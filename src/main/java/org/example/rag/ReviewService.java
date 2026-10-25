package org.example.rag;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.example.domain.Note;
import org.example.repository.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日/每周回顾（M3）：时间窗内更新的笔记 → 统计（篇数/标签分布/文件夹分布）+ LLM 回顾叙述
 * （"写了什么、关注什么、遗漏了什么"）。LLM 失败仅 summary 置 null，统计照常返回。
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private static final String REVIEW_SYSTEM_PROMPT = """
            你是个人知识库回顾助手。根据给定时间窗内的笔记清单（标题/标签/AI摘要），生成一段简短回顾：
            1. 这段时间写了什么（按主题归纳，不逐条罗列）
            2. 当前关注的重心是什么（从标签/文件夹分布看）
            3. 遗漏与建议（如：某主题只有一条孤立笔记、某文件夹长期未更新等，没有就说没有明显遗漏）
            用简体中文，三小段，每段一两句，不要用 Markdown 标题符号。""";

    private final NoteRepository noteRepo;
    private final ChatModel chatModel;

    public ReviewService(NoteRepository noteRepo, @Qualifier("chatModel") ChatModel chatModel) {
        this.noteRepo = noteRepo;
        this.chatModel = chatModel;
    }

    public record NoteItem(String id, String title, String tags, String folder, String summary, String updatedAt) {}

    public record ReviewResult(String range, String since, long count,
                               Map<String, Long> tags, Map<String, Long> folders,
                               List<NoteItem> notes, String summary) {}

    public ReviewResult review(String range) {
        boolean weekly = "weekly".equalsIgnoreCase(range);
        Instant since = weekly
                ? Instant.now().minus(Duration.ofDays(7))
                : LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        List<Note> notes = noteRepo.findByUpdatedAtAfter(since,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "updatedAt"));

        Map<String, Long> tagDist = new LinkedHashMap<>();
        Map<String, Long> folderDist = new LinkedHashMap<>();
        List<NoteItem> items = notes.stream().map(n -> {
            for (String t : (n.getTags() == null ? "" : n.getTags()).split("[,，]")) {
                if (!t.isBlank()) {
                    tagDist.merge(t.trim(), 1L, Long::sum);
                }
            }
            if (n.getFolder() != null && !n.getFolder().isBlank()) {
                folderDist.merge(n.getFolder(), 1L, Long::sum);
            }
            return new NoteItem(n.getId(), n.getTitle(), n.getTags(), n.getFolder(), n.getSummary(),
                    n.getUpdatedAt().toString());
        }).toList();

        String summary = items.isEmpty() ? null : summarize(range, items, tagDist, folderDist);
        log.info("回顾生成（{}）：{} 篇，summary={}", weekly ? "weekly" : "daily", items.size(), summary != null);
        return new ReviewResult(weekly ? "weekly" : "daily", since.toString(), items.size(),
                sortByValueDesc(tagDist), sortByValueDesc(folderDist), items, summary);
    }

    private String summarize(String range, List<NoteItem> items, Map<String, Long> tags, Map<String, Long> folders) {
        StringBuilder sb = new StringBuilder("时间范围：").append("daily".equals(range) ? "今天" : "最近一周").append('\n');
        for (NoteItem n : items) {
            sb.append("- ").append(n.title())
                    .append(n.tags() == null || n.tags().isBlank() ? "" : "（标签：" + n.tags() + "）")
                    .append(n.summary() == null || n.summary().isBlank() ? "" : "｜摘要：" + n.summary())
                    .append('\n');
        }
        if (!tags.isEmpty()) {
            sb.append("标签分布：").append(tags).append('\n');
        }
        if (!folders.isEmpty()) {
            sb.append("文件夹分布：").append(folders).append('\n');
        }
        try {
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(REVIEW_SYSTEM_PROMPT),
                    UserMessage.from(sb.toString()));
            return response.aiMessage().text();
        } catch (Exception e) {
            log.warn("回顾叙述生成失败（返回纯统计）：{}", String.valueOf(e.getMessage()));
            return null;
        }
    }

    private static Map<String, Long> sortByValueDesc(Map<String, Long> in) {
        return in.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }
}
