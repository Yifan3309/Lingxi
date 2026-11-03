; 灵犀安装器钩子（NSIS / Tauri v2）
;
; 模型文件不进安装包（合计约 1.1GB），安装完必须告诉用户去哪下——
; 否则应用会因为缺嵌入模型起不来，用户只知道"装完了但打不开"。
!macro NSIS_HOOK_PREINSTALL
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; 模型目录先建好：用户只要把下载好的文件丢进去，不用自己建目录
  CreateDirectory "$INSTDIR\models\embedding\Multilingual-E5-small"
  CreateDirectory "$INSTDIR\models\reranker\onnx-communitybge-reranker-v2-m3-ONNX"

  MessageBox MB_YESNO "安装完成。$\r$\n$\r$\n灵犀需要本地模型才能工作（安装器不携带，约 1GB）：$\r$\n$\r$\n  1. 嵌入模型（必需）$\r$\n     models\embedding\Multilingual-E5-small\（470MB + 17MB）$\r$\n  2. 重排模型（可选，可后补）$\r$\n     models\reranker\onnx-communitybge-reranker-v2-m3-ONNX\（544MB + 17MB）$\r$\n$\r$\n下载地址见安装目录下的《模型放置说明.txt》。$\r$\n$\r$\n现在打开 HuggingFace 下载页面吗？" IDYES lx_open_hf IDNO lx_no_open

  lx_open_hf:
    ExecShell "open" "https://huggingface.co/intfloat/multilingual-e5-small/tree/main/onnx"
    Goto lx_done

  lx_no_open:
  lx_done:
!macroend

!macro NSIS_HOOK_PREUNINSTALL
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
!macroend
