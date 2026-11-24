package com.chua.common.support.ai.agent;

/**
 * 内容护栏命中项。
 *
 * <p>一次内容检查的单条命中记录：命中类型 + 严重程度 + 规则标识 + 片段样例。
 * 命中项供审计 / 评测消费；样例自动截断，避免把完整敏感值写进日志。</p>
 *
 * @param type     命中类型，不应为 null
 * @param severity 严重程度，不应为 null
 * @param rule     命中规则标识（如 {@code injection/ignore-instructions}、
 *                 {@code pii/phone}、{@code secret/openai-key}），可为 null
 * @param sample   命中片段样例（已截断，不含完整敏感值），可为 null
 * @author CH
 * @since 4.0.0.52
 */
public record AgentContentFinding(Type type, Severity severity, String rule, String sample) {

    /**
     * 样例最大保留长度
     */
    public static final int MAX_SAMPLE_LENGTH = 80;

    /**
     * 命中类型
     */
    public enum Type {
        /**
         * 提示注入（含角色伪装、指令覆盖）
         */
        PROMPT_INJECTION,
        /**
         * 个人身份信息（手机号 / 证件 / 银行卡 / 邮箱等）
         */
        PII,
        /**
         * 凭据 / 密钥泄漏
         */
        SECRET,
        /**
         * 自定义敏感词（审计用，默认不改变内容）
         */
        SENSITIVE_KEYWORD
    }

    /**
     * 严重程度
     */
    public enum Severity {
        /**
         * 低：仅记录，不改变内容
         */
        LOW,
        /**
         * 中：默认脱敏
         */
        MEDIUM,
        /**
         * 高：输入侧默认阻断，工具返回侧默认摘除
         */
        HIGH
    }

    /**
     * 紧凑构造：null 容错 + 样例截断。
     */
    public AgentContentFinding {
        type = type == null ? Type.SENSITIVE_KEYWORD : type;
        severity = severity == null ? Severity.LOW : severity;
        sample = truncate(sample);
    }

    /**
     * 构造命中项（样例自动截断）。
     *
     * @param type     命中类型
     * @param severity 严重程度
     * @param rule     规则标识
     * @param sample   命中片段
     * @return 命中项
     */
    public static AgentContentFinding of(Type type, Severity severity, String rule, String sample) {
        return new AgentContentFinding(type, severity, rule, sample);
    }

    /**
     * 截断样例：压平换行、限制长度。
     *
     * @param sample 原始片段
     * @return 截断后的样例
     */
    private static String truncate(String sample) {
        if (sample == null) {
            return null;
        }
        String flat = sample.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_SAMPLE_LENGTH ? flat : flat.substring(0, MAX_SAMPLE_LENGTH) + "...";
    }
}
