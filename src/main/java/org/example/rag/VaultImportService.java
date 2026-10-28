package org.example.rag;

import org.example.config.VaultProperties;
import org.example.domain.Note;
import org.example.domain.NoteImport;
import org.example.repository.NoteImportRepository;
import org.example.repository.NoteRepository;
import org.example.service.NoteService;
import org.example.web.dto.NoteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旧仓库一键迁移（M4-2）：本机 md 文件夹 → 灵犀笔记。
 * 两段式：scan 轻量预览（只数不读）→ start 异步逐篇迁移（vaultExecutor 单线程，进度轮询）。
 * 智能解析：UTF-8 严格解码失败回退 GBK；frontmatter（SnakeYAML）提取 title/tags/folder；
 * 相对目录映射为 folder 路径（根目录 → 未归类）；md 相对图片链与 Obsidian wiki 嵌入
 * ![[name]] 落地到 data/assets 并重写为 /api/assets 链接；正文超限截断。
 * 幂等凭证 note_import（源路径 + SHA-256）：未变更跳过、变更原地更新（保留笔记 id）、笔记已删重建。
 * AI 富化按用户决策在迁移中不触发（显式富化模型，2026-10-26）。
 */
@Service
public class VaultImportService {

    private static final Logger log = LoggerFactory.getLogger(VaultImportService.class);

    private static final List<String> MD_EXT = List.of("md", "markdown");
    private static final List<String> IMAGE_EXT = List.of("png", "jpg", "jpeg", "gif", "webp");
    /** markdown 相对图片链 ![alt](path "title")；外链/绝对路径不改动 */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(\\s*([^)\\s]+)(?:\\s+\"[^\"]*\")?\\s*\\)");
    /** Obsidian wiki 嵌入 ![[name]] / ![[name|600]] */
    private static final Pattern WIKI_EMBED = Pattern.compile("!\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\]");

    private static final Path ASSETS = Path.of("data/assets").toAbsolutePath().normalize();
    private static final int CONTENT_LIMIT = 60000;   // note.content 列 65536，留余量给截断说明
    private static final int TITLE_MAX = 255;         // Note.title 默认 VARCHAR(255)
    private static final int TAGS_MAX = 255;          // Note.tags 默认 VARCHAR(255)
    private static final int MAX_TRACKED_IDS = 100;   // 状态里带回的笔记 id 上限

    private final NoteService noteService;
    private final NoteRepository noteRepo;
    private final NoteImportRepository importRepo;
    private final VaultProperties props;

    /** 进度（volatile，仿 ReembedService）：start 重置，run 单线程独占写，status 轮询读 */
    private volatile boolean running = false;
    private volatile int total = 0;
    private volatile int done = 0;
    private volatile int imported = 0;
    private volatile int updated = 0;
    private volatile int skipped = 0;
    private volatile int failed = 0;
    private volatile int imagesCopied = 0;
    private volatile int imagesBroken = 0;
    private volatile int gbkConverted = 0;
    private volatile int truncated = 0;
    private volatile String currentFile = "";
    private volatile List<String> errors = List.of();
    private volatile List<String> importedNoteIds = List.of();

    public VaultImportService(NoteService noteService, NoteRepository noteRepo,
                              NoteImportRepository importRepo, VaultProperties props) {
        this.noteService = noteService;
        this.noteRepo = noteRepo;
        this.importRepo = importRepo;
        this.props = props;
    }

    public record VaultScanReport(String root, int mdCount, int folderCount, int imageFileCount,
                                  int excludedCount, List<String> topLevelFolders, boolean truncated) {
    }

    public record VaultImportStatus(boolean running, int total, int done, int imported, int updated,
                                    int skipped, int failed, int imagesCopied, int imagesBroken,
                                    int gbkConverted, int truncated, String currentFile,
                                    List<String> errors, List<String> importedNoteIds) {
    }

    private record WalkResult(List<Path> mdFiles, Map<String, Path> index, int imageFileCount,
                              int folderCount, List<String> topLevelFolders, int excluded, boolean truncated) {
    }

    private record Parsed(String title, String tags, String folder, String body) {
    }

    /** 链接处理三态：url 非空 = 已重写；broken = 相对路径但源缺失；否则保持原文（外链等） */
    private record LinkOutcome(String url, boolean broken) {
        static final LinkOutcome KEEP = new LinkOutcome(null, false);

        static final LinkOutcome BROKEN = new LinkOutcome(null, true);
    }

    // ---- 对外：扫描 / 触发 / 进度 ----

    public VaultScanReport scan(String rawPath) {
        Path root = resolveRoot(rawPath);
        WalkResult w = walk(root);
        return new VaultScanReport(root.toString(), w.mdFiles().size(), w.folderCount(), w.imageFileCount(),
                w.excluded(), w.topLevelFolders(), w.truncated());
    }

