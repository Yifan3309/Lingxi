package org.example.repository;

import org.example.domain.NoteChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NoteChunkRepository extends JpaRepository<NoteChunk, String> {

    /**
     * HQL 批量删除（立即落库）：derived delete 会把 DELETE 排进持久化上下文，而 Hibernate flush
     * 时 INSERT 先于 DELETE 执行——"删旧块+插新块"同唯一键 (note_id, chunk_index) 时必撞 23505
     * （全量重嵌入路径实测踩中）。绕过 ActionQueue 排序，删插同事务也安全。
     */
    @Modifying
    @Query("delete from NoteChunk c where c.noteId = :noteId")
    void deleteByNoteId(String noteId);

    List<NoteChunk> findByNoteIdOrderByChunkIndex(String noteId);
}
