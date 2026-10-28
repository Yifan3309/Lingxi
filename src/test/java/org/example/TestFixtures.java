package org.example;

import org.example.domain.Note;

import java.lang.reflect.Field;

/**
 * 测试夹具：Note 的 id 由 JPA @PrePersist 生成，单元测试里不落库，
 * 需要 id 时用反射写入（不改生产代码的可见性）。
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Note note(String id, String title, String tags, String folder, String content) {
        Note note = new Note();
        withId(note, id);
        note.setTitle(title);
        note.setTags(tags);
        note.setFolder(folder);
        note.setContent(content);
        return note;
    }

    /** 反射写私有 id（等同于 @PrePersist 的效果）。 */
    public static void withId(Note note, String id) {
        try {
            Field field = Note.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(note, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法写入 Note.id", e);
        }
    }
}
