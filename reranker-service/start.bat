@echo off
rem 启动 reranker sidecar（127.0.0.1:8900）。Java 应用需配置 app.rag.reranker-provider: http
cd /d %~dp0
venv\Scripts\python.exe app.py
