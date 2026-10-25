package org.example.web;

import org.example.rag.SemanticSearchService;
import org.example.web.dto.SourceRef;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 语义搜索（向量召回 + 可选重排）。与问答共用检索组件；
 * 也用于直接观察重排效果（score 为重排分数或向量相似度）。
 */
@RestController
public class SemanticSearchController {

    private final SemanticSearchService semanticSearchService;

    public SemanticSearchController(SemanticSearchService semanticSearchService) {
        this.semanticSearchService = semanticSearchService;
    }

    @GetMapping("/api/notes/semantic-search")
    public List<SourceRef> search(@RequestParam String q,
                                  @RequestParam(required = false, defaultValue = "5") Integer limit) {
        if (q == null || q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q 不能为空");
        }
        return semanticSearchService.search(q.trim(), Math.max(1, Math.min(limit, 50)));
    }
}
