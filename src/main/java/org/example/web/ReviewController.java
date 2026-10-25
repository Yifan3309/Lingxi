package org.example.web;

import org.example.rag.GraphService;
import org.example.rag.ReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M3 连接与回顾：知识图谱与每日/每周回顾。
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final GraphService graphService;
    private final ReviewService reviewService;

    public ReviewController(GraphService graphService, ReviewService reviewService) {
        this.graphService = graphService;
        this.reviewService = reviewService;
    }

    @GetMapping("/graph")
    public GraphService.GraphResult graph() {
        return graphService.graph();
    }

    /** range=daily（今天，默认）/ weekly（最近 7 天） */
    @GetMapping("/review")
    public ReviewService.ReviewResult review(@RequestParam(defaultValue = "daily") String range) {
        if (!"daily".equalsIgnoreCase(range) && !"weekly".equalsIgnoreCase(range)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "range 仅支持 daily / weekly");
        }
        return reviewService.review(range);
    }
}
