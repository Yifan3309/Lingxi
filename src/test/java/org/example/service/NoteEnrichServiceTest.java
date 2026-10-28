package org.example.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.example.TestFixtures;
import org.example.config.EnrichProperties;
import org.example.domain.Note;
import org.example.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 输出解析的健壮性：模型经常用 ```json 包裹、偶尔返回半截 JSON 或直接 401。
 * 这些分支决定了用户点「生成」时看到的是提示还是一屏 500，接口测试只能靠真实模型撞运气。
 */
@DisplayName("NoteEnrichService：LLM 输出解析与失败降级")
class NoteEnrichServiceTest {

    private StubChatModel chatModel;
    private NoteRepository noteRepo;
    private Note note;

    @BeforeEach
    void setUp() {
        chatModel = new StubChatModel();
        noteRepo = mock(NoteRepository.class);
        note = TestFixtures.note("n1", "标题", null, null, "正文内容");
        when(noteRepo.findById("n1")).thenReturn(Optional.of(note));
        when(noteRepo.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("标准 JSON：标签拼成逗号串，摘要落库")
    void parsesPlainJson() {
        chatModel.reply = "{\"tags\":[\"AI\",\"笔记\"],\"summary\":\"一句话摘要\"}";

        Note enriched = service(new EnrichProperties(true, 3000)).enrichNow("n1");

        assertThat(enriched.getSuggestedTags()).isEqualTo("AI,笔记");
        assertThat(enriched.getSummary()).isEqualTo("一句话摘要");
        verify(noteRepo).save(note);
    }

    @Test
    @DisplayName("```json 围栏被剥离后正常解析")
    void stripsJsonFences() {
        chatModel.reply = "```json\n{\"tags\":[\"RAG\"],\"summary\":\"摘要\"}\n```";

        Note enriched = service(new EnrichProperties(true, 3000)).enrichNow("n1");

        assertThat(enriched.getSuggestedTags()).isEqualTo("RAG");
        assertThat(enriched.getSummary()).isEqualTo("摘要");
    }

    @Test
    @DisplayName("标签数组里的空白项被丢弃；全空时建议标签为 null")
    void filtersBlankTags() {
        chatModel.reply = "{\"tags\":[\" AI \",\"  \",\"\",\"笔记\"],\"summary\":\"s\"}";
        assertThat(service(new EnrichProperties(true, 3000)).enrichNow("n1").getSuggestedTags())
                .isEqualTo("AI,笔记");

        setUp();
        chatModel.reply = "{\"tags\":[\"  \",\"\"],\"summary\":\"s\"}";
        assertThat(service(new EnrichProperties(true, 3000)).enrichNow("n1").getSuggestedTags()).isNull();
    }

    @Test
    @DisplayName("summary 缺失或为空时置 null，而不是写空串")
    void missingSummaryBecomesNull() {
        chatModel.reply = "{\"tags\":[\"A\"]}";
        assertThat(service(new EnrichProperties(true, 3000)).enrichNow("n1").getSummary()).isNull();

        setUp();
        chatModel.reply = "{\"tags\":[\"A\"],\"summary\":\"   \"}";
        assertThat(service(new EnrichProperties(true, 3000)).enrichNow("n1").getSummary()).isNull();
    }

    @Test
    @DisplayName("模型返回非 JSON：502，且不写库")
    void rejectsNonJson() {
        chatModel.reply = "抱歉，我无法处理这个请求。";

        ResponseStatusException ex = catchThrowableOfType(
                () -> service(new EnrichProperties(true, 3000)).enrichNow("n1"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode().value()).isEqualTo(502);
        verify(noteRepo, never()).save(any(Note.class));
    }

    @Test
    @DisplayName("LLM 调用抛异常（密钥无效/网络不通）：502，且不写库")
    void mapsLlmFailureTo502() {
        chatModel.failure = new RuntimeException("401 Unauthorized");

        ResponseStatusException ex = catchThrowableOfType(
                () -> service(new EnrichProperties(true, 3000)).enrichNow("n1"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode().value()).isEqualTo(502);
        assertThat(ex.getReason()).contains("AI 调用失败");
        verify(noteRepo, never()).save(any(Note.class));
    }

    @Test
    @DisplayName("富化被禁用：400，且完全不调用模型")
    void disabledEnrichDoesNotCallModel() {
        ResponseStatusException ex = catchThrowableOfType(
                () -> service(new EnrichProperties(false, 3000)).enrichNow("n1"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode().value()).isEqualTo(400);
        assertThat(chatModel.calls).isZero();
    }

    @Test
    @DisplayName("笔记不存在：404")
    void missingNoteIs404() {
        when(noteRepo.findById("nope")).thenReturn(Optional.empty());

        ResponseStatusException ex = catchThrowableOfType(
                () -> service(new EnrichProperties(true, 3000)).enrichNow("nope"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("超长笔记按 max-content-chars 截断后再送模型（控 token）")
    void truncatesLongContent() {
        note = TestFixtures.note("n1", "标题", null, null, "正".repeat(100));
        when(noteRepo.findById("n1")).thenReturn(Optional.of(note));
        chatModel.reply = "{\"tags\":[\"A\"],\"summary\":\"s\"}";

        service(new EnrichProperties(true, 10)).enrichNow("n1");

        String sent = ((UserMessage) chatModel.lastRequest.messages().get(1)).singleText();
        assertThat(sent).contains("正".repeat(10))
                .doesNotContain("正".repeat(11));
    }

    private NoteEnrichService service(EnrichProperties props) {
        return new NoteEnrichService(chatModel, noteRepo, props);
    }

    /** 用最小实现替代 mock：ChatModel 的 chat(ChatMessage...) 默认方法会转调 chat(ChatRequest)。 */
    private static final class StubChatModel implements ChatModel {
        private String reply = "{}";
        private RuntimeException failure;
        private ChatRequest lastRequest;
        private int calls;

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls++;
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }
}
