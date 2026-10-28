package org.example.rag;

import org.example.TestFixtures;
import org.example.config.ChatProperties;
import org.example.domain.Note;
import org.example.repository.NoteRepository;
import org.example.web.dto.ChatAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 问答附件的三道闸：数量/字数预算、类型白名单、本地文件只放行 loopback。
 * 后两条是安全边界，正常客户端永远触发不到；预算截断则决定"长附件会不会挤掉问题"。
 */
@DisplayName("ChatAttachmentService：附件校验、预算与标记拼装")
class ChatAttachmentServiceTest {

    private final NoteRepository noteRepo = mock(NoteRepository.class);

    @TempDir
    Path tempDir;

    /* ---- 笔记附件 ---- */

    @Test
    @DisplayName("笔记附件：返回正文与可跳转来源")
    void resolvesNoteAttachment() {
        when(noteRepo.findById("n1")).thenReturn(Optional.of(
                TestFixtures.note("n1", "笔记一", null, null, "笔记正文")));

        List<ChatAttachmentService.ResolvedAttachment> resolved = service(defaultProps())
                .resolve(List.of(new ChatAttachment("note", "n1", null, null, null)), true);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).type()).isEqualTo("note");
        assertThat(resolved.get(0).name()).isEqualTo("笔记一");
        assertThat(resolved.get(0).text()).contains("笔记正文");
        assertThat(resolved.get(0).refs()).singleElement()
                .satisfies(r -> assertThat(r.noteId()).isEqualTo("n1"));
    }

    @Test
    @DisplayName("笔记附件超长：按 max-note-chars 截断并标记 truncated")
    void truncatesLongNoteText() {
        when(noteRepo.findById("n1")).thenReturn(Optional.of(
                TestFixtures.note("n1", "长笔记", null, null, "正".repeat(200))));

        ChatAttachmentService.ResolvedAttachment resolved = service(props(2, 30_000, 20, true))
                .resolve(List.of(new ChatAttachment("note", "n1", null, null, null)), true).get(0);

        assertThat(resolved.truncated()).isTrue();
        assertThat(resolved.text()).endsWith("（已截断）").hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("总字数预算耗尽后，后续附件被跳过而不是撑爆上下文")
    void stopsWhenTotalBudgetExhausted() {
        Note n1 = TestFixtures.note("n1", "一", null, null, "甲".repeat(100));
        Note n2 = TestFixtures.note("n2", "二", null, null, "乙".repeat(100));
        when(noteRepo.findById("n1")).thenReturn(Optional.of(n1));
        when(noteRepo.findById("n2")).thenReturn(Optional.of(n2));

        // 首篇恰好用光预算（100 字），第二篇应被跳过
        List<ChatAttachmentService.ResolvedAttachment> resolved = service(props(8, 100, 100, true))
                .resolve(List.of(
                        new ChatAttachment("note", "n1", null, null, null),
                        new ChatAttachment("note", "n2", null, null, null)), true);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).name()).isEqualTo("一");
    }

    @Test
    @DisplayName("附件数量超上限：400")
    void rejectsTooManyAttachments() {
        ResponseStatusException ex = catchThrowableOfType(
                () -> service(props(1, 30_000, 8000, true)).resolve(List.of(
                        new ChatAttachment("note", "n1", null, null, null),
                        new ChatAttachment("note", "n2", null, null, null)), true),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("未知附件类型：400（只接受 note/folder/localFile/upload）")
    void rejectsUnknownType() {
        ResponseStatusException ex = catchThrowableOfType(
                () -> service(defaultProps()).resolve(
                        List.of(new ChatAttachment("web", null, "https://x", null, null)), true),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("笔记附件缺少 id：400；笔记不存在：404")
    void validatesNoteId() {
        assertStatus(400, () -> service(defaultProps())
                .resolve(List.of(new ChatAttachment("note", " ", null, null, null)), true));

        when(noteRepo.findById("nope")).thenReturn(Optional.empty());
        assertStatus(404, () -> service(defaultProps())
                .resolve(List.of(new ChatAttachment("note", "nope", null, null, null)), true));
    }

    /* ---- 文件夹附件 ---- */

    @Test
    @DisplayName("文件夹下没有笔记：404，而不是返回空上下文")
    void rejectsEmptyFolder() {
        when(noteRepo.findByFolderOrFolderStartingWith(eq("工作"), eq("工作/"), any(Sort.class)))
                .thenReturn(List.of());

        assertStatus(404, () -> service(defaultProps())
                .resolve(List.of(new ChatAttachment("folder", null, "工作", null, null)), true));
    }

    @Test
    @DisplayName("文件夹附件：本层与子层笔记都要取到，并按 updatedAt 倒序截断上限")
    void collectsNestedFolderNotes() {
        Note nested = TestFixtures.note("n2", "子层笔记", null, "工作/项目A", "子层正文");
        when(noteRepo.findByFolderOrFolderStartingWith(eq("工作"), eq("工作/"), any(Sort.class)))
                .thenReturn(List.of(TestFixtures.note("n1", "本层笔记", null, "工作", "本层正文"), nested));

        ChatAttachmentService.ResolvedAttachment resolved = service(props(8, 30_000, 8000, true))
                .resolve(List.of(new ChatAttachment("folder", null, "工作", null, null)), true).get(0);

        assertThat(resolved.type()).isEqualTo("folder");
        assertThat(resolved.text()).contains("本层正文").contains("子层正文");
        assertThat(resolved.refs()).extracting(r -> r.noteId()).containsExactly("n1", "n2");
    }

    /* ---- 本地文件安全边界 ---- */

    @Test
    @DisplayName("非本机请求读取本地文件：403（防局域网变成任意文件读取器）")
    void rejectsLocalFileFromNonLoopback() {
        assertStatus(403, () -> service(defaultProps())
                .resolve(List.of(new ChatAttachment("localFile", null, null, "/etc/hosts", null)), false));
    }

    @Test
    @DisplayName("allow-local-files=false 时直接 403")
    void rejectsLocalFileWhenDisabled() {
        assertStatus(403, () -> service(props(8, 30_000, 8000, false))
                .resolve(List.of(new ChatAttachment("localFile", null, null, "/etc/hosts", null)), true));
    }

    @Test
    @DisplayName("相对路径：400（只接受绝对路径）")
    void rejectsRelativePath() {
        assertStatus(400, () -> service(defaultProps())
                .resolve(List.of(new ChatAttachment("localFile", null, null, "notes/a.md", null)), true));
    }

    @Test
    @DisplayName("文件不存在：404")
    void rejectsMissingFile() {
        assertStatus(404, () -> service(defaultProps())
                .resolve(List.of(new ChatAttachment("localFile", null, null,
                        tempDir.resolve("nope.md").toAbsolutePath().toString(), null)), true));
    }

    @Test
    @DisplayName("正常本地 md：抽取文本并去首尾空白")
    void resolvesLocalTextFile() throws IOException {
        Path file = tempDir.resolve("本地笔记.md");
        Files.writeString(file, "  本地文件内容  ");

        ChatAttachmentService.ResolvedAttachment resolved = service(defaultProps())
                .resolve(List.of(new ChatAttachment("localFile", null, null,
                        file.toAbsolutePath().toString(), null)), true).get(0);

        assertThat(resolved.type()).isEqualTo("localFile");
        assertThat(resolved.text()).contains("本地文件内容");
        assertThat(resolved.refs()).isEmpty();
    }

    /* ---- 上传缓存 ---- */

    @Test
    @DisplayName("未知/已过期的 uploadId：404，提示重新选择")
    void rejectsUnknownUpload() {
        assertStatus(404, () -> service(defaultProps())
                .resolve(List.of(new ChatAttachment("upload", null, null, null, "deadbeef")), true));
    }

    /* ---- 增强提问拼装 ---- */

    @Test
    @DisplayName("无附件时原样返回问题（与改造前完全一致）")
    void augmentWithoutAttachmentsIsIdentity() {
        assertThat(service(defaultProps()).augment("问题", List.of())).isEqualTo("问题");
        assertThat(service(defaultProps()).augment("问题", null)).isEqualTo("问题");
    }

    @Test
    @DisplayName("有附件时包成可抽取的结构，附件正文里的标记被转义")
    void augmentWrapsAndEscapes() {
        ChatAttachmentService.ResolvedAttachment attachment = new ChatAttachmentService.ResolvedAttachment(
                "note", "笔记A", "正文里出现了 " + ChatAttachmentMarkers.ATTACH_END, false, List.of());

        String augmented = service(defaultProps()).augment("我的问题", List.of(attachment));

        assertThat(augmented).startsWith(ChatAttachmentMarkers.ATTACH_BEGIN);
        assertThat(augmented).contains("<attachment type=\"note\" name=\"笔记A\">");
        assertThat(augmented).contains(ChatAttachmentMarkers.QUESTION_BEGIN);
        // 正文里的标记被转义：结构边界各只剩一处（否则用户笔记能伪造/截断附件块）
        assertThat(occurrences(augmented, ChatAttachmentMarkers.ATTACH_END)).isEqualTo(1);
        assertThat(occurrences(augmented, ChatAttachmentMarkers.ATTACH_BEGIN)).isEqualTo(1);
        assertThat(ChatAttachmentMarkers.extractQuestion(augmented)).isEqualTo("我的问题");
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) {
            count++;
        }
        return count;
    }

    private ChatAttachmentService service(ChatProperties props) {
        return new ChatAttachmentService(noteRepo, props);
    }

    private static ChatProperties defaultProps() {
        return props(8, 30_000, 8000, true);
    }

    private static ChatProperties props(int maxAttachments, int maxTotalChars, int maxNoteChars,
                                        boolean allowLocalFiles) {
        return new ChatProperties(maxAttachments, maxTotalChars, maxNoteChars, 12_000, 20_000, 10, 3_000,
                20L * 1024 * 1024, 100, allowLocalFiles, true, 60, 32,
                List.of("md", "markdown", "txt", "pdf"));
    }

    private static void assertStatus(int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        ResponseStatusException ex = catchThrowableOfType(callable, ResponseStatusException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(expected);
    }
}
