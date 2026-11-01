import org.example.rag.ConceptExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 概念抽取纯逻辑的自检：切块、骨架、锚点、说明归属、输出解析。
 * 不含模型调用，用来确认 Java 移植和 Python 原型行为一致。
 */
public class ConceptProbe {
    public static void main(String[] args) throws Exception {
        String note = Files.readString(Path.of("target/probe-note.md"));
        List<ConceptExtractor.Block> blocks = ConceptExtractor.blocks(note);
        System.out.println("块数 = " + blocks.size()
                + "（标题 " + blocks.stream().filter(ConceptExtractor.Block::isHeading).count()
                + "，列表 " + blocks.stream().filter(b -> b.type().equals("L")).count()
                + "，段落 " + blocks.stream().filter(b -> b.type().equals("P")).count() + "）");
        System.out.println("\n--- 骨架前 6 行 ---");
        String[] lines = ConceptExtractor.skeleton(blocks).split("\n");
        for (int i = 0; i < Math.min(6, lines.length); i++) {
            System.out.println("  " + lines[i]);
        }

        List<String> anchors = ConceptExtractor.anchors(blocks);
        System.out.println("\n锚点 " + anchors.size() + " 个：" + anchors);

        System.out.println("\n--- 锚点校验 ---");
        for (String name : List.of("查询压缩", "元数据过滤", "重排", "检索后重排",
                "Bellman optimality equation", "一个函数接收另一个函数")) {
            System.out.printf("  %-28s %s%n", name, ConceptExtractor.anchored(name, anchors) ? "通过" : "丢弃");
        }

        System.out.println("\n--- 结构归属说明 ---");
        for (String name : List.of("查询压缩", "元数据过滤", "动态跳过检索")) {
            System.out.printf("  %-12s %s%n", name,
                    ConceptExtractor.attribute(name, blocks)
                            .map(s -> s.substring(0, Math.min(56, s.length())))
                            .orElse("(没有说明)"));
        }

        System.out.println("\n--- 模型输出解析 ---");
        ObjectMapper m = new ObjectMapper();
        System.out.println("  正常： " + ConceptExtractor.parseNames(
                "{\"entities\":[\"查询压缩\",\"查询路由\"]}", m));
        System.out.println("  带围栏：" + ConceptExtractor.parseNames(
                "```json\n{\"entities\":[\"元数据过滤\"]}\n```", m));
        System.out.println("  脏名字：" + ConceptExtractor.parseNames(
                "{\"entities\":[\" 「查询压缩」 \",\"\",\"很长的半句话不应该被当成概念因为超过二十个字了\"]}", m));
        System.out.println("  坏 JSON：" + ConceptExtractor.parseNames("不是 json", m));
        System.out.println("  对象形态：" + ConceptExtractor.parseNames(
                "{\"entities\":[{\"concept\":\"元数据过滤\"},{\"entity\":\"查询压缩\"}]}", m));

        System.out.println("\n--- 说明清洗 ---");
        System.out.println("  " + ConceptExtractor.cleanDescription(
                "输入门是$\\mathbf{I}_t \\in \\mathbb{R}^{n \\times h}$，遗忘门是$x$。 ![图](/api/assets/a.png)"));
    }
}
