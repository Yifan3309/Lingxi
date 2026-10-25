package org.example.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.example.config.ImportProperties;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 多模态导入（分级解析）：
 * 一级 Apache Tika 统一抽取结构化文本（md/txt/docx/pptx/原生 pdf/html/代码等）；
 * 二级视觉 LLM 处理真正非结构化的（纯图片、扫描版 PDF——Tika 文本量过低时判定）。
 * 抽取文本建笔记后走既有 分块→嵌入→重排 与 富化 管线。
 */
@Service
public class DocumentImportService {

    private static final Logger log = LoggerFactory.getLogger(DocumentImportService.class);

    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp");
    private static final Set<String> TEXT_EXT = Set.of(
            "md", "markdown", "txt", "docx", "doc", "pptx", "ppt", "html", "htm",
            "json", "yaml", "yml", "xml", "csv",
            "java", "py", "js", "ts", "sql", "sh", "go", "rs", "c", "cpp", "h", "cs");
    private static final Path ASSETS = Path.of("data/assets").toAbsolutePath().normalize();

    private static final String VISION_SYSTEM_PROMPT = """
            你是图片文字转录助手。请忠实转录图片中的全部文字内容：
            - 保持原有段落与结构，不要总结、改写或补充
            - 手写体尽力识别，无法辨认的字用 ▢ 占位
            - 图中没有文字时，用一两句话描述图片内容
            只输出转录/描述结果。""";

    private final ChatModel visionChatModel;
    private final NoteService noteService;
    private final NoteAssetRepository assetRepo;
    private final ImportProperties props;
    private final ApacheTikaDocumentParser tika = new ApacheTikaDocumentParser();

    public DocumentImportService(@Qualifier("visionChatModel") ChatModel visionChatModel,
                                 NoteService noteService,
                                 NoteAssetRepository assetRepo,
                                 ImportProperties props) {
        this.visionChatModel = visionChatModel;
        this.noteService = noteService;
        this.assetRepo = assetRepo;
        this.props = props;
    }

    public Note importFile(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extOf(name);
        boolean isImage = IMAGE_EXT.contains(ext);
        boolean isPdf = ext.equals("pdf");
        if (!isImage && !isPdf && !TEXT_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的类型 ." + ext + "（支持: 图片/pdf/" + String.join("/", List.of("md", "txt", "docx", "pptx", "html", "代码")) + "）");
        }
        if (file.isEmpty() || file.getSize() > props.maxBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空或超过 " + (props.maxBytes() / 1024 / 1024) + "MB");
        }
        byte[] bytes = file.getBytes();
        String stored = storeOriginal(bytes, ext);

        NoteAsset asset = new NoteAsset();
        asset.setOriginalName(name);
        asset.setStoragePath(stored);
        asset.setMime(file.getContentType());
        asset.setStatus("pending");

        String title = titleOf(name);
        String content;
        try {
            if (isImage) {
                asset.setKind("image");
                content = extractByVision(bytes, "image/" + ("jpg".equals(ext) ? "jpeg" : ext));
            } else if (isPdf) {
                String text = extractByTika(bytes);
                if (text.trim().length() >= props.minPdfChars()) {
                    asset.setKind("file");
                    content = text;
                } else {
                    asset.setKind("pdf_scan");
                    content = extractScannedPdf(bytes);
                }
            } else {
                asset.setKind("file");
                content = extractByTika(bytes);
            }
            asset.setExtractedText(content.length() > 60000 ? content.substring(0, 60000) : content);
            asset.setStatus("done");
        } catch (Exception e) {
            log.warn("导入 {} 解析失败: {}", name, String.valueOf(e.getMessage()));
            asset.setStatus("failed");
            asset.setError(truncate(String.valueOf(e.getMessage()), 1000));
            content = "（解析失败：" + truncate(String.valueOf(e.getMessage()), 200) + "）";
            if (isImage || "pdf_scan".equals(asset.getKind())) {
                content += "\n\n![](/api/assets/" + stored + ")";
            }
        }

        String source = switch (asset.getKind() == null ? "file" : asset.getKind()) {
            case "image", "pdf_scan" -> "vision";
            default -> "import";
        };
        Note note = noteService.create(new NoteRequest(title, content, null, null), source);

        asset.setNoteId(note.getId());
        assetRepo.save(asset);
        log.info("导入完成 {} → 笔记 {}（kind={}, status={}）", name, note.getId(), asset.getKind(), asset.getStatus());
        return note;
    }

    private String extractByTika(byte[] bytes) {
        Document doc = tika.parse(new ByteArrayInputStream(bytes));
        return doc.text() == null ? "" : doc.text();
    }

    /** 纯图片：视觉 LLM 转录。 */
    private String extractByVision(byte[] imageBytes, String mime) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        ChatResponse response = visionChatModel.chat(
                SystemMessage.from(VISION_SYSTEM_PROMPT),
                UserMessage.from(TextContent.from("转录这张图片"), ImageContent.from(b64, mime)));
        return response.aiMessage().text();
    }

    /** 扫描版 PDF：逐页渲染成图走视觉 LLM，页间以分页线拼接。 */
    private String extractScannedPdf(byte[] pdfBytes) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            int pages = Math.min(pdf.getNumberOfPages(), props.maxPdfPages());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages; i++) {
                var image = renderer.renderImageWithDPI(i, 120);
                var out = new java.io.ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                sb.append(extractByVision(out.toByteArray(), "image/png"));
                if (i < pages - 1) {
                    sb.append("\n\n---\n\n");
                }
            }
            if (pdf.getNumberOfPages() > pages) {
                sb.append("\n\n（仅处理前 ").append(pages).append(" 页，共 ")
                        .append(pdf.getNumberOfPages()).append(" 页）");
            }
            return sb.toString();
        }
    }

    private String storeOriginal(byte[] bytes, String ext) throws IOException {
        Files.createDirectories(ASSETS);
        String filename = UUID.randomUUID() + "." + ext;
        Files.write(ASSETS.resolve(filename), bytes);
        return filename;
    }

    private static String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private static String titleOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
