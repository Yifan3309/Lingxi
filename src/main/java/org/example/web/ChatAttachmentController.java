package org.example.web;

import org.example.rag.ChatAttachmentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 问答附件上传（@链接-上传来源）：Tika 抽文本 → 内存 TTL 缓存 → 返回 uploadId。
 * 仅本轮上下文，不建笔记、不落盘、不写向量。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatAttachmentController {

    private final ChatAttachmentService attachmentService;

    public ChatAttachmentController(ChatAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAttachmentService.UploadResult upload(@RequestParam("file") MultipartFile file) {
        return attachmentService.storeUpload(file);
    }
}
