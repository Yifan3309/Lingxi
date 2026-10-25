package org.example.web.dto;

import java.util.List;

/**
 * SSE 问答的 done 帧：结束原因 + 来源列表。
 */
public record ChatDoneEvent(String finishReason, List<SourceRef> sources) {
}
