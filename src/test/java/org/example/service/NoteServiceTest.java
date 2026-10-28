package org.example.service;

import org.example.domain.Note;
import org.example.rag.NoteIngestService;
import org.example.repository.NoteRepository;
import org.example.web.dto.NoteRequest;
import org.example.web.dto.NoteSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NoteService 的纯逻辑：标题推导、文件夹路径规范化、PUT 的部分更新语义、嵌入失败不阻断保存。
 * 接口测试覆盖了"正常路径 + 两个典型坏路径"，但边界（连续斜杠、点段、长度上限、
 * 部分更新"null 不修改"）与"嵌入抛异常仍返回 201"没法用 HTTP 精确构造。
 */
@DisplayName("NoteService：标题推导与文件夹规范化")
class NoteServiceTest {

    private NoteRepository noteRepo;
    private NoteIngestService ingestService;
    private NoteService service;

    @BeforeEach
    void setUp() {
        noteRepo = mock(NoteRepository.class);
        ingestService = mock(NoteIngestService.class);
        service = new NoteService(noteRepo, ingestService);
        when(noteRepo.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /* ---- 标题推导 ---- */

    @Test
    @DisplayName("title 省略时取 content 首行，且只取首行")
    void derivesTitleFromFirstLine() {
        Note saved = service.create(new NoteRequest(null, "第一行标题\n第二行不算", null, null));

        assertThat(saved.getTitle()).isEqualTo("第一行标题");
    }

    @Test
    @DisplayName("首行超 30 字符时截断到 30")
    void truncatesLongTitle() {
        Note saved = service.create(new NoteRequest(null, "x".repeat(50), null, null));

        assertThat(saved.getTitle()).hasSize(30);
    }

    @Test
    @DisplayName("显式 title 去首尾空白；纯空白视为省略")
    void trimsExplicitTitle() {
        assertThat(service.create(new NoteRequest("  标题  ", "正文", null, null)).getTitle())
                .isEqualTo("标题");
        assertThat(service.create(new NoteRequest("   ", "正文首行", null, null)).getTitle())
                .isEqualTo("正文首行");
    }

    @Test
    @DisplayName("create 默认 source=manual，显式传入的来源被保留")
    void recordsSource() {
        assertThat(service.create(new NoteRequest("t", "c", null, null)).getSource()).isEqualTo("manual");
        assertThat(service.create(new NoteRequest("t", "c", null, null), "import").getSource())
                .isEqualTo("import");
    }

    /* ---- 文件夹路径规范化 ---- */

    @Test
    @DisplayName("反斜杠统一为 /，并逐段去空白")
    void normalizesFolderSeparators() {
        assertThat(createWithFolder("工作\\项目A\\周报").getFolder()).isEqualTo("工作/项目A/周报");
        assertThat(createWithFolder(" 工作 / 项目A ").getFolder()).isEqualTo("工作/项目A");
    }

    @Test
    @DisplayName("首尾与连续斜杠被丢弃，全空视为未归类（null）")
    void dropsEmptySegments() {
        assertThat(createWithFolder("/工作//项目A/").getFolder()).isEqualTo("工作/项目A");
        assertThat(createWithFolder("   ").getFolder()).isNull();
        assertThat(createWithFolder("///").getFolder()).isNull();
    }

    @Test
    @DisplayName("路径段为 . 或 .. 时拒绝（400），防止伪造层级")
    void rejectsDotSegments() {
        assertBadRequest(() -> createWithFolder("工作/../机密"));
        assertBadRequest(() -> createWithFolder("."));
        assertBadRequest(() -> createWithFolder(".."));
    }

    @Test
    @DisplayName("路径超过列上限 128 字符时拒绝（400）")
    void rejectsTooLongFolder() {
        String tooLong = "a".repeat(64) + "/" + "b".repeat(64);  // 129
        assertBadRequest(() -> createWithFolder(tooLong));
    }

    /* ---- PUT 部分更新语义 ---- */

    @Test
    @DisplayName("PUT 传 null 表示不修改；folder 传空串表示移出文件夹")
    void updateSemanticsOfNullVersusEmpty() {
        Note existing = org.example.TestFixtures.note("n1", "原标题", "旧标签", "旧/文件夹", "旧内容");
        when(noteRepo.findById("n1")).thenReturn(Optional.of(existing));

        service.update("n1", new NoteRequest(null, null, null, null));
        assertThat(existing.getTitle()).isEqualTo("原标题");
        assertThat(existing.getContent()).isEqualTo("旧内容");
        assertThat(existing.getFolder()).isEqualTo("旧/文件夹");

        service.update("n1", new NoteRequest(null, null, null, ""));
        assertThat(existing.getFolder()).isNull();
    }

    @Test
    @DisplayName("PUT 传值时覆盖对应字段，其它字段不变")
    void updateOverwritesProvidedFields() {
        Note existing = org.example.TestFixtures.note("n1", "旧标题", "旧标签", "旧夹", "旧内容");
        when(noteRepo.findById("n1")).thenReturn(Optional.of(existing));

        service.update("n1", new NoteRequest("新标题", "新内容", "新标签", "新/夹"));

        assertThat(existing.getTitle()).isEqualTo("新标题");
        assertThat(existing.getContent()).isEqualTo("新内容");
        assertThat(existing.getTags()).isEqualTo("新标签");
        assertThat(existing.getFolder()).isEqualTo("新/夹");
    }

    @Test
    @DisplayName("笔记不存在时 404")
    void rejectsMissingNote() {
        when(noteRepo.findById("nope")).thenReturn(Optional.empty());

        ResponseStatusException ex = catchThrowableOfType(
                () -> service.update("nope", new NoteRequest("t", "c", null, null)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }

    /* ---- 列表投影（不含正文） ---- */

    @Test
    @DisplayName("列表走投影查询；keyword+tag 同时给出时标签在内存里二次过滤")
    void searchReturnsSummariesAndFiltersTag() {
        NoteSummary a = new NoteSummary("n1", "A", "AI,笔记", null, "manual", Instant.parse("2026-10-01T00:00:00Z"));
        NoteSummary b = new NoteSummary("n2", "B", "菜谱", null, "manual", Instant.parse("2026-10-02T00:00:00Z"));
        when(noteRepo.findSummariesByKeyword(eq("测试"), any(Sort.class))).thenReturn(List.of(a, b));

        assertThat(service.search("测试", "AI")).extracting(NoteSummary::id).containsExactly("n1");
    }

    @Test
    @DisplayName("只有标签走标签查询，都没有走全量投影")
    void searchRoutesToTagOrAll() {
        when(noteRepo.findSummariesByTag(eq("AI"), any(Sort.class))).thenReturn(List.of());
        when(noteRepo.findAllSummaries(any(Sort.class))).thenReturn(List.of());

        service.search(null, "AI");
        verify(noteRepo).findSummariesByTag(eq("AI"), any(Sort.class));

        service.search(null, null);
        verify(noteRepo).findAllSummaries(any(Sort.class));
    }

    /* ---- 失败降级 ---- */

    @Test
    @DisplayName("嵌入入库失败不阻断保存（笔记仍可按关键词检索）")
    void ingestFailureDoesNotBreakSave() {
        doThrow(new IllegalStateException("向量库写入失败")).when(ingestService).ingest(any(Note.class));

        Note saved = service.create(new NoteRequest("标题", "正文", null, null));

        assertThat(saved.getTitle()).isEqualTo("标题");
        verify(noteRepo).save(any(Note.class));
    }

    @Test
    @DisplayName("删除时先清向量再删笔记，向量与元数据不残留")
    void deleteRemovesVectorsFirst() {
        Note existing = org.example.TestFixtures.note("n1", "t", null, null, "c");
        when(noteRepo.findById("n1")).thenReturn(Optional.of(existing));

        service.delete("n1");

        verify(ingestService).deleteVectors("n1");
        verify(noteRepo).delete(existing);
    }

    private Note createWithFolder(String folder) {
        return service.create(new NoteRequest("t", "正文", null, folder));
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        ResponseStatusException ex = catchThrowableOfType(callable, ResponseStatusException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
    }
}
