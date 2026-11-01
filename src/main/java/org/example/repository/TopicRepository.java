package org.example.repository;

import org.example.domain.Topic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TopicRepository extends JpaRepository<Topic, String> {

    Optional<Topic> findByNameAndSource(String name, String source);

    List<Topic> findAllByOrderByConceptCountDescNameAsc();

    /**
     * 按旧规则生成的主题数（版本为空也算旧）。
     *
     * <p>不能用派生查询 {@code existsByRuleVersionNot}：它在 SQL 里生成 {@code <>}，
     * 而版本为 null 的行比较结果是 NULL，不会被匹配上——恰好漏掉最需要重算的那批。
     */
    @Query("select count(t) from Topic t where t.ruleVersion is null or t.ruleVersion <> :version")
    long countStaleTopics(@Param("version") int version);
}
