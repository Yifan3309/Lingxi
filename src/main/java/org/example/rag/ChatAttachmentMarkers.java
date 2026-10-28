package org.example.rag;

/**
 * 附件上下文标记：组装增强提问、记忆剥离、检索抽取共用。
 * 正文中出现这些字面量时先转义，避免被误判为结构边界。
 */
public final class ChatAttachmentMarkers {

    public static final String ATTACH_BEGIN = "[[LINGXI_ATTACHMENTS_BEGIN]]";
    public static final String ATTACH_END = "[[LINGXI_ATTACHMENTS_END]]";
    public static final String QUESTION_BEGIN = "[[LINGXI_QUESTION_BEGIN]]";
    public static final String QUESTION_END = "[[LINGXI_QUESTION_END]]";

    private ChatAttachmentMarkers() {
    }

    /** 把正文/问题里出现的标记字面量替换为安全变体。 */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace(ATTACH_BEGIN, "[[LINGXI_ATTACH_BEGIN（禁用）]]")
                .replace(ATTACH_END, "[[LINGXI_ATTACH_END（禁用）]]")
                .replace(QUESTION_BEGIN, "[[LINGXI_Q_BEGIN（禁用）]]")
                .replace(QUESTION_END, "[[LINGXI_Q_END（禁用）]]");
    }

    /**
     * 从增强提问中抽出原始问题（供检索/重排使用）。
     * 优先取 [[LINGXI_QUESTION_BEGIN]]/[[LINGXI_QUESTION_END]] 之间；
     * 否则取附件块之后；都没有则原样返回（无附件时与旧行为一致）。
     */
    public static String extractQuestion(String augmented) {
        if (augmented == null) {
            return "";
        }
        int qb = augmented.indexOf(QUESTION_BEGIN);
        int qe = augmented.lastIndexOf(QUESTION_END);
        if (qb >= 0 && qe > qb) {
            return augmented.substring(qb + QUESTION_BEGIN.length(), qe).trim();
        }
        int ae = augmented.indexOf(ATTACH_END);
        if (ae >= 0) {
            return augmented.substring(ae + ATTACH_END.length()).trim();
        }
        return augmented;
    }
}
