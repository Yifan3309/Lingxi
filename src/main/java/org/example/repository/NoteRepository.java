package org.example.repository;

import org.example.domain.Note;
import org.example.web.dto.NoteSummary;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NoteRepository extends JpaRepository<Note, String> {

    /**
     * 概念抽取后只定向更新指纹列，不 save(note) 整实体：抽取事务从读笔记到写回之间
     * 隔着一次模型调用（ConceptNoteRepository#deleteByNoteId 还会清空持久化上下文），
     * merge 会把事务开头读到的旧内容整行写回，覆盖模型调用期间用户的再次保存。
     */
    @Modifying
    @Query("update Note n set n.conceptHash = :hash where n.id = :id")
    int updateConceptHash(@Param("id") String id, @Param("hash") String hash);

    /**
     * 列表投影：只取展示字段，不搬 content（65k 列）——原来列表直接把整个实体搬给前端，
     * 1k 篇时单次刷新即数 MB。构造器表达式的类名必须写全限定名。
     */
    @Query("""
            select new org.example.web.dto.NoteSummary(n.id, n.title, n.tags, n.folder, n.source, n.updatedAt)
            from Note n""")
    List<NoteSummary> findAllSummaries(Sort sort);

    @Query("""
            select new org.example.web.dto.NoteSummary(n.id, n.title, n.tags, n.folder, n.source, n.updatedAt)
            from Note n
            where lower(n.title) like lower(concat('%', :keyword, '%'))
               or lower(n.content) like lower(concat('%', :keyword, '%'))""")
    List<NoteSummary> findSummariesByKeyword(String keyword, Sort sort);

    @Query("""
            select new org.example.web.dto.NoteSummary(n.id, n.title, n.tags, n.folder, n.source, n.updatedAt)
            from Note n
            where lower(n.tags) like lower(concat('%', :tag, '%'))""")
    List<NoteSummary> findSummariesByTag(String tag, Sort sort);

    /**
     * 文件夹附件用：直接按"本层 + 子层"查（prefix 传 folder + "/"），
     * 替代原来的 findAll(Sort) 全表加载再内存过滤。
     */
    List<Note> findByFolderOrFolderStartingWith(String folder, String prefix, Sort sort);

    /** 每日/每周回顾（M3）：取时间窗内更新的笔记，最新在前 */
    List<Note> findByUpdatedAtAfter(Instant after, Sort sort);
}
