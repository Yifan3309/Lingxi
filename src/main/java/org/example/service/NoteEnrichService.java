package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.example.config.EnrichProperties;
import org.example.domain.Note;
import org.example.repository.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 笔记 AI 富化（M2）：保存后异步生成「标签建议 + 摘要」，一次 LLM 调用产出两者。
 * AI 是建议者：结果写入 suggestedTags/summary，采纳与否由用户决定（经 PUT 改 tags）。
 * 任何失败只记日志，不影响笔记保存与检索。
 */
@Service
public class NoteEnrichService {

    private static final Logger log = LoggerFactory.getLogger(NoteEnrichService.class);

    private final ChatModel chatModel;
    private final NoteRepository noteRepo;
    private final EnrichProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NoteEnrichService(@Qualifier("chatModel") ChatModel chatModel, NoteRepository noteRepo, EnrichProperties props) {
        this.chatModel = chatModel;
        this.noteRepo = noteRepo;
        this.props = props;
    }

    @Async("enrichExecutor")
    public void enrich(String noteId) {
        try {
            Note note = noteRepo.findById(noteId).orElse(null);
            if (note == null) {
                return;
            }
            String content = note.getContent() == null ? "" : note.getContent();
            if (content.length() > props.maxContentChars()) {
                content = content.substring(0, props.maxContentChars());
            }
            ChatResponse response = chatModel.chat(
                    SystemMessage.from("""
                            你是笔记整理助手。根据笔记内容只输出一个 JSON 对象，不要输出其他任何文字：
                            {"tags":["标签1","标签2"],"summary":"一句话摘要"}
                            tags 给 2-5 个简短中文标签；summary 不超过两句话。
                            """),
                    UserMessage.from("标题：" + note.getTitle() + "\n\n内容：\n" + content));
            String answer = response.aiMessage().text();

            JsonNode node = objectMapper.readTree(stripJsonFences(answer));
            JsonNode tagsNode = node.get("tags");
            List<String> tags = new ArrayList<>();
            if (tagsNode != null && tagsNode.isArray()) {
                tagsNode.forEach(t -> {
                    String v = t.asText().trim();
                    if (!v.isEmpty()) {
                        tags.add(v);
                    }
                });
            }
            String summary = node.hasNonNull("summary") ? node.get("summary").asText().trim() : null;

            note.setSuggestedTags(tags.isEmpty() ? null : String.join(",", tags));
            note.setSummary(summary == null || summary.isEmpty() ? null : summary);
            noteRepo.save(note);
            log.info("笔记 {} 富化完成：标签建议={}，摘要={}", noteId, note.getSuggestedTags() != null, note.getSummary() != null);
        } catch (Exception e) {
            log.warn("笔记 {} 富化失败（不影响保存）：{}", noteId, String.valueOf(e.getMessage()));
        }
    }

    /** 模型偶尔会用 ```json 包裹，剥掉再解析。 */
    private static String stripJsonFences(String answer) {
        String s = answer == null ? "" : answer.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return s;
    }
}
