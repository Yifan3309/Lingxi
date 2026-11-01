@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ============================================================================
rem  前端一键构建：npm run build + 构建产物镜像到 target
rem
rem  用法（在 frontend 目录下执行）：
rem    build.bat                 构建 + 镜像到 ..\target\classes\static
rem    build.bat -ToJar          额外增量更新 ..\target\lingxi.jar 内的静态资源
rem    build.bat -SkipInstall    跳过 npm ci（node_modules 已存在时）
rem    build.bat -Verbose        逐文件列出镜像明细（默认只显示统计汇总）
rem    build.bat -NoPause        失败时不暂停窗口（供自动化调用）
rem
rem  本脚本不静默：
rem    - 每一步都打印进度与时间戳
rem    - 镜像显示统计汇总（总数/复制/跳过/失败），-Verbose 可看逐文件明细
rem    - 7z 保留摘要输出
rem    - 失败时暂停窗口，便于查看错误原因
rem
rem  说明：
rem    - vite 的 outDir 已是 ..\src\main\resources\static（Spring Boot 静态目录）
rem    - 这里再整目录镜像到 ..\target\classes\static；/MIR 会清掉带 hash 的孤儿 chunk
rem    - 只有 target\classes\static 默认被更新；lingxi.jar 需显式加 -ToJar
rem ============================================================================

set "SKIP_INSTALL=0"
set "TO_JAR=0"
set "NOPAUSE=0"
set "VERBOSE=0"

:parseArgs
if "%~1"=="" goto argsDone
if /i "%~1"=="-tojar" set "TO_JAR=1"
if /i "%~1"=="-skipinstall" set "SKIP_INSTALL=1"
if /i "%~1"=="-nopause" set "NOPAUSE=1"
if /i "%~1"=="-verbose" set "VERBOSE=1"
shift
goto parseArgs
:argsDone

rem 注意：括号块内的变量在解析时整体展开，这些必须在块外赋值
set "PROJECT_ROOT=%CD%\.."
set "OUT_DIR=%PROJECT_ROOT%\src\main\resources\static"
set "DEST_DIR=%PROJECT_ROOT%\target\classes\static"
set "JAR_FILE=%PROJECT_ROOT%\target\lingxi.jar"
set "TMP_STAGE=%TEMP%\lingxi-jar-stage"

rem 镜像参数：默认只看统计汇总；-Verbose 时逐文件列出
if "%VERBOSE%"=="1" (
  set "MIR_FLAGS=/MIR /NJH /NJS /NP /R:1 /W:1"
) else (
  set "MIR_FLAGS=/MIR /NFL /NDL /NP /R:1 /W:1"
)

echo.
echo ============================================================
echo  前端构建 - 产物镜像到 target
echo  项目根目录 : %PROJECT_ROOT%
echo  构建输出   : %OUT_DIR%
echo  镜像目标   : %DEST_DIR%
echo  开始时间   : %DATE% %TIME%
echo ============================================================
echo.

where npm >nul 2>nul
if errorlevel 1 (
  echo [%TIME%] [错误] 找不到 npm，请确认已安装 Node.js 并加入 PATH
  goto :fail
)

echo [%TIME%] [1/4] 检查依赖 ...
if exist "node_modules" (
  echo        node_modules 已存在
  if "%SKIP_INSTALL%"=="1" echo        按 -SkipInstall 跳过安装
) else (
  if "%SKIP_INSTALL%"=="1" (
    echo [错误] 缺少 node_modules，且指定了 -SkipInstall，请先执行 npm ci
    goto :fail
  )
  echo        未找到 node_modules，开始 npm ci ...
  call npm ci
  if errorlevel 1 (
    echo [错误] 依赖安装失败
    goto :fail
  )
  echo        依赖安装完成
)
echo.

echo [%TIME%] [2/4] 构建前端 npm run build ...
call npm run build
if errorlevel 1 (
  echo [错误] 构建失败
  goto :fail
)
if not exist "%OUT_DIR%\index.html" (
  echo [错误] 未找到构建产物 %OUT_DIR%\index.html
  goto :fail
)
echo        构建产物已就位
echo.

echo [%TIME%] [3/4] 整目录镜像到 %DEST_DIR% ...
if not exist "%DEST_DIR%" mkdir "%DEST_DIR%"
rem /MIR 会删除目标中源已不存在的旧文件（带 hash 的孤儿 chunk）
robocopy "%OUT_DIR%" "%DEST_DIR%" !MIR_FLAGS!
set "ROBO_RC=!ERRORLEVEL!"
if !ROBO_RC! GEQ 8 (
  echo [错误] 镜像失败，robocopy 退出码 !ROBO_RC!
  goto :fail
)
echo        镜像完成（robocopy 退出码 !ROBO_RC!，0-7 均为成功；失败数见上表）
echo.

if "%TO_JAR%"=="1" (
  if not exist "%JAR_FILE%" (
    echo [%TIME%] [4/4] 未找到 %JAR_FILE%，跳过 jar 更新
  ) else (
    echo [%TIME%] [4/4] 更新 %JAR_FILE% 内的静态资源 ...
    where 7z >nul 2>nul
    if errorlevel 1 (
      echo [错误] 未找到 7z 命令，无法增量更新 jar；请改用 mvn package 重新打包
      goto :fail
    )
    if exist "%TMP_STAGE%" rmdir /s /q "%TMP_STAGE%"
    mkdir "%TMP_STAGE%\BOOT-INF\classes"
    robocopy "%OUT_DIR%" "%TMP_STAGE%\BOOT-INF\classes\static" /MIR /NFL /NDL /NJH /NJS /NP /R:1 /W:1
    if errorlevel 8 (
      echo [错误] 准备 jar 暂存目录失败
      goto :fail
    )
    echo        暂存副本就绪，先删 jar 内旧 static 目录 ...
    pushd "%TMP_STAGE%"
    7z d -tzip "%JAR_FILE%" "BOOT-INF/classes/static" -r -bsp0
    if errorlevel 1 (
      popd
      echo [错误] 删除 jar 内旧 static 失败
      goto :fail
    )
    echo        再写入新的 static ...
    7z u -tzip "%JAR_FILE%" "BOOT-INF" -r -bsp0
    if errorlevel 1 (
      popd
      echo [错误] 写入 jar 内 static 失败
      goto :fail
    )
    popd
    rmdir /s /q "%TMP_STAGE%"
    echo        jar 已更新
  )
) else (
  echo [%TIME%] [4/4] 跳过 jar 更新（需要时加 -ToJar）
)

echo.
echo ============================================================
echo  完成。产物已就位：
echo    %DEST_DIR%
if "%TO_JAR%"=="1" echo    %JAR_FILE%  （jar 内 static 已更新）
echo  结束时间 : %DATE% %TIME%
echo ============================================================
echo.
endlocal
exit /b 0

:fail
echo.
echo ============================================================
echo  [失败] 构建未完成，请查看上方输出
echo ============================================================
echo.
if "%NOPAUSE%"=="0" pause
endlocal
exit /b 1