    /** 触发前轻量校验路径（不存在/非目录/空 → 400），避免异步任务里才发现错误而先回 202。 */
    public void validateRoot(String rawPath) {
        resolveRoot(rawPath);
    }

    /** 触发前置检查：已在跑则拒绝（控制器据此回 409），否则重置进度并占位。 */
    public synchronized boolean tryStart() {
        if (running) {
            return false;
        }
        running = true;
        total = 0;
        done = 0;
        imported = 0;
        updated = 0;
        skipped = 0;
        failed = 0;
        imagesCopied = 0;
        imagesBroken = 0;
        gbkConverted = 0;
        truncated = 0;
        currentFile = "";
        errors = List.of();
        importedNoteIds = List.of();
        return true;
    }

    /** 异步执行（控制器经代理调用；路径校验在任务内做，失败落入 errors 由进度接口带回）。 */
    @Async("vaultExecutor")
    public void run(String rawPath) {
        List<String> errList = new CopyOnWriteArrayList<>();
        List<String> ids = new CopyOnWriteArrayList<>();
        try {
            Path root = resolveRoot(rawPath);
            WalkResult w = walk(root);
            total = w.mdFiles().size();
            log.info("仓库迁移开始：{} 篇 md，来自 {}", total, root);
            for (Path file : w.mdFiles()) {
                currentFile = root.relativize(file).toString();
                try {
                    String noteId = importOne(root, file, w.index());
                    if (noteId != null && ids.size() < MAX_TRACKED_IDS) {
                        ids.add(noteId);
                    }
                } catch (Exception e) {
                    failed++;
                    if (errList.size() < 20) {
                        errList.add(currentFile + "：" + String.valueOf(e.getMessage()));
                    }
                    log.warn("迁移失败 {}：{}", currentFile, String.valueOf(e.getMessage()));
                }
                done++;
            }
        } catch (Exception e) {
            errList.add("迁移中止：" + String.valueOf(e.getMessage()));
            log.error("仓库迁移中止", e);
        } finally {
            errors = List.copyOf(errList);
            importedNoteIds = List.copyOf(ids);
            currentFile = "";
            running = false;
            log.info("仓库迁移完成：导入 {} / 更新 {} / 跳过 {} / 失败 {} / 共 {}，图片落盘 {}（缺失 {}），GBK 转码 {}",
                    imported, updated, skipped, failed, total, imagesCopied, imagesBroken, gbkConverted);
        }
    }

    public VaultImportStatus status() {
        return new VaultImportStatus(running, total, done, imported, updated, skipped, failed,
                imagesCopied, imagesBroken, gbkConverted, truncated, currentFile, errors, importedNoteIds);
    }

    // ---- 单篇迁移 ----

