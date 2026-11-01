package org.example.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 概念抽取的纯逻辑部分：切块、渲染骨架、解析模型输出、锚点校验、结构归属说明。
 *
 * <p>这一层不含任何 IO 与模型调用，所有判断都是确定的——它对应 doc/08 §5.5 里
 * "结构负责在哪、LLM 负责是什么"的分工：模型只产出名字，剩下的都靠这里。
 *
 * <p>四条规则来自实测（都不是凭感觉定的）：
 * <ul>
 *   <li><b>锚点校验</b>：名字必须能在原文的标题或短列表项里找到。模型会偷翻译
 *       （`贝尔曼最优方程` → `Bellman optimality equation`），回不到原文就不该进图谱。</li>
 *   <li><b>块只送前 {@value #BLOCK_CHARS} 字</b>：长块整段送会把上下文撑爆，
 *       而且概念名通常出现在块首。</li>
 *   <li><b>说明按标题归属</b>：标题下第一个合格段落就是这个概念的说明。
 *       实测这条比向量检索可靠得多（向量会把"提到它"的段落误判成"解释它"）——
 *       宁可没有说明，也不要错说明。</li>
 *   <li><b>不合格段落直接留空</b>：代码块、表格、公式、待办勾选、图片链接都不是解释。</li>
 * </ul>
 */
public final class ConceptExtractor {

    /** 每块送进 prompt 的字数上限 */
    public static final int BLOCK_CHARS = 160;
    /** 模型一次最多给几个名字。2B 守不住"最多 N 个"，这里做硬截断兜底 */
    public static final int MAX_NAMES = 15;
    /** 名字长度上限，超过基本是半句话而不是概念 */
    private static final int MAX_NAME_LEN = 20;

    private ConceptExtractor() {
    }

    /** 结构块：type 为 H1..H6 / P / L / CODE */
    public record Block(String type, String text) {
        public boolean isHeading() {
            return type.length() == 2 && type.charAt(0) == 'H';
        }

        public int level() {
            return isHeading() ? type.charAt(1) - '0' : 0;
        }
    }

    /**
     * 把 markdown 切成结构块。代码块整体吞掉（变量名噪声远大于信息），
     * 连续列表项合成一块（它们语义上是一段）。
     */
    public static List<Block> blocks(String content) {
        List<Block> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        List<String> listBuf = new ArrayList<>();
        List<String> paraBuf = new ArrayList<>();
        boolean inFence = false;

        for (String raw : content.split("\n", -1)) {
            String line = raw.stripTrailing();
            String trimmed = line.strip();

            if (trimmed.startsWith("```")) {
                flushList(out, listBuf);
                flushPara(out, paraBuf);
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            if (trimmed.isEmpty()) {
                flushList(out, listBuf);
                flushPara(out, paraBuf);
                continue;
            }
            if (isHeading(trimmed)) {
                flushList(out, listBuf);
                flushPara(out, paraBuf);
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                String text = trimmed.substring(level).strip();
                if (!text.isEmpty()) {
                    out.add(new Block("H" + Math.min(level, 6), text));
                }
                continue;
            }
            if (isListItem(trimmed)) {
                flushPara(out, paraBuf);
                listBuf.add(stripListMarker(trimmed));
                continue;
            }
            flushList(out, listBuf);
            paraBuf.add(trimmed);
        }
        flushList(out, listBuf);
        flushPara(out, paraBuf);
        return out;
    }

    /** 骨架：每块一行 `#序号 [类型] 前 N 字`。模型靠序号指认，不靠位置猜测 */
    public static String skeleton(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            String txt = b.text();
            if (txt.length() > BLOCK_CHARS) {
                txt = txt.substring(0, BLOCK_CHARS);
            }
            if (i > 0) {
                sb.append('\n');
            }
            sb.append('#').append(i).append(" [").append(b.type()).append("] ").append(txt);
        }
        return sb.toString();
    }

    /** 锚点集合：标题全文 + 短列表项（不含句末标点的才算条目，整句话不是锚点） */
    public static List<String> anchors(List<Block> blocks) {
        List<String> out = new ArrayList<>();
        for (Block b : blocks) {
            if (b.isHeading()) {
                out.add(b.text());
            } else if ("L".equals(b.type())) {
                for (String part : b.text().split(" / ")) {
                    String p = part.strip();
                    if (p.length() > 1 && p.length() <= MAX_NAME_LEN && !hasSentenceEnd(p)) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    /** 名字能否回到原文：完全相等，或互为子串（模型常把 `检索后重排` 简写成 `重排`） */
    public static boolean anchored(String name, List<String> anchors) {
        String n = normKey(name);
        if (n.isEmpty()) {
            return false;
        }
        for (String a : anchors) {
            String an = normKey(a);
            if (!an.isEmpty() && (an.contains(n) || n.contains(an))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 结构归属说明：找到名字所在的标题，取它下面第一个合格段落。
     * 找不到标题就没有说明——清单式条目（`- 切片`）本来就没写解释，硬编是自欺欺人。
     */
    public static Optional<String> attribute(String name, List<Block> blocks) {
        String n = normKey(name);
        for (int i = 0; i < blocks.size(); i++) {
            Block h = blocks.get(i);
            if (!h.isHeading() || !normKey(h.text()).contains(n)) {
                continue;
            }
            for (int j = i + 1; j < blocks.size(); j++) {
                Block b = blocks.get(j);
                if (b.isHeading() && b.level() <= h.level()) {
                    break;
                }
                if ("P".equals(b.type()) || "L".equals(b.type())) {
                    String body = "L".equals(b.type()) ? pickListItem(b.text(), name) : b.text();
                    if (usable(body, name, h.text())) {
                        return Optional.of(body.length() > 400 ? body.substring(0, 400) : body);
                    }
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * 解析模型输出。容忍 `{"entities":[...]}` 与裸数组两种形态。
     *
     * <p>JSON 不合法（截断、混入说明文字）必须抛出而不是吞成空列表：
     * 调用方靠「空列表 = 确实没有概念」来清空旧提及并写指纹，吞掉的话
     * 一次跑偏的响应就会把这篇笔记已有概念永久抹掉且永不重试。
     * 与之严格区分：合法的空数组/空内容 = 真的没有概念，正常返回空。
     */
    public static List<String> parseNames(String raw, ObjectMapper mapper) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        JsonNode node;
        try {
            String s = stripFences(raw);
            node = mapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("概念抽取返回不是合法 JSON", e);
        }
        JsonNode arr = node.isArray() ? node : node.get("entities");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        for (JsonNode item : arr) {
            // 小模型偶尔会把数组元素写成对象（{"concept":"元数据过滤"}），键名不固定。
            // 只认 "name" 会让整篇静默变成 0 个概念——这类失败最难发现，所以多认两个键。
            String name = item.isTextual() ? item.asText()
                    : item.hasNonNull("name") ? item.get("name").asText()
                    : item.hasNonNull("concept") ? item.get("concept").asText()
                    : item.hasNonNull("entity") ? item.get("entity").asText()
                    : null;
            if (name == null) {
                continue;
            }
            name = cleanName(name);
            if (name.length() > 1 && name.length() <= MAX_NAME_LEN) {
                out.add(name);
            }
            if (out.size() >= MAX_NAMES) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 规范名：把模型输出的名字收敛回原文写法。
     *
     * <p>模型经常把说明一起吐出来——`缓存（第一，它能改善中央处理器与外围设备之间速度不匹配的矛盾…）`、
     * `FCB（按名存取 组成目录文件 唯一标识 存储属性）`。这种名字通过锚点校验
     * （它确实包含锚点），但存进概念表就成了带括号说明的怪东西，
     * 展示、归一化键、主题分组全被它拖累。
     *
     * <p>做法：截到第一个括号处，前半段仍能通过锚点校验就取前半段。
     * 截不出干净前缀就原样保留——宁可名字长一点，也不要丢掉真概念。
     */
    public static String canonicalName(String modelName, List<String> anchors) {
        String raw = cleanName(modelName);
        for (String open : new String[]{"（", "("}) {
            int p = raw.indexOf(open);
            if (p > 0) {
                String head = raw.substring(0, p).strip();
                if (head.length() > 1 && anchored(head, anchors)) {
                    return head;
                }
            }
        }
        return raw;
    }

    /** 归一化键：小写 + 去掉空白与常见标点。只用于身份判定，不用于展示 */
    public static String normKey(String s) {
        if (s == null) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 说明文本清洗：摘录里常带图片 markdown、行内公式、链接，直接展示是一串噪声 */
    public static String cleanDescription(String text) {
        if (text == null) {
            return "";
        }
        String t = text
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("\\$\\$?[^$]*\\$\\$?", "…")
                .replaceAll("\\\\[a-zA-Z]+", " ")
                .replaceAll("[`*]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([，。、；：])", "$1");
        // 「核心思想：」「定义：」这类前缀是原文的小节标签，不是说明本身
        t = t.replaceAll("^\\s*(核心思想|核心|定义|原理|说明|概述|简介|作用|用途|含义|是什么)\\s*[：:]\\s*", "");
        return t.strip();
    }

    // ---- 内部 ----

    private static void flushPara(List<Block> out, List<String> buf) {
        if (!buf.isEmpty()) {
            out.add(new Block("P", String.join(" ", buf)));
            buf.clear();
        }
    }

    private static void flushList(List<Block> out, List<String> buf) {
        if (!buf.isEmpty()) {
            out.add(new Block("L", String.join(" / ", buf)));
            buf.clear();
        }
    }

    private static boolean isHeading(String trimmed) {
        int i = 0;
        while (i < trimmed.length() && trimmed.charAt(i) == '#') {
            i++;
        }
        return i >= 1 && i <= 6 && i < trimmed.length() && trimmed.charAt(i) == ' ';
    }

    private static boolean isListItem(String trimmed) {
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return true;
        }
        int i = 0;
        while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i))) {
            i++;
        }
        return i > 0 && i + 1 < trimmed.length()
                && (trimmed.charAt(i) == '.' || trimmed.charAt(i) == ')')
                && trimmed.charAt(i + 1) == ' ';
    }

    private static String stripListMarker(String line) {
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            return line.substring(2).strip();
        }
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        return i + 1 < line.length() ? line.substring(i + 2).strip() : line.strip();
    }

    /**
     * 列表块里挑一条用：优先含概念名的那条，否则第一条。
     * 直接返回整段会把「核心思想」和「代价」一起当说明——那是两件事。
     */
    private static String pickListItem(String listText, String name) {
        String n = normKey(name);
        String first = null;
        for (String part : listText.split(" / ")) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = p;
            }
            if (!n.isEmpty() && normKey(p).contains(n)) {
                return p;
            }
        }
        return first == null ? listText : first;
    }

    private static boolean hasSentenceEnd(String s) {
        return s.indexOf('。') >= 0 || s.indexOf('；') >= 0 || s.indexOf('！') >= 0 || s.indexOf('？') >= 0;
    }

    /** 一段文本够不够格当"说明" */
    private static boolean usable(String body, String name, String heading) {
        String b = body.strip();
        if (b.length() < 12) {
            return false;
        }
        char c0 = b.charAt(0);
        if (c0 == '!' || c0 == '|' || c0 == '$' || c0 == '`' || c0 == '>') {
            return false;
        }
        if (b.contains("[x]") || b.contains("[ ]")) {
            return false;
        }
        String nb = normKey(b);
        if (nb.equals(normKey(name)) || nb.equals(normKey(heading))) {
            return false;
        }
        // 列表拼接：多个短条目拼成一段且没有成句标点，说明它只是条目堆叠
        String[] parts = b.split(" / ");
        if (parts.length >= 3 && !hasSentenceEnd(b)) {
            return false;
        }
        // 纯 ASCII 短串基本是代码行或命令
        return !b.matches("[a-zA-Z0-9_\\s.()#:+\\-]+");
    }

    private static String stripFences(String answer) {
        String s = answer.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return s;
    }

    private static String cleanName(String name) {
        String n = name.strip();
        // 去掉首尾常见的包裹与标点：模型偶尔带引号或句末句号
        while (!n.isEmpty() && " \t\"'`「」『』《》（）()[]【】.,;:，。、".indexOf(n.charAt(0)) >= 0) {
            n = n.substring(1);
        }
        while (!n.isEmpty() && " \t\"'`「」『』《》（）()[]【】.,;:，。、".indexOf(n.charAt(n.length() - 1)) >= 0) {
            n = n.substring(0, n.length() - 1);
        }
        return n.strip();
    }
}
