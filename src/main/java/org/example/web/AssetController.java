package org.example.web;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 附件（图片）落盘：图片池设计——不关联笔记，新笔记未保存也能插图；
 * note_asset 表与孤儿清理留到 OCR 里程碑。md 中以 API 路径引用
 * （![](/api/assets/xxx.png)），第二步文件化时统一切相对路径。
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private static final Set<String> ALLOWED_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp");
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Path ROOT = Path.of("data/assets").toAbsolutePath().normalize();

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    @PostMapping
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extOf(original);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持图片: " + String.join("/", ALLOWED_EXT));
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片不能超过 10MB");
        }
        Files.createDirectories(ROOT);
        String name = UUID.randomUUID() + "." + ext;
        file.transferTo(ROOT.resolve(name));
        return Map.of("url", "/api/assets/" + name);
    }

    @GetMapping("/{name}")
    public ResponseEntity<Resource> get(@PathVariable String name) {
        Path file = ROOT.resolve(name).normalize();
        if (!file.startsWith(ROOT)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法路径");
        }
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        MediaType type = MediaType.parseMediaType(
                CONTENT_TYPES.getOrDefault(extOf(file.getFileName().toString()), "application/octet-stream"));
        return ResponseEntity.ok()
                .contentType(type)
                .body(new FileSystemResource(file));
    }

    private static String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
