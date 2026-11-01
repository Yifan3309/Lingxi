package org.example.repository;

import org.example.domain.ConceptNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface ConceptNoteRepository extends JpaRepository<ConceptNote, String> {

    List<ConceptNote> findByNoteId(String noteId);

    List<ConceptNote> findByConceptId(String conceptId);

    List<ConceptNote> findByConceptIdIn(Collection<String> conceptIds);

    /**
     * 重抽时整篇替换：先删后插，不做增量追加。
     * 追加会留下旧名字的残影，而"概念忽隐忽现"比"少一个概念"更伤信任。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from ConceptNote cn where cn.noteId = :noteId")
    int deleteByNoteId(@Param("noteId") String noteId);
}
