import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.example.rag.ConceptExtractor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 复现某一篇笔记的抽取过程，把异常原样打出来。
 *
 * <p>用途：抽取失败在应用里只表现为"这篇还是 pending"，看不到原因。
 * 这里用同一套提示词、同一套模型参数、同一套纯逻辑走一遍，把异常暴露在控制台。
 *
 * <p>用法：java ConceptRepro <笔记文件>
 */
public class ConceptRepro {

    private static final SystemMessage PROMPT = SystemMessage.from("""
            从笔记结构里抽出概念名，只输出 JSON。

            概念是可复用的知识名词：技术、算法、模型、方法、理论、指标、协议。
            不要抽：小节名（核心思想/优点/边界/示例/总结/分类）、代码、命令、变量名、半句话。
            名字必须用原文写法，不要翻译、不要改写。
            最多 15 个。

            输出格式：{"entities":["概念1","概念2"]}""");

    public static void main(String[] args) throws Exception {
        String content = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        String title = args.length > 1 ? args[1] : "未命名";

        List<ConceptExtractor.Block> blocks = ConceptExtractor.blocks(content);
        System.out.println("块数 = " + blocks.size()
                + "（标题 " + blocks.stream().filter(ConceptExtractor.Block::isHeading).count()
                + "，列表 " + blocks.stream().filter(b -> b.type().equals("L")).count()
                + "，段落 " + blocks.stream().filter(b -> b.type().equals("P")).count()
                + "，代码 " + blocks.stream().filter(b -> b.type().equals("C")).count() + "）");

        String skeleton = ConceptExtractor.skeleton(blocks);
        System.out.println("骨架长度 = " + skeleton.length());
        System.out.println("--- 骨架 ---");
        System.out.println(skeleton);

        ChatModel model = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen_3.5_2BQ4:latest")
                .temperature(0.0)
                .think(false)
                .numPredict(512)
                .responseFormat(ResponseFormat.JSON)
                .timeout(Duration.ofSeconds(60))
                .build();

        long t0 = System.currentTimeMillis();
        String raw;
        try {
            raw = model.chat(PROMPT, UserMessage.from("笔记标题：" + title + "\n\n结构骨架：\n" + skeleton)).aiMessage().text();
        } catch (Exception e) {
            System.out.println("\n!! 模型调用抛出异常：" + e.getClass().getName());
            System.out.println("   message = " + e.getMessage());
            Throwable c = e.getCause();
            while (c != null) {
                System.out.println("   cause   = " + c.getClass().getName() + " : " + c.getMessage());
                c = c.getCause();
            }
            return;
        }
        System.out.println("\n耗时 " + (System.currentTimeMillis() - t0) + " ms");
        System.out.println("原始返回 = " + raw);

        List<String> names = ConceptExtractor.parseNames(raw, new ObjectMapper());
        System.out.println("解析出的名字 = " + names);

        List<String> anchors = ConceptExtractor.anchors(blocks);
        System.out.println("锚点 = " + anchors);
        for (String n : names) {
            boolean ok = ConceptExtractor.anchored(n, anchors);
            String desc = ConceptExtractor.attribute(n, blocks).orElse("(无说明)");
            System.out.printf("  %-16s 锚点=%s  说明=%s%n", n, ok ? "通过" : "丢弃", desc);
        }
    }
}
