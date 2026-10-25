package org.example.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.example.config.ClipProperties;
import org.example.domain.Note;
import org.example.domain.NoteAsset;
import org.example.repository.NoteAssetRepository;
import org.example.service.NoteService;
import org.example.web.dto.NoteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 网页剪藏：URL → 抓取 → Tika 抽正文 + <img> 解析 → 图片下载入本地图片池 →
 * LLM 清洗重写（去导航/广告/页脚噪音，图片占位符插回对应位置）→ 笔记(source=clip) → 既有管线。
 * 降级链：正文过厚跳过重写、LLM 失败回退 Tika 原文、图片不可达跳过——任何一步失败不阻断建笔记
 * （抓取本身失败除外：超时/非 2xx/非 HTML → 4xx/502 直接报错）。Tika 原文存 note_asset.extracted_text 留底。
 * SSRF 轻防护：仅放行 http/https（本地单人应用，见决策 16）。
 */
@Service
public class WebClipService {

    private static final Logger log = LoggerFactory.getLogger(WebClipService.class);
    private static final Pattern TITLE =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IMG_TAG = Pattern.compile("<img[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC_ATTR = Pattern.compile("src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT_ATTR = Pattern.compile("alt\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    /** LLM 输出中的图片占位符：[[IMG:1]] / [IMG 1] / [[img：1]] 等变体都认 */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\[\\s*\\[?\\s*IMG\\s*[:：]?\\s*(\\d+)\\s*\\]?\\s*\\]", Pattern.CASE_INSENSITIVE);
    /** 明显非正文的图片（小图标/头像/追踪像素），按 URL 子串粗过滤 */
    private static final List<String> JUNK_IMG = List.of(
            "icon", "logo", "avatar", "sprite", "badge", "pixel", "tracking", "spacer",
            "blank", "gravatar", "emoji", "1x1", ".svg");
    private static final Path ASSETS = Path.of("data/assets").toAbsolutePath().normalize();
    private static final int MIN_DIM = 100;

    private static final String REWRITE_SYSTEM_PROMPT = """
            你是网页剪藏整理助手。请把网页抽取文本清理成一篇干净完整的 Markdown 笔记：
            - 忠实保留正文全部实质内容与原有结构（标题层级、段落、列表），禁止总结、缩写、改写措辞或补充内容
            - 删除与正文无关的噪音：导航菜单、广告、页脚、版权声明、分享文案、推荐阅读、评论区、cookie 提示、无意义符号等
            - 图片清单中的图片以 [[IMG:n]] 占位符原样插在对应段落位置（n 为清单编号），不要编造图片地址；找不到合适位置就集中放在文末
            - 只输出 Markdown 正文，不要任何前后解释""";

    private final ChatModel chatModel;
    private final NoteService noteService;
    private final NoteAssetRepository assetRepo;
    private final ClipProperties props;
    private final ApacheTikaDocumentParser tika = new ApacheTikaDocumentParser();
    private final HttpClient client;

    public WebClipService(@Qualifier("chatModel") ChatModel chatModel,
                          NoteService noteService,
                          NoteAssetRepository assetRepo,
                          ClipProperties props) {
        this.chatModel = chatModel;
        this.noteService = noteService;
        this.assetRepo = assetRepo;
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
    }

    public Note clip(String url) {
        URI uri = parse(url);
        FetchResult page = fetch(uri);

        NoteAsset asset = new NoteAsset();
        asset.setKind("clip");
        asset.setStoragePath("-"); // 无磁盘文件；列 NOT NULL 用占位符（H2 加列教训：不动已有列约束）
        asset.setOriginalName(truncate(url, 250));
        asset.setMime(page.contentType());
        asset.setStatus("pending");

        String title = extractTitle(page.body(), uri, page.charset());

        String rawText;
        try {
            Document doc = tika.parse(new ByteArrayInputStream(page.body()));
            rawText = doc.text() == null ? "" : doc.text().trim();
            asset.setExtractedText(truncate(rawText, 60000));
            asset.setStatus("done");
        } catch (Exception e) {
            // Tika 对空/脚本渲染文档抛异常（如 "The document is blank"）：标题+来源兜底，图片与重写无意义
            log.warn("剪藏 {} 解析失败: {}", url, String.valueOf(e.getMessage()));
            asset.setStatus("failed");
            asset.setError(truncate(String.valueOf(e.getMessage()), 1000));
            return finish(title, title + "\n\n（解析失败：" + truncate(String.valueOf(e.getMessage()), 200)
                    + "；来源：" + url + "）", asset);
        }
        if (rawText.length() < 20) {
            return finish(title, title + "\n\n（页面正文由脚本渲染或为空，未能抽取；来源：" + url + "）", asset);
        }

        // 图片解析 + 并行下载入本地图片池（失败跳过，不阻断）
        List<ImgRef> imgRefs = downloadImages(page.body(), uri, page.charset(), url);

        // LLM 清洗重写；正文过长或 LLM 失败 → Tika 原文 + 图片文末追加
        String content;
        if (rawText.length() > props.rewriteMaxChars()) {
            log.info("剪藏 {} 正文 {} 字超过重写上限 {}，保留 Tika 原文", url, rawText.length(), props.rewriteMaxChars());
            content = appendImages(rawText, imgRefs, null);
        } else {
            try {
                content = rewrite(title, rawText, imgRefs);
            } catch (Exception e) {
                log.warn("剪藏 {} LLM 重写失败，回退 Tika 原文: {}", url, String.valueOf(e.getMessage()));
                content = appendImages(rawText, imgRefs, null);
            }
        }

        return finish(title, content, asset);
    }

    private Note finish(String title, String content, NoteAsset asset) {
        Note note = noteService.create(new NoteRequest(title, content, null, null), "clip");
        asset.setNoteId(note.getId());
        assetRepo.save(asset);
        log.info("剪藏完成 → 笔记 {}（{} 字）", note.getId(), content.length());
        return note;
    }

    /* ---------- LLM 清洗重写 ---------- */

    private String rewrite(String title, String rawText, List<ImgRef> images) {
        StringBuilder list = new StringBuilder(images.isEmpty() ? "（无图片）" : "");
        for (ImgRef img : images) {
            list.append("\n[").append(img.num()).append("] ")
                    .append(img.alt().isBlank() ? "（无说明）" : img.alt())
                    .append("｜出现在「").append(img.context()).append("」附近");
        }
        ChatResponse response = chatModel.chat(
                SystemMessage.from(REWRITE_SYSTEM_PROMPT),
                UserMessage.from("网页标题：" + title + "\n\n抽取文本：\n" + rawText
                        + "\n\n图片清单（占位符中的 n 对应编号）：\n" + list));
        String md = response.aiMessage().text() == null ? "" : response.aiMessage().text().trim();
        if (md.isEmpty()) {
            throw new IllegalStateException("LLM 返回空内容");
        }
        // 占位符 → 本地图片地址；LLM 漏插的图片兜底追加文末（不静默丢）
        Set<Integer> used = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(md);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            ImgRef ref = images.stream().filter(i -> i.num() == n).findFirst().orElse(null);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    ref != null ? "![" + ref.alt() + "](" + ref.url() + ")" : ""));
            if (ref != null) {
                used.add(n);
            }
        }
        m.appendTail(sb);
        return appendImages(sb.toString(), images, used);
    }

    /** 未被引用的图片追加到文末（used=null 表示全部追加）。 */
    private static String appendImages(String content, List<ImgRef> images, Set<Integer> used) {
        StringBuilder sb = new StringBuilder(content);
        for (ImgRef img : images) {
            if (used != null && used.contains(img.num())) {
                continue;
            }
            sb.append("\n\n![").append(img.alt()).append("](").append(img.url()).append(")");
        }
        return sb.toString();
    }

    /* ---------- 图片解析与下载 ---------- */

    private record ImgRef(int num, String alt, String url, String context) {}

    private record ImgCandidate(int order, String src, String alt, String context, byte[] data) {
        ImgCandidate withOrder(int o) { return new ImgCandidate(o, src, alt, context, data); }
    }

    /** 解析候选（过滤/去重/上限）→ 并行下载 → 魔数与尺寸校验 → 落 data/assets/，按出现顺序编号。 */
    private List<ImgRef> downloadImages(byte[] html, URI base, String charsetName, String pageUrl) {
        String htmlStr = new String(html, charsetOf(charsetName));
        List<ImgCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher tag = IMG_TAG.matcher(htmlStr);
        while (tag.find() && candidates.size() < props.maxImages()) {
            String tagStr = tag.group();
            String src = attr(tagStr, SRC_ATTR);
            if (src == null || src.isBlank()) {
                continue;
            }
            String alt = attr(tagStr, ALT_ATTR);
            String context = contextBefore(htmlStr, tag.start());
            ImgCandidate c = candidateOf(src.trim(), alt, context, base, seen);
            if (c != null) {
                candidates.add(c.withOrder(candidates.size()));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Integer, ImgCandidate> ok = new ConcurrentHashMap<>();
        candidates.parallelStream().forEach(c -> {
            byte[] bytes = c.data() != null ? c.data() : httpGetImage(c.src(), pageUrl);
            String stored = storeIfValid(bytes);
            if (stored != null) {
                ok.put(c.order(), new ImgCandidate(c.order(), stored, c.alt(), c.context(), c.data()));
            }
        });
        List<ImgRef> refs = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ImgCandidate c = ok.get(i);
            if (c != null) {
                refs.add(new ImgRef(refs.size() + 1, c.alt() == null ? "" : c.alt(), c.src(), c.context()));
            }
        }
        log.info("剪藏图片：候选 {} 张，落地 {} 张", candidates.size(), refs.size());
        return refs;
    }

    private ImgCandidate candidateOf(String src, String alt, String context, URI base, Set<String> seen) {
        String lower = src.toLowerCase();
        if (lower.startsWith("data:image/")) {
            if (lower.contains("image/svg")) {
                return null; // svg 有脚本风险且尺寸过滤失效
            }
            int comma = src.indexOf(',');
            if (comma < 0) {
                return null;
            }
            try {
                byte[] bytes = Base64.getDecoder().decode(src.substring(comma + 1));
                if (!seen.add("data:" + bytes.length + ":" + (alt == null ? "" : alt))) {
                    return null;
                }
                return new ImgCandidate(-1, "data:", alt == null ? "" : alt, context, bytes);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (lower.contains(".svg")) {
            return null;
        }
        for (String junk : JUNK_IMG) {
            if (lower.contains(junk)) {
                return null;
            }
        }
        try {
            URI abs = base.resolve(src);
            if (abs.getScheme() == null || !(abs.getScheme().equals("http") || abs.getScheme().equals("https"))) {
                return null;
            }
            return seen.add(abs.toString())
                    ? new ImgCandidate(-1, abs.toString(), alt == null ? "" : alt, context, null)
                    : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private byte[] httpGetImage(String url, String referer) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.connectTimeoutSeconds() + 5))
                    .header("User-Agent", props.userAgent())
                    .header("Referer", referer)
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return null;
            }
            byte[] bytes = resp.body();
            return bytes != null && bytes.length <= props.maxImageBytes() ? bytes : null;
        } catch (Exception e) {
            return null; // 防盗链/超时/不可达：跳过（"能获取到的"）
        }
    }

    /** 魔数校验 + 最小尺寸过滤，通过则落 data/assets/ 返回 /api/assets/ 引用。 */
    private String storeIfValid(byte[] bytes) {
        if (bytes == null || bytes.length < 100) {
            return null;
        }
        String ext = imageExt(bytes);
        if (ext == null) {
            return null;
        }
        try {
            if (!ext.equals("webp")) { // webp 无 JDK 解码器，跳过尺寸过滤改按体积把关
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img == null || img.getWidth() < MIN_DIM || img.getHeight() < MIN_DIM) {
                    return null;
                }
            } else if (bytes.length < 5 * 1024) {
                return null;
            }
            Files.createDirectories(ASSETS);
            String name = UUID.randomUUID() + "." + ext;
            Files.write(ASSETS.resolve(name), bytes);
            return "/api/assets/" + name;
        } catch (IOException e) {
            return null;
        }
    }

    /** PNG/JPEG/GIF/WEBP 魔数识别，返回扩展名；其余（含 svg）拒绝。 */
    private static String imageExt(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "gif";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String attr(String tag, Pattern attrPattern) {
        Matcher m = attrPattern.matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    /** img 标签前约 60 字符的纯文字（去标签），给 LLM 的位置线索。 */
    private static String contextBefore(String html, int pos) {
        int from = Math.max(0, pos - 300);
        String raw = html.substring(from, pos).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return raw.length() > 60 ? raw.substring(raw.length() - 60) : raw;
    }

    /* ---------- 抓取 ---------- */

    private URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的 URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 http/https 链接");
        }
        return uri;
    }

    private record FetchResult(byte[] body, String contentType, String charset) {}

    private FetchResult fetch(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                .header("User-Agent", props.userAgent())
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "抓取失败（网络/超时）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "抓取被中断");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "目标返回 " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (!contentType.isEmpty()
                && !contentType.startsWith("text/html")
                && !contentType.startsWith("application/xhtml")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的类型: " + contentType + "（仅 text/html）");
        }
        String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase();
        try (InputStream raw = response.body();
             InputStream in = encoding.contains("gzip") ? new GZIPInputStream(raw) : raw) {
            byte[] bytes = in.readNBytes((int) props.maxBytes() + 1);
            if (bytes.length > props.maxBytes()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "页面超过 " + (props.maxBytes() / 1024 / 1024) + "MB 上限");
            }
            String charset = contentType.contains("charset=")
                    ? contentType.substring(contentType.indexOf("charset=") + 8).split("[;\\s]")[0].replace("\"", "").trim()
                    : "utf-8";
            return new FetchResult(bytes, contentType, charset);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "读取页面失败：" + e.getMessage());
        }
    }

    /* ---------- 标题 ---------- */

    private static String extractTitle(byte[] html, URI uri, String charsetName) {
        // 用单字节编码读入仅为命中 <title> 标签（charset 无关）；命中后按页面字符集（默认 UTF-8）转回真实文本
        Matcher m = TITLE.matcher(new String(html, StandardCharsets.ISO_8859_1));
        if (m.find()) {
            String decoded = new String(m.group(1).getBytes(StandardCharsets.ISO_8859_1), charsetOf(charsetName));
            String t = decoded.replaceAll("\\s+", " ")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'").trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return uri.getHost() + uri.getPath();
    }

    private static java.nio.charset.Charset charsetOf(String name) {
        try {
            return name == null || name.isBlank() ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