    /** 返回新建/更新的笔记 id；跳过返回 null。 */
    private String importOne(Path root, Path file, Map<String, Path> index) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > props.maxMdBytes()) {
            throw new IllegalStateException("文件超过 " + (props.maxMdBytes() / 1024 / 1024) + "MB 上限");
        }
        String hash = sha256Hex(bytes);
        String sourcePath = file.toString();
        NoteImport provenance = importRepo.findBySourcePath(sourcePath).orElse(null);
        if (provenance != null && hash.equals(provenance.getContentHash())
                && noteRepo.existsById(provenance.getNoteId())) {
            skipped++;
            return null;
        }

        String text = decode(bytes);
        Parsed parsed = parseFrontMatter(text);
        String folder = parsed.folder() != null && !parsed.folder().isBlank() ? parsed.folder() : relFolder(root, file);
        String title = clamp(parsed.title() != null && !parsed.title().isBlank()
                ? parsed.title() : stripExt(file.getFileName().toString()), TITLE_MAX);
        String tags = clamp(parsed.tags() == null ? "" : parsed.tags(), TAGS_MAX);

        ImageOutcome outcome = rewriteImages(parsed.body(), file.getParent(), root, index);
        imagesCopied += outcome.copied();
        imagesBroken += outcome.broken();
        String content = outcome.content();
        if (content.length() > CONTENT_LIMIT) {
            content = content.substring(0, CONTENT_LIMIT) + "\n\n>（迁移时截断，原文 " + content.length() + " 字符）";
            truncated++;
        }

        Note note;
        boolean isUpdate = provenance != null && noteRepo.existsById(provenance.getNoteId());
        if (isUpdate) {
            note = noteService.update(provenance.getNoteId(), new NoteRequest(title, content, tags, folder));
        } else {
            note = noteService.create(new NoteRequest(title, content, tags, folder), "import");
        }
        NoteImport rec = provenance != null ? provenance : new NoteImport();
        rec.setNoteId(note.getId());
        rec.setSourcePath(sourcePath);
        rec.setContentHash(hash);
        importRepo.save(rec);
        if (isUpdate) {
            updated++;
        } else {
            imported++;
        }
        return note.getId();
    }

    private record ImageOutcome(String content, int copied, int broken) {
    }

    /** wiki 嵌入先改写，再处理标准 md 图链；缺失/不可解析的链接保留原文。 */
    private ImageOutcome rewriteImages(String body, Path baseDir, Path root, Map<String, Path> index) {
        if (body == null || body.isEmpty()) {
            return new ImageOutcome(body, 0, 0);
        }
        int[] copied = {0};
        int[] broken = {0};

        StringBuffer wikiBuf = new StringBuffer();
        Matcher wm = WIKI_EMBED.matcher(body);
        while (wm.find()) {
            String name = wm.group(1).trim();
            Path hit = lookup(index, name);
            String replacement = wm.group(0);
            if (hit != null && IMAGE_EXT.contains(extOf(name))) {
                String url = copyAsset(hit);
                if (url != null) {
                    copied[0]++;
                    replacement = "![" + name + "](" + url + ")";
                } else {
                    broken[0]++;
                }
            } else if (hit == null && IMAGE_EXT.contains(extOf(name))) {
                broken[0]++;   // 引用了图片但库里找不到
            }
            wm.appendReplacement(wikiBuf, Matcher.quoteReplacement(replacement));
        }
        wm.appendTail(wikiBuf);
        String afterWiki = wikiBuf.toString();

        StringBuffer buf = new StringBuffer();
        Matcher m = MD_IMAGE.matcher(afterWiki);
        while (m.find()) {
            LinkOutcome outcome = resolveAndCopy(m.group(2).trim(), baseDir, root);
            String replacement = m.group(0);
            if (outcome.url() != null) {
                copied[0]++;
                replacement = "![" + m.group(1) + "](" + outcome.url() + ")";
            } else if (outcome.broken()) {
                broken[0]++;
            }
            m.appendReplacement(buf, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(buf);
        return new ImageOutcome(buf.toString(), copied[0], broken[0]);
    }

    /** 相对路径解析 → vault 内文件 → 复制入资产池返回新链接；外链/绝对路径 KEEP；vault 内缺失 BROKEN。 */
    private LinkOutcome resolveAndCopy(String target, Path baseDir, Path root) {
        String cleaned = target;
        int hashIdx = cleaned.indexOf('#');
        if (hashIdx >= 0) {
            cleaned = cleaned.substring(0, hashIdx);
        }
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()
                || cleaned.startsWith("http://") || cleaned.startsWith("https://")
                || cleaned.startsWith("data:") || cleaned.startsWith("/api/")
                || cleaned.startsWith("/") || cleaned.startsWith("\\")
                || cleaned.matches("(?i)[a-z]:[\\\\/].*")) {
            return LinkOutcome.KEEP;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(cleaned, StandardCharsets.UTF_8);
        } catch (Exception e) {
            decoded = cleaned;
        }
        Path p = baseDir.resolve(decoded).normalize();
        if (!p.startsWith(root) || !Files.isRegularFile(p)) {
            return LinkOutcome.BROKEN;
        }
        String url = copyAsset(p);
        return url != null ? new LinkOutcome(url, false) : LinkOutcome.BROKEN;
    }

    /** 复制图片进资产池（UUID + 魔数扩展名；非图片魔数一律拒绝）。 */
    private String copyAsset(Path img) {
        try {
            byte[] bytes = Files.readAllBytes(img);
            if (bytes.length == 0 || bytes.length > props.maxImageBytes()) {
                return null;
            }
            String magicExt = imageMagic(bytes);
            if (magicExt == null) {
                log.warn("跳过非图片文件 {}（魔数未通过）", img);
                return null;
            }
            Files.createDirectories(ASSETS);
            String name = UUID.randomUUID() + "." + magicExt;
            Files.write(ASSETS.resolve(name), bytes);
            return "/api/assets/" + name;
        } catch (IOException e) {
            log.warn("图片落盘失败 {}：{}", img, String.valueOf(e.getMessage()));
            return null;
        }
    }

    /** wiki 嵌入名查找：先相对路径（img/demo.png），再纯文件名（demo.png），均不区分大小写。 */
    private static Path lookup(Map<String, Path> index, String name) {
        String key = name.replace('\\', '/').toLowerCase(Locale.ROOT);
        Path hit = index.get(key);
        if (hit == null) {
            int slash = key.lastIndexOf('/');
            if (slash >= 0) {
                hit = index.get(key.substring(slash + 1));
            }
        }
        return hit;
    }

    // ---- 目录遍历 ----

    /** 一次遍历收集 md 清单、图片文件名索引与统计；隐藏目录/.git 等整枝跳过。 */
    private WalkResult walk(Path root) {
        List<Path> mds = new ArrayList<>();
        Map<String, Path> index = new HashMap<>();
        int[] imageCount = {0};
        int[] folderCount = {0};
        int[] excluded = {0};
        boolean[] truncated = {false};
        TreeSet<String> topFolders = new TreeSet<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)) {
                        if (isExcluded(root, dir)) {
                            excluded[0]++;
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        folderCount[0]++;
                        topFolders.add(root.relativize(dir).getName(0).toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    String ext = extOf(name);
                    if (MD_EXT.contains(ext)) {
                        mds.add(file);
                        if (mds.size() > props.maxFiles()) {
                            truncated[0] = true;
                        }
                    } else if (IMAGE_EXT.contains(ext)) {
                        imageCount[0]++;
                        index.put(name.toLowerCase(Locale.ROOT), file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取目录：" + String.valueOf(e.getMessage()));
        }
        mds.sort(Comparator.comparing(Path::toString));
        List<Path> result = truncated[0] ? new ArrayList<>(mds.subList(0, props.maxFiles())) : mds;
        for (Path p : List.copyOf(index.values())) {
            index.put(root.relativize(p).toString().replace('\\', '/').toLowerCase(Locale.ROOT), p);
        }
        return new WalkResult(result, index, imageCount[0], folderCount[0], List.copyOf(topFolders), excluded[0], truncated[0]);
    }

    /** 排除规则：任一路径段为 . 开头（隐藏，如 .obsidian/.git）或命中 excludeDirs。 */
    private boolean isExcluded(Path root, Path dir) {
        String name = dir.getFileName().toString();
        return name.startsWith(".") || props.excludeDirs().contains(name);
    }

    private static Path resolveRoot(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路径不能为空");
        }
        Path root;
        try {
            root = Path.of(raw.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路径格式不合法：" + raw.trim());
        }
        if (!Files.isDirectory(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录不存在或不是文件夹：" + raw.trim());
        }
        return root;
    }

    // ---- 内容解析 ----

    /** UTF-8 严格解码（BOM 剥离），失败按 GBK 兜底并计数——中文老库常见 GBK。 */
    private String decode(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            gbkConverted++;
            return new String(bytes, offset, bytes.length - offset, Charset.forName("GBK"));
        }
    }

    /**
     * frontmatter：文件以 --- 开头且前 50 行内有闭合 --- 才解析（SnakeYAML），
     * 提取 title/tags/folder（tags 支持 list 与逗号/空格分隔串）；YAML 坏 = 整篇当正文。
     */
    private Parsed parseFrontMatter(String text) {
        String leading = text.stripLeading();
        String[] lines = leading.split("\r?\n");
        if (lines.length < 2 || !lines[0].trim().equals("---")) {
            return new Parsed(null, null, null, text);
        }
        int close = -1;
        int limit = Math.min(lines.length, 50);
        for (int i = 1; i < limit; i++) {
            if (lines[i].trim().equals("---")) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            return new Parsed(null, null, null, text);
        }
        String block = String.join("\n", Arrays.copyOfRange(lines, 1, close));
        String body = String.join("\n", Arrays.copyOfRange(lines, close + 1, lines.length));
        try {
            Object obj = new Yaml().load(block);
            if (!(obj instanceof Map<?, ?> map)) {
                return new Parsed(null, null, null, body);
            }
            return new Parsed(str(map.get("title")), tagsOf(map.get("tags"), map.get("tag"), map.get("keywords")),
                    str(map.get("folder")), body);
        } catch (Exception e) {
            log.debug("frontmatter 解析失败，整篇当正文：{}", String.valueOf(e.getMessage()));
            return new Parsed(null, null, null, text);
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String tagsOf(Object... candidates) {
        for (Object v : candidates) {
            if (v instanceof List<?> list) {
                String joined = list.stream().map(String::valueOf).map(String::trim)
                        .filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.joining(","));
                if (!joined.isEmpty()) {
                    return joined;
                }
            } else if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return Arrays.stream(s.split("[,，\\s]+"))
                            .filter(x -> !x.isBlank())
                            .collect(java.util.stream.Collectors.joining(","));
                }
            }
        }
        return null;
    }

    /** 相对目录 → folder（根目录文件 → 空串，normalizeFolder 视为未归类）。 */
    private static String relFolder(Path root, Path file) {
        Path parent = file.getParent();
        if (parent == null || parent.equals(root)) {
            return "";
        }
        return root.relativize(parent).toString().replace('\\', '/');
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String clamp(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    /** 按魔数识别图片类型，避免把任意相对链接文件复制进 /api/assets。 */
    private static String imageMagic(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
