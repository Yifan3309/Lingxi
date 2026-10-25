package org.example.web;

import org.example.domain.Note;
import org.example.rag.DocumentImportService;
import org.example.rag.WebClipService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 输入扩展：多模态导入（分级解析，DocumentImportService）与网页剪藏（WebClipService）。
 */
@RestController
@RequestMapping("/api/notes")
public class ImportController {

    private final DocumentImportService importService;
    private final WebClipService clipService;

    public ImportController(DocumentImportService importService, WebClipService clipService) {
        this.importService = importService;
        this.clipService = clipService;
    }

    public record ClipRequest(String url) {}

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public Note importFile(@RequestParam("file") MultipartFile file) throws IOException {
        return importService.importFile(file);
    }

    @PostMapping("/clip")
    @ResponseStatus(HttpStatus.CREATED)
    public Note clip(@RequestBody ClipRequest request) {
        return clipService.clip(request.url());
    }
}
