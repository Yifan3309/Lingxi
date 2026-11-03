package org.example.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * parseNames 是「解析失败」与「确实没有概念」的分界线：吞错会把旧概念永久清空
 * 并写指纹导致永不重试（见 ConceptService#extract），这里锁死两种情形各自的行为。
 */
class ConceptExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("标准对象形态：{\"entities\":[...]}")
    void parsesObjectForm() {
        assertThat(ConceptExtractor.parseNames("{\"entities\":[\"卷积神经网络\",\"缓存\"]}", mapper))
                .containsExactly("卷积神经网络", "缓存");
    }

    @Test
    @DisplayName("裸数组 + markdown 围栏都容忍")
    void parsesBareArrayInsideFence() {
        assertThat(ConceptExtractor.parseNames("```json\n[\"缓存\",\"DMA\"]\n```", mapper))
                .containsExactly("缓存", "DMA");
    }

    @Test
    @DisplayName("数组元素写成对象时认 name/concept/entity 键")
    void parsesObjectElements() {
        assertThat(ConceptExtractor.parseNames("{\"entities\":[{\"concept\":\"元数据过滤\"}]}", mapper))
                .containsExactly("元数据过滤");
    }

    @Test
    @DisplayName("合法的空数组 = 确实没有概念，正常返回空")
    void emptyArrayMeansNoConcepts() {
        assertThat(ConceptExtractor.parseNames("{\"entities\":[]}", mapper)).isEmpty();
    }

    @Test
    @DisplayName("截断/混入说明文字必须抛错，绝不静默变 0 个概念")
    void malformedJsonThrowsInsteadOfReturningEmpty() {
        assertThatThrownBy(() -> ConceptExtractor.parseNames("{\"entities\":[\"卷积", mapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                ConceptExtractor.parseNames("好的，下面是抽取结果：{\"entities\":[\"缓存\"]}", mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空正文（思考吃满输出预算/被截断）也是失败，不能当\"没有概念\"")
    void blankOutputThrows() {
        assertThatThrownBy(() -> ConceptExtractor.parseNames("", mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空内容");
        assertThatThrownBy(() -> ConceptExtractor.parseNames("   \n ", mapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConceptExtractor.parseNames(null, mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
