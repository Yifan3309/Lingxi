use std::io::ErrorKind;
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use tauri::{Manager, RunEvent, State};

/** Java 后端子进程，退出时需要回收。 */
struct Backend(Mutex<Option<Child>>);

/// 首选端口：与开发环境一致，装完也能用浏览器直开。
const PREFERRED_PORT: u16 = 8080;
/// 首选端口被占时的候选区间（安装态自动避让用）。
const FALLBACK_PORTS: std::ops::Range<u16> = 8081..8099;

/**
 * 解析应用主目录（Spring Boot 的 CWD：data/ models/ 都在其下）。
 *
 * 优先级：
 * 1. `LINGXI_HOME` 环境变量——分发版把模型和数据放到别处时用它；
 * 2. 开发态：exe 在 `src-tauri/target/{debug,release}/` 下，主目录应是项目根（data/ models/ 在那）；
 * 3. 安装态：exe 所在目录就是 `$INSTDIR`，安装器把 jar 装在它的 `target/` 子目录下。
 *
 * **不要在这里写死某个开发机的路径**：后端所有数据目录都是相对 CWD 解析的
 * （./data/notes、data/embeddings.json、data/runtime-config.json），
 * CWD 由本函数决定，于是"装到哪、数据就落在哪"，不会串到开发环境的数据。
 */
fn resolve_home() -> PathBuf {
    if let Ok(home) = std::env::var("LINGXI_HOME") {
        if !home.trim().is_empty() {
            return PathBuf::from(home);
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            return project_root_of(dir).unwrap_or_else(|| dir.to_path_buf());
        }
    }
    // current_exe 失败是极端情况：退回当前工作目录，而不是某个固定路径
    std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
}

/// 开发态定位项目根：路径上同时有 `src-tauri/` 与 `pom.xml` 的那一层。
/// 安装态没有这个结构，返回 None，调用方退回 exe 所在目录。
fn project_root_of(dir: &Path) -> Option<PathBuf> {
    dir.ancestors()
        .find(|a| a.join("src-tauri").is_dir() && a.join("pom.xml").is_file())
        .map(|a| a.to_path_buf())
}

/// 开发态判定：exe 路径里带 `target` 段（cargo build / tauri dev 的产物位置）。
/// 安装态的 exe 在 `$INSTDIR` 下，没有这一段。
fn is_dev_build() -> bool {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(Path::to_path_buf))
        .map(|dir| {
            dir.components()
                .any(|c| c.as_os_str().to_string_lossy().eq_ignore_ascii_case("target"))
        })
        .unwrap_or(false)
}

/// 端口上是否已经有服务在听。
fn port_in_use(port: u16) -> bool {
    TcpStream::connect(("127.0.0.1", port)).is_ok()
}

/// 在候选区间里找一个能绑定的端口（绑上立刻释放，避免和随后的后端抢端口时留空档）。
fn find_free_port(range: std::ops::Range<u16>) -> Option<u16> {
    range.into_iter().find(|&port| {
        TcpListener::bind(("127.0.0.1", port))
            .map(|l| drop(l))
            .is_ok()
    })
}

/// 后端接入方案：用哪个端口、要不要自己拉起。
struct BackendPlan {
    port: u16,
    /// true = 自己 spawn 后端；false = 复用已经在跑的实例（仅开发态）
    spawn: bool,
    note: String,
}

/**
 * 决定用哪个端口、是否自己拉起后端。
 *
 * <p><b>安装态绝不能复用 8080 上已有的服务。</b>那个端口上多半是开发者自己开着的
 * 后端（`mvn spring-boot:run`），一旦复用，窗口里打开的就是**开发库的数据**——
 * 而安装版自己的数据在 `$INSTDIR\data`，两者根本不是一回事。所以安装态遇到端口占用
 * 就换一个空闲端口、自己拉起后端，物理隔离。
 *
 * <p>开发态相反：`tauri dev` 时用户常常自己开着后端，此时复用它是既有行为，别抢端口。
 */
fn plan_backend() -> BackendPlan {
    if !port_in_use(PREFERRED_PORT) {
        return BackendPlan {
            port: PREFERRED_PORT,
            spawn: true,
            note: format!("后端端口 {PREFERRED_PORT}"),
        };
    }
    if is_dev_build() {
        return BackendPlan {
            port: PREFERRED_PORT,
            spawn: false,
            note: "检测到 8080 已有服务（开发态），直接复用".to_string(),
        };
    }
    match find_free_port(FALLBACK_PORTS) {
        Some(port) => BackendPlan {
            port,
            spawn: true,
            note: format!(
                "8080 已被其他服务占用（多半是开发环境的后端），本实例改用 {port}，避免误连到别人的数据"
            ),
        },
        None => BackendPlan {
            port: PREFERRED_PORT,
            spawn: true,
            note: format!("{}–8099 都没有空闲端口，仍按 {PREFERRED_PORT} 尝试", PREFERRED_PORT),
        },
    }
}

