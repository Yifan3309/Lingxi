package org.example.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * M1 分块器：按 Markdown 标题层级与空行段落切分。
 * 规则：标题路径作为块前缀（提升检索命中）；段落在目标长度内打包；
 * 相邻块携带上块末段（≤200 字符）作为重叠。长度均按字符计。
 * 目标长度可配：受嵌入模型上下文上限约束（如 E5 为 512 token，中文约 300 字符安全）。
 */
public final class NoteChunker {

    private static final int OVERLAP_SLACK = 100;
    private static final int MAX_CARRY_CHARS = 200;

    private record Section(String headingPath, String body) {}

    private NoteChunker() {}

    public static List<String> chunk(String title, String content, int targetChars) {
        List<String> result = new ArrayList<>();
        String safeTitle = title == null || title.isBlank() ? "未命名" : title.trim();
        for (Section section : splitSections(content == null ? "" : content)) {
            String prefix = "# " + safeTitle
                    + (section.headingPath().isEmpty() ? "" : " > " + section.headingPath());
            for (String body : pack(section.body(), targetChars)) {
                if (!body.isBlank()) {
                    result.add(prefix + "\n\n" + body);
                }
            }
        }
        return result;
    }

    /** 按标题切节，标题路径由层级栈拼出（如 "使用指南 > 快速开始"）。 */
    private static List<Section> splitSections(String content) {
        List<Section> sections = new ArrayList<>();
        TreeMap<Integer, String> levelTitles = new TreeMap<>();
        StringBuilder body = new StringBuilder();
        String path = "";
        for (String line : content.split("\n", -1)) {
            if (line.matches("#+ .+")) {
                if (!body.toString().isBlank()) {
                    sections.add(new Section(path, body.toString()));
                }
                body = new StringBuilder();
                int level = line.indexOf(' ');
                levelTitles.put(level, line.substring(level + 1).trim());
                levelTitles.tailMap(level + 1).clear();
                path = String.join(" > ", levelTitles.values());
            } else {
                body.append(line).append('\n');
            }
        }
        if (!body.toString().isBlank()) {
            sections.add(new Section(path, body.toString()));
        }
        return sections;
    }

    /** 节内段落打包：超出目标则收尾开新块，新块携带上块末段作重叠。 */
    private static List<String> pack(String body, int targetChars) {
        int hardSplitStep = Math.max(1, targetChars - OVERLAP_SLACK);
        List<String> paragraphs = new ArrayList<>();
        for (String p : body.split("\n\\s*\n")) {
            if (!p.strip().isEmpty()) {
                paragraphs.add(p.strip());
            }
        }
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : paragraphs) {
            if (p.length() > targetChars) {
                if (!buf.isEmpty()) {
                    out.add(finish(buf));
                    buf = new StringBuilder();
                }
                out.addAll(hardSplit(p, hardSplitStep, targetChars));
                continue;
            }
            if (!buf.isEmpty() && buf.length() + p.length() + 2 > targetChars) {
                String carry = lastParagraph(finish(buf));
                out.add(finish(buf));
                buf = new StringBuilder();
                if (!carry.isEmpty()) {
                    buf.append(carry).append("\n\n");
                }
            }
            buf.append(p).append("\n\n");
        }
        if (!buf.toString().isBlank()) {
            out.add(finish(buf));
        }
        return out;
    }

    /** 超长段落按固定步长硬切，块间留重叠余量。 */
    private static List<String> hardSplit(String paragraph, int step, int targetChars) {
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < paragraph.length(); start += step) {
            int end = Math.min(paragraph.length(), start + targetChars);
            pieces.add(paragraph.substring(start, end));
            if (end == paragraph.length()) {
                break;
            }
        }
        return pieces;
    }

    private static String lastParagraph(String chunkBody) {
        String[] parts = chunkBody.split("\n\\s*\n");
        if (parts.length == 0) {
            return "";
        }
        String last = parts[parts.length - 1].strip();
        return last.length() <= MAX_CARRY_CHARS ? last : "";
    }

    private static String finish(StringBuilder buf) {
        return buf.toString().strip();
    }
}
