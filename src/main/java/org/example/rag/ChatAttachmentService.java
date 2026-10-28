package org.example.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.example.config.ChatProperties;
import org.example.domain.Note;
import org.example.repository.NoteRepository;
import org.example.web.dto.ChatAttachment;
import org.example.web.dto.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 问答附件（@链接）解析：知识库笔记 / 文件夹 / 本地文件 / 已上传文件。
 * 全部只读——不建 Note、不写向量、不落盘（上传仅内存 TTL 缓存）。
 * 文本统一截断到上限后按标记拼进本轮提问；笔记附件同时产出 SourceRef 供跳转。
 */
@Service
public class ChatAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg");
    private static final int PREVIEW_CHARS = 120;

    private final NoteRepository noteRepo;
    private final ChatProperties props;
    private final ApacheTikaDocumentParser tika = new ApacheTikaDocumentParser();
    private final Map<String, StoredUpload> uploads = new ConcurrentHashMap<>();

    public ChatAttachmentService(NoteRepository noteRepo, ChatProperties props) {
        this.noteRepo = noteRepo;
        this.props = props;
    }

    /** 解析后的一段附件文本 + 可点击来源（note/folder 有，localFile/upload 无）。 */
    public record ResolvedAttachment(String type, String name, String text, boolean truncated, List<SourceRef> refs) {
    }

    /** 上传解析结果（POST /api/chat/attachments 返回体）。 */
    public record UploadResult(String uploadId, String name, int chars, boolean truncated, String preview) {
    }

    private record StoredUpload(String name, String text, long expiresAtMillis) {
    }

    /** 校验并解析全部附件；上限/白名单/loopback 不满足直接 400/403。 */
    public List<ResolvedAttachment> resolve(List<ChatAttachment> attachments, boolean fromLoopback) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        if (attachments.size() > props.maxAttachments()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "附件最多 " + props.maxAttachments() + " 个（当前 " + attachments.size() + " 个）");
        }
        List<ResolvedAttachment> out = new ArrayList<>();
        AtomicInteger remaining = new AtomicInteger(props.maxTotalChars());
        for (ChatAttachment a : attachments) {
            if (remaining.get() <= 0) {
                log.info("附件总字数已达上限 {}，后续附件跳过", props.maxTotalChars());
                break;
            }
            ResolvedAttachment r = resolveOne(a, fromLoopback, remaining.get());
            if (r == null || r.text().isBlank()) {
                continue;
            }
            remaining.addAndGet(-r.text().length());
            out.add(r);
        }
        if (out.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可用的附件内容");
        }
        return out;
    }

    private ResolvedAttachment resolveOne(ChatAttachment a, boolean fromLoopback, int budget) {
        String type = a.type() == null ? "" : a.type().trim();
        return switch (type) {
            case "note" -> resolveNote(a, budget);
            case "folder" -> resolveFolder(a, budget);
            case "localFile" -> resolveLocalFile(a, fromLoopback, budget);
            case "upload" -> resolveUpload(a, budget);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知附件类型：" + type + "（支持 note/folder/localFile/upload）");
        };
    }

    private ResolvedAttachment resolveNote(ChatAttachment a, int budget) {
        if (a.id() == null || a.id().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "笔记附件缺少 id");
        }
        Note note = noteRepo.findById(a.id().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在: " + a.id()));
        String text = cut(contentOf(note), Math.min(props.maxNoteChars(), budget));
        boolean truncated = text.endsWith("（已截断）");
        return new ResolvedAttachment("note", titleOf(note), text, truncated, List.of(excerptRef(note, text)));
    }

    private ResolvedAttachment resolveFolder(ChatAttachment a, int budget) {
        String folder = a.name() == null ? "" : a.name().trim().replace('\\', '/');
        if (folder.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件夹附件缺少 name");
        }
        // 本层 + 子层直接查库（原来是 findAll 全表加载再内存过滤，笔记多了会拖慢整个提问）
        List<Note> notes = noteRepo.findByFolderOrFolderStartingWith(
                        folder, folder + "/", Sort.by(Sort.Direction.DESC, "updatedAt"))
                .stream()
                .limit(props.maxFolderNotes())
                .toList();
        if (notes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件夹下没有笔记: " + folder);
        }
        int cap = Math.min(props.maxFolderChars(), budget);
        StringBuilder sb = new StringBuilder();
        List<SourceRef> refs = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        for (Note n : notes) {
            String piece = "\n\n### " + titleOf(n) + "\n" + contentOf(n);
            int room = cap - used;
            if (room <= 0) {
                truncated = true;
                break;
            }
            if (piece.length() > room) {
                piece = cut(piece, room);
                truncated = true;
            }
            sb.append(piece);
            used += piece.length();
            refs.add(excerptRef(n, piece));
        }
        return new ResolvedAttachment("folder", folder, sb.toString().trim(), truncated, refs);
    }

    private ResolvedAttachment resolveLocalFile(ChatAttachment a, boolean fromLoopback, int budget) {
        if (!props.allowLocalFiles()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本地文件附件已禁用（app.chat.allow-local-files=false）");
        }
        if (props.localFilesRequireLoopback() && !fromLoopback) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本地文件附件仅允许本机访问");
        }
        if (a.path() == null || a.path().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本地文件附件缺少 path");
        }
        Path raw;
        try {
            raw = Path.of(a.path().trim());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路径非法：" + a.path());
        }
        if (!raw.isAbsolute()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持绝对路径：" + a.path());
        }
        Path real;
        try {
            real = raw.toRealPath();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在或无法访问：" + a.path());
        }
        if (!Files.isRegularFile(real)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不是普通文件：" + a.path());
        }
        long size;
        try {
            size = Files.size(real);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取文件大小：" + a.path());
        }
        if (size > props.maxFileBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文件超过 " + (props.maxFileBytes() / 1024 / 1024) + "MB：" + real.getFileName());
        }
        String name = real.getFileName().toString();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(real);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取文件失败：" + e.getMessage());
        }
        String text = parseText(name, bytes);
        text = cut(text, Math.min(props.maxFileChars(), budget));
        boolean truncated = text.endsWith("（已截断）");
        return new ResolvedAttachment("localFile", name, text, truncated, List.of());
    }

    private ResolvedAttachment resolveUpload(ChatAttachment a, int budget) {
        if (a.uploadId() == null || a.uploadId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传附件缺少 uploadId");
        }
        StoredUpload up = uploads.get(a.uploadId().trim());
        if (up == null || up.expiresAtMillis() < System.currentTimeMillis()) {
            if (up != null) {
                uploads.remove(a.uploadId().trim());
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上传已过期，请重新选择文件");
        }
        String text = cut(up.text(), Math.min(props.maxFileChars(), budget));
        boolean truncated = text.endsWith("（已截断）");
        return new ResolvedAttachment("upload", up.name(), text, truncated, List.of());
    }

    /** 上传解析：Tika 抽文本，内存 TTL 缓存，不落盘不入库。 */
    public UploadResult storeUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        if (file.getSize() > props.maxFileBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文件超过 " + (props.maxFileBytes() / 1024 / 1024) + "MB");
        }
        String name = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        String ext = extOf(name);
        if (IMAGE_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片附件暂不支持（首版仅文本类文件）");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取上传文件失败：" + e.getMessage());
        }
        String text = parseText(name, bytes);
        evictIfNeeded();
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        long ttl = System.currentTimeMillis() + props.uploadTtlMinutes() * 60_000L;
        String clipped = cut(text, props.maxFileChars());
        uploads.put(uploadId, new StoredUpload(name, clipped, ttl));
        log.info("问答附件上传 {}（{} 字）→ {}", name, text.length(), uploadId);
        return new UploadResult(uploadId, name, text.length(),
                clipped.endsWith("（已截断）"), preview(clipped));
    }

    /** 组装本轮增强提问；无附件时原样返回（与旧行为完全一致）。 */
    public String augment(String question, List<ResolvedAttachment> resolved) {
        if (resolved == null || resolved.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ChatAttachmentMarkers.ATTACH_BEGIN).append('\n');
        for (ResolvedAttachment r : resolved) {
            sb.append("<attachment type=\"").append(r.type())
                    .append("\" name=\"").append(escapeAttr(r.name())).append("\">\n");
            sb.append(ChatAttachmentMarkers.escape(r.text())).append('\n');
            sb.append("</attachment>\n");
        }
        sb.append(ChatAttachmentMarkers.ATTACH_END).append("\n\n");
        sb.append(ChatAttachmentMarkers.QUESTION_BEGIN).append('\n');
        sb.append(ChatAttachmentMarkers.escape(question)).append('\n');
        sb.append(ChatAttachmentMarkers.QUESTION_END);
        return sb.toString();
    }

    /* ---- 内部工具 ---- */

    private String parseText(String name, byte[] bytes) {
        String ext = extOf(name);
        if (IMAGE_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片附件暂不支持（首版仅文本类文件）");
        }
        if (!props.allowedExtensions().contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的类型 ." + ext + "（支持: md/txt/pdf/docx/pptx/html/csv/代码）");
        }
        String text;
        try {
            Document doc = tika.parse(new ByteArrayInputStream(bytes));
            text = doc.text() == null ? "" : doc.text();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "解析失败：" + e.getMessage());
        }
        if ("pdf".equals(ext) && text.trim().length() < props.minPdfChars()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PDF 几乎无文本（可能是扫描版），暂不支持作为问答附件");
        }
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件没有可提取的文本");
        }
        return text;
    }

    private static String contentOf(Note note) {
        String content = note.getContent() == null ? "" : note.getContent();
        return content.isBlank() ? note.getTitle() : content;
    }

    private static String titleOf(Note note) {
        return note.getTitle() == null || note.getTitle().isBlank() ? "未命名" : note.getTitle();
    }

    private static SourceRef excerptRef(Note note, String text) {
        String excerpt = text.replace("\n", " ").trim();
        if (excerpt.length() > PREVIEW_CHARS) {
            excerpt = excerpt.substring(0, PREVIEW_CHARS) + "…";
        }
        return new SourceRef(note.getId(), titleOf(note), null, null, excerpt);
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 8)) + "（已截断）";
    }

    private static String preview(String text) {
        String flat = text.replace("\n", " ").trim();
        return flat.length() > PREVIEW_CHARS ? flat.substring(0, PREVIEW_CHARS) + "…" : flat;
    }

    private static String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String escapeAttr(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("<", "").replace(">", "");
    }

    private void evictIfNeeded() {
        uploads.entrySet().removeIf(e -> e.getValue().expiresAtMillis() < System.currentTimeMillis());
        while (uploads.size() >= props.uploadCacheSize()) {
            Iterator<String> it = uploads.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            uploads.remove(it.next());
        }
    }
}
