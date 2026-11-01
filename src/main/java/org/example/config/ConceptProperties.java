package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 概念层抽取配置。
 *
 * <p>默认开启，但它跑在保存之后的异步线程里——**失败不影响保存**，
 * 只把这篇笔记留在"未抽取"状态，下次编辑或回填时再补。
 */
@ConfigurationProperties(prefix = "app.concept")
public record ConceptProperties(
        /** 总开关。关掉后不再有新抽取，已有数据照常可读 */
        @DefaultValue("true") boolean enabled,
        /** 单篇正文上限：超过就截断。骨架本身也按块截断，这里兜住异常长的笔记 */
        @DefaultValue("12000") int maxContentChars,
        /** 保存后是否自动触发抽取。关掉则只能手动回填 */
        @DefaultValue("true") boolean extractOnSave,
        /**
         * 主题来源：llm = 让模型按语义分组（默认）；folder = 用文件夹做结构证据。
         *
         * <p>文件夹版永远可用、零成本、语义上不会错，但太机械：
         * 「深度学习」会把 CNN、强化学习、符号约定混在一起。
         * 模型分组能拆开这些，但有小模型不稳的风险，所以保留 folder 作为回退。
         */
        @DefaultValue("llm") String themeMode,
        /** 每批送给模型多少个概念 */
        @DefaultValue("20") int themeBatchSize,
        /** 单个主题最多几个概念（防止模型把所有东西塞进一组） */
        @DefaultValue("8") int themeMaxMembers) {

    /**
     * 主题生成规则的版本。
     *
     * <p>主题层是派生数据，规则一改（来源从文件夹换成模型、文件夹折叠方式变化…）
     * 库里旧的主题不会自己变。写入时记下版本，读取前发现版本不符就知道该重算。
     * v1 = 文件夹按完整路径；v2 = 模型语义分组 + 文件夹兜底。
     */
    public static final int TOPIC_RULE_VERSION = 2;
}
