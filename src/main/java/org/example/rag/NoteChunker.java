package org.example.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M1 分块器：按 Markdown 标题层级与空行段落切分。
 * 规则：标题路径作为块前缀（提升检索命中）；段落在目标长度内打包；
 * 相邻块携带上块末段（≤200 字符）作为重叠。长度均按字符计。
 * 目标长度可配：受嵌入模型上下文上限约束（如 E5 为 512 token，中文约 300 字符安全）。
 *
 * 除块文本外同时给出每块在原文中的区间 [charStart, charEnd) 与所属标题路径——
 * RAG 来源点击后前端据此定位原文段落（GET /api/notes/{id}/chunks 读时重算，
 * 区间始终与当前正文同步，不落库、不进向量元数据）。
 */
public final class NoteChunker {

    private static final int OVERLAP_SLACK = 100;
    private static final int MAX_CARRY_CHARS = 200;
    private static final Pattern PARAGRAPH_SEP = Pattern.compile("\n\\s*\n");

    /**
     * 一个分块：text = "# 标题[ > 路径]\n\n正文"；
     * [charStart, charEnd) 为正文（含 carry 重叠段）在原 content 中的字符区间。
     */
    public record Chunk(String text, int charStart, int charEnd, String headingPath) {}

    /** 段落（已 strip）及其在原 content 中的区间；pack 的内部流转单位。 */
    private record Span(String text, int start, int end) {}

    /** 标题节：正文串 + 正文首行在原 content 中的偏移（作段落相对偏移的基准）。 */
    private record Section(String headingPath, String body, int bodyStart) {}

    private NoteChunker() {}

    public static List<Chunk> chunk(String title, String content, int targetChars) {
        List<Chunk> result = new ArrayList<>();
        String safeTitle = title == null || title.isBlank() ? "未命名" : title.trim();
        for (Section section : splitSections(content == null ? "" : content)) {
            String prefix = "# " + safeTitle
                    + (section.headingPath().isEmpty() ? "" : " > " + section.headingPath());
            for (Span body : pack(section.body(), section.bodyStart(), targetChars)) {
                if (!body.text().isBlank()) {
                    result.add(new Chunk(prefix + "\n\n" + body.text(), body.start(), body.end(), section.headingPath()));
                }
            }
        }
        return result;
    }

    /**
     * 按标题切节，标题路径由层级栈拼出（如 "使用指南 > 快速开始"）。
     * bodyStart = 节内第一行的起始偏移：标题行后从下一行算起，节内首行落在 else 分支再校准一次。
     */
    private static List<Section> splitSections(String content) {
        List<Section> sections = new ArrayList<>();
        TreeMap<Integer, String> levelTitles = new TreeMap<>();
        StringBuilder body = new StringBuilder();
        int bodyStart = 0;
        int offset = 0;
        String path = "";
        for (String line : content.split("\n", -1)) {
            if (line.matches("#+ .+")) {
                if (!body.toString().isBlank()) {
                    sections.add(new Section(path, body.toString(), bodyStart));
                }
                body = new StringBuilder();
                bodyStart = offset + line.length() + 1;
                int level = line.indexOf(' ');
                levelTitles.put(level, line.substring(level + 1).trim());
                levelTitles.tailMap(level + 1).clear();
                path = String.join(" > ", levelTitles.values());
            } else {
                if (body.length() == 0) {
                    bodyStart = offset;
                }
                body.append(line).append('\n');
            }
            offset += line.length() + 1;
        }
        if (!body.toString().isBlank()) {
            sections.add(new Section(path, body.toString(), bodyStart));
        }
        return sections;
    }

    /**
     * 节内段落打包：超出目标则收尾开新块，新块携带上块末段作重叠。
     * 段落偏移 = bodyStart + 在 body 内的相对区间（strip 后的净正文边界），
     * 长度判定与旧实现完全一致（builder 含每段后的分隔符）。
     */
    private static List<Span> pack(String body, int bodyStart, int targetChars) {
        int hardSplitStep = Math.max(1, targetChars - OVERLAP_SLACK);
        List<Span> paragraphs = paragraphSpans(body, bodyStart);
        List<Span> out = new ArrayList<>();
        List<Span> buf = new ArrayList<>();
        int bufLen = 0;
        for (Span p : paragraphs) {
            if (p.text().length() > targetChars) {
                if (!buf.isEmpty()) {
                    out.add(finish(buf));
                    buf = new ArrayList<>();
                    bufLen = 0;
                }
                out.addAll(hardSplit(p, hardSplitStep, targetChars));
                continue;
            }
            if (!buf.isEmpty() && bufLen + p.text().length() + 2 > targetChars) {
                Span carry = buf.get(buf.size() - 1);
                out.add(finish(buf));
                buf = new ArrayList<>();
                if (carry.text().length() <= MAX_CARRY_CHARS) {
                    buf.add(carry);
                    bufLen = carry.text().length() + 2;
                } else {
                    bufLen = 0;
                }
            }
            buf.add(p);
            bufLen += p.text().length() + 2;
        }
        if (!buf.isEmpty()) {
            out.add(finish(buf));
        }
        return out;
    }

    /** body 里的非空段落 → 净正文区间（strip 边界修正到原文坐标）。 */
    private static List<Span> paragraphSpans(String body, int bodyStart) {
        List<Span> paragraphs = new ArrayList<>();
        Matcher sep = PARAGRAPH_SEP.matcher(body);
        int regionStart = 0;
        while (sep.find()) {
            addParagraph(paragraphs, body, bodyStart, regionStart, sep.start());
            regionStart = sep.end();
        }
        addParagraph(paragraphs, body, bodyStart, regionStart, body.length());
        return paragraphs;
    }

    /** 区间 [from, to) 内的文本 strip 后非空才收，偏移修正到原 content 坐标。 */
    private static void addParagraph(List<Span> out, String body, int bodyStart, int from, int to) {
        String raw = body.substring(from, to);
        int lead = raw.length() - raw.stripLeading().length();
        int trail = raw.length() - raw.stripTrailing().length();
        String text = raw.substring(lead, raw.length() - trail);
        if (!text.isEmpty()) {
            out.add(new Span(text, bodyStart + from + lead, bodyStart + to - trail));
        }
    }

    /** 超长段落按固定步长硬切，块间留重叠余量；区间直接取子串坐标。 */
    private static List<Span> hardSplit(Span paragraph, int step, int targetChars) {
        List<Span> pieces = new ArrayList<>();
        for (int start = 0; start < paragraph.text().length(); start += step) {
            int end = Math.min(paragraph.text().length(), start + targetChars);
            pieces.add(new Span(paragraph.text().substring(start, end), paragraph.start() + start, paragraph.start() + end));
            if (end == paragraph.text().length()) {
                break;
            }
        }
        return pieces;
    }

    /** 打包成块：文本 = 各段以空行连接（首尾本就干净，无需再 strip），区间 = 首段起点到末段终点。 */
    private static Span finish(List<Span> buf) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < buf.size(); i++) {
            if (i > 0) {
                text.append("\n\n");
            }
            text.append(buf.get(i).text());
        }
        return new Span(text.toString(), buf.get(0).start(), buf.get(buf.size() - 1).end());
    }
}
