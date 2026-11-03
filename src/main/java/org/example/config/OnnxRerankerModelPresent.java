package org.example.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 重排模型文件是否就位。
 *
 * <p><b>为什么重排模型可以缺、嵌入模型不行：</b>分发版不携带模型文件（合计约 1.1GB），
 * 只在安装后指引用户自行下载。于是"用户只下了嵌入模型、还没下重排模型"是常态而非异常——
 * 这时应当降级为不做重排（等价于 {@code app.rag.reranker-enabled=false}，检索照常工作，
 * 只是少了重排这一层精排），而不是让整个后端起不来。
 *
 * <p>嵌入模型没有这种余地：没有它整个检索层不成立，缺文件必须明确报错（见
 * {@code AiModelConfig#embeddingModel}）。
 */
public class OnnxRerankerModelPresent implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String path = context.getEnvironment().getProperty("app.rag.reranker-model-path", "");
        return !path.isBlank() && Files.exists(Path.of(path));
    }
}
