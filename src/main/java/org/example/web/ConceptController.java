package org.example.web;

import org.example.service.ConceptService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 概念层读取端点。
 *
 * <p>{@code /overview} 返回的结构与前端原型用的 {@code conceptLayer.json} 完全一致——
 * 这样从静态文件切到接口不用改渲染逻辑，也方便两边对照。
 */
@RestController
@RequestMapping("/api/concepts")
public class ConceptController {

    private final ConceptService conceptService;

    public ConceptController(ConceptService conceptService) {
        this.conceptService = conceptService;
    }

    /**
     * 主题 + 概念 + 未分类，一次取全。数据量在千级以内，暂不分页。
     *
     * <p>有概念还没有归属时惰性重算一次——否则页面上大量概念落在「未分类」，
     * 看起来像坏了。判断依据是"多少概念没归属"，不是"主题数是否为 0"：
     * 主题表里已经有几个主题、但大部分概念还没挂边的情况，后者查不出来。
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        if (conceptService.needsTopicRebuild()) {
            conceptService.rebuildTopics();
        }
        return conceptService.overview();
    }

    /** 单个概念的详情：说明、出处、主题、提及次数 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return conceptService.detail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "概念不存在: " + id));
    }
}
