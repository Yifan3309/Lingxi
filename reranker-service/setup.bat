@echo off
rem 一次性初始化 reranker sidecar：创建独立 venv 并安装依赖（不碰 conda/系统环境）
cd /d %~dp0
python -m venv venv
venv\Scripts\python.exe -m pip install --disable-pip-version-check -r requirements.txt
echo.
echo 完成。启动服务请运行 start.bat