fn spawn_backend(home: &Path, port: u16) -> std::io::Result<Child> {
    let jar = home.join("target").join("lingxi.jar");
    if !jar.exists() {
        return Err(std::io::Error::other(format!("找不到 {}", jar.display())));
    }
    // java 取 PATH；CREATE_NO_WINDOW 防止后端弹黑窗
    let mut cmd = Command::new("java");
    cmd.arg("-jar")
        .arg(&jar)
        // 桌面场景不需要占满核数：给容器化式的温和线程数，GUI 常驻更友好
        .arg("-Djava.awt.headless=true")
        // 端口由壳决定：安装态要让开开发环境占着的 8080
        .arg(format!("--server.port={port}"))
        .current_dir(home)
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    cmd.spawn()
}

/** 轮询后端端口就绪（Tomcat 监听即基本可用），超时返回 Err。 */
fn wait_backend_ready(port: u16, timeout: Duration) -> Result<(), String> {
    let start = Instant::now();
    loop {
        match TcpStream::connect(("127.0.0.1", port)) {
            Ok(_) => return Ok(()),
            Err(e) if e.kind() == ErrorKind::ConnectionRefused => {}
            Err(e) => log(&format!("探测端口异常：{e}")),
        }
        if start.elapsed() > timeout {
            return Err(format!("等待后端就绪超时（{} 秒）", timeout.as_secs()));
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}

fn log(msg: &str) {
    println!("[lingxi-shell] {msg}");
}

#[tauri::command]
fn restart_backend(state: State<Backend>, app: tauri::AppHandle) {
    if let Some(mut child) = state.0.lock().unwrap().take() {
        let _ = child.kill();
    }
    // 刚 kill 掉的后端，端口释放要一会儿
    for _ in 0..25 {
        if !port_in_use(PREFERRED_PORT) {
            break;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    let home = resolve_home();
    let plan = plan_backend();
    log(&plan.note);
    if !plan.spawn {
        let window = app.get_webview_window("main");
        navigate_to_app(window.as_ref(), plan.port);
        return;
    }
    match spawn_backend(&home, plan.port) {
        Ok(child) => {
            *state.0.lock().unwrap() = Some(child);
            let window = app.get_webview_window("main");
            std::thread::spawn(move || {
                if wait_backend_ready(plan.port, Duration::from_secs(90)).is_ok() {
                    navigate_to_app(window.as_ref(), plan.port);
                }
            });
        }
        Err(e) => log(&format!("重启后端失败：{e}")),
    }
}

fn navigate_to_app(window: Option<&tauri::WebviewWindow>, port: u16) {
    if let Some(w) = window {
        // 带一个时间戳回源：WebView2 的缓存跨启动留存，旧的 index.html 会指向旧构建的
        // chunk 名（chunk 名带内容 hash）。服务端换了 jar 之后那些文件已不存在，
        // 动态 import 直接 404——「Failed to fetch dynamically imported module」。
        // 后端已对 index.html 发 no-store，但升级前就缓存下来的那份仍需靠查询串绕开。
        let bust = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let _ = w.eval(&format!("location.replace('http://127.0.0.1:{port}/?v={bust}')"));
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let home = resolve_home();
    log(&format!("应用主目录：{}", home.display()));

    tauri::Builder::default()
        .manage(Backend(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![restart_backend])
        .setup(move |app| {
            let window = app.get_webview_window("main");
            let plan = plan_backend();
            log(&plan.note);
            if !plan.spawn {
                // 开发态：复用已在跑的后端，直接跳过去
                navigate_to_app(window.as_ref(), plan.port);
                return Ok(());
            }
            match spawn_backend(&home, plan.port) {
                Ok(child) => {
                    app.state::<Backend>().0.lock().unwrap().replace(child);
                    std::thread::spawn(move || match wait_backend_ready(plan.port, Duration::from_secs(90)) {
                        Ok(()) => {
                            log("后端就绪，跳转应用");
                            navigate_to_app(window.as_ref(), plan.port);
                        }
                        Err(e) => {
                            log(&e);
                            if let Some(w) = window {
                                let _ = w.eval(&format!(
                                    "window.__showBackendError({:?})",
                                    format!("{e}\n请确认 target/lingxi.jar 存在、models/ 下的嵌入与重排模型已就位（模型不随安装器分发）；后端启动日志见 data/backend.log")
                                ));
                            }
                        }
                    });
                }
                Err(e) => {
                    if let Some(w) = window {
                        let _ = w.eval(&format!(
                            "window.__showBackendError({:?})",
                            format!("后端进程启动失败：{e}\n请确认已安装 Java 21 或更高版本且 java 命令在 PATH 中、{}/target/lingxi.jar 存在", home.display())
                        ));
                    }
                }
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            // Windows 父进程退出不会自动杀子进程：壳退出时显式回收 java，不留孤儿
            if let RunEvent::Exit = event {
                if let Some(mut child) = app.state::<Backend>().0.lock().unwrap().take() {
                    log("退出：停止后端进程");
                    let _ = child.kill();
                    let _ = child.wait();
                }
            }
        });
}
