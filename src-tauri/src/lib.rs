use std::io::ErrorKind;
use std::net::TcpStream;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tauri::{Manager, RunEvent, State};

/** Java 后端子进程，退出时需要回收。 */
struct Backend(Mutex<Option<Child>>);

const BACKEND_URL: &str = "http://127.0.0.1:8080";

/** 解析应用主目录（Spring Boot 的 CWD：data/ models/ 都在其下）。 */
fn resolve_home() -> PathBuf {
    if let Ok(home) = std::env::var("LINGXI_HOME") {
        if !home.trim().is_empty() {
            return PathBuf::from(home);
        }
    }
    // 第一版本机版：默认项目目录；分发版（决策 20 下一步）改 AppData + 资源内嵌
    PathBuf::from(r"D:\project_learning\LLM_native_notebool")
}

fn spawn_backend(home: &PathBuf) -> std::io::Result<Child> {
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
fn wait_backend_ready(timeout: Duration) -> Result<(), String> {
    let start = Instant::now();
    loop {
        match TcpStream::connect(("127.0.0.1", 8080)) {
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
    let home = resolve_home();
    match spawn_backend(&home) {
        Ok(child) => {
            *state.0.lock().unwrap() = Some(child);
            let window = app.get_webview_window("main");
            std::thread::spawn(move || {
                if wait_backend_ready(Duration::from_secs(90)).is_ok() {
                    navigate_to_app(window.as_ref());
                }
            });
        }
        Err(e) => log(&format!("重启后端失败：{e}")),
    }
}

fn navigate_to_app(window: Option<&tauri::WebviewWindow>) {
    if let Some(w) = window {
        let _ = w.eval(&format!("location.replace('{BACKEND_URL}')"));
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
            match spawn_backend(&home) {
                Ok(child) => {
                    app.state::<Backend>().0.lock().unwrap().replace(child);
                    std::thread::spawn(move || match wait_backend_ready(Duration::from_secs(90)) {
                        Ok(()) => {
                            log("后端就绪，跳转应用");
                            navigate_to_app(window.as_ref());
                        }
                        Err(e) => {
                            log(&e);
                            if let Some(w) = window {
                                let _ = w.eval(&format!(
                                    "window.__showBackendError({:?})",
                                    format!("{e}\n请确认 target/lingxi.jar 存在、models/ 下的嵌入与重排模型已就位（模型不随安装器分发）、8080 端口未被占用；后端启动日志见 data/backend.log")
                                ));
                            }
                        }
                    });
                }
                Err(e) => {
                    if let Some(w) = window {
                        let _ = w.eval(&format!(
                            "window.__showBackendError({:?})",
                            format!("后端进程启动失败：{e}")
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
