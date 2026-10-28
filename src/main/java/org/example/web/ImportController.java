package org.example.web;

import org.example.domain.Note;
import org.example.rag.DocumentImportService;
import org.example.rag.VaultImportService;
import org.example.rag.WebClipService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * 输入扩展：多模态导入（分级解析，DocumentImportService）、网页剪藏（WebClipService）、
 * 旧仓库一键迁移（VaultImportService，扫描预览 → 异步迁移 → 进度轮询）。
 */
@RestController
@RequestMapping("/api/notes")
public class ImportController {

    private final DocumentImportService importService;
    private final WebClipService clipService;
    private final VaultImportService vaultImportService;

    public ImportController(DocumentImportService importService, WebClipService clipService,
                            VaultImportService vaultImportService) {
        this.importService = importService;
        this.clipService = clipService;
        this.vaultImportService = vaultImportService;
    }

    public record ClipRequest(String url) {}

    public record VaultPathRequest(String path) {}

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

    /** 迁移扫描预览：轻量遍历只数不读，返回 md/文件夹/图片统计与顶层目录。 */
    @PostMapping("/vault-import/scan")
    public VaultImportService.VaultScanReport vaultScan(@RequestBody VaultPathRequest request) {
        return vaultImportService.scan(request.path());
    }

    /** 触发迁移（异步）：仿 reembed——运行中 409，202 返回初始进度，GET status 轮询至 running=false。 */
    @PostMapping("/vault-import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VaultImportService.VaultImportStatus vaultImport(@RequestBody VaultPathRequest request) {
        vaultImportService.validateRoot(request.path());
        if (!vaultImportService.tryStart()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "迁移已在进行中");
        }
        vaultImportService.run(request.path());
        return vaultImportService.status();
    }

    @GetMapping("/vault-import/status")
    public VaultImportService.VaultImportStatus vaultImportStatus() {
        return vaultImportService.status();
    }
}
