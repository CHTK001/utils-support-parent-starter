package com.chua.common.support.ai.chat.aggregate;

/**
 * 流式故障转移的两个等待上限，供上层按部署情况覆盖。
 *
 * <p><b>为什么要可配</b>：这两个值原先是 {@link FailoverTemplate} 里的常量
 * （首包 8 秒 / 整段完成 100 秒），任何使用方都改不了。它们的实际含义是
 * 「最多容忍上游卡顿多久」——而这在不同部署里差别很大：内网直连的快模型 8 秒绰绰有余，
 * 走公网的大模型、或者开了深度思考的链路，100 秒整段上限会先把正常的慢回复掐掉。
 * 上层要调，只能改公共库再重新打包。</p>
 *
 * <p><b>默认��保持原常量值不变</b>（首包 8000ms / 整段 100s），因此不设置任何值时
 * 行为与改造前逐毫秒一致 —— 这是把常量变成可配时最容易踩的坑：顺手「优化」一个默认值，
 * 就等于悄悄改了所有使用方的行为。</p>
 *
 * <p>设置为非正数视为无效，读取时会回落到默认值，避免有人填 0 导致「首包立刻超时」，
 * 把每一次正常请求都打成故障转移。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FailoverTimeouts {

    /**
     * 默认首包等待上限（毫秒）：与改造前的常量一致
     */
    public static final long DEFAULT_FIRST_TOKEN_TIMEOUT_MS = 8000L;

    /**
     * 默认整段完成等待上限（秒）：与改造前的常量一致
     */
    public static final long DEFAULT_COMPLETION_WAIT_SECONDS = 100L;

    /**
     * 首包等待上限（毫秒）
     */
    private static volatile long firstTokenTimeoutMs = DEFAULT_FIRST_TOKEN_TIMEOUT_MS;

    /**
     * 整段完成等待上限（秒）
     */
    private static volatile long completionWaitSeconds = DEFAULT_COMPLETION_WAIT_SECONDS;

    private FailoverTimeouts() {
    }

    /**
     * 取首包等待上限（毫秒）。
     *
     * @return 生效值；非正数时返回默认值
     */
    public static long firstTokenTimeoutMs() {
        long value = firstTokenTimeoutMs;
        return value > 0 ? value : DEFAULT_FIRST_TOKEN_TIMEOUT_MS;
    }

    /**
     * 取整段完成等待上限（秒）。
     *
     * @return 生效值；非正数时返回默认值
     */
    public static long completionWaitSeconds() {
        long value = completionWaitSeconds;
        return value > 0 ? value : DEFAULT_COMPLETION_WAIT_SECONDS;
    }

    /**
     * 覆盖首包等待上限。
     *
     * @param millis 毫秒数；非正数表示恢复默认
     */
    public static void setFirstTokenTimeoutMs(long millis) {
        firstTokenTimeoutMs = millis > 0 ? millis : DEFAULT_FIRST_TOKEN_TIMEOUT_MS;
    }

    /**
     * 覆盖整段完成等待上限。
     *
     * @param seconds 秒数；非正数表示恢复默认
     */
    public static void setCompletionWaitSeconds(long seconds) {
        completionWaitSeconds = seconds > 0 ? seconds : DEFAULT_COMPLETION_WAIT_SECONDS;
    }

    /**
     * 恢复两个上限的默认值。
     */
    public static void reset() {
        firstTokenTimeoutMs = DEFAULT_FIRST_TOKEN_TIMEOUT_MS;
        completionWaitSeconds = DEFAULT_COMPLETION_WAIT_SECONDS;
    }
}