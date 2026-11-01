package org.example.repository;

import org.example.domain.ConceptTopic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ConceptTopicRepository extends JpaRepository<ConceptTopic, ConceptTopic.Key> {

    List<ConceptTopic> findByConceptId(String conceptId);

    List<ConceptTopic> findByTopicId(String topicId);

    /**
     * 已经挂到某个主题上的概念数（去重）。
     *
     * <p>用来判断"有没有概念还没有归属"——这比看主题总数可靠：
     * 主题数不为 0，但只有 14 个概念挂了边、另外 89 个是后来抽出来的，
     * 这种情况看上去一切正常，实际上大部分概念在页面上都落进未分类。
     */
    @Query("select count(distinct ct.conceptId) from ConceptTopic ct")
    long countDistinctConcepts();
}
