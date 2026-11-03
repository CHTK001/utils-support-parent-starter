package com.chua.common.support.ai.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 会话护栏：死循环判定 + 中断信号。
 *
 * <p><b>为什么需要它</b></p>
 * <p>{@link Agent} 的三个实现（opencode 子进程、langchain4j 进程内循环、agentscope Harness）
 * 各自维护自己的工具调用循环。过去只有“最大迭代次数”一个闸门，且不同实现对
 * {@code maxToolIterations<=0} 的解释并不一致（有的当成不限，等于放任死循环），
 * 也<b>没有任何从外部打断一轮执行的能力</b>——上层要停一个已经卡住的会话，
 * 除了等它超时之外无计可施。</p>
 *
 * <p>本类把两件事收拢成一份共用语义，供三个实现共同遵守：</p>
 * <ul>
 *   <li><b>重复调用判定</b>：连续若干次「工具名 + 入参」完全相同，视为模型在原地打转。
 *       只做<b>连续</b>判定：中间夹了别的调用就重新计数，避免把“逐个读文件”
 *       这类合法重复误判成死循环。</li>
 *   <li><b>中断信号</b>：{@link #interrupt()} 可从任意线程置位，
 *       实现类在自己的循环点检测 {@link #isInterrupted()} 后收尾退出；
 *       能拿到系统资源的实现（子进程 / Harness）应当同时释放资源。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：所有状态都是原子的或 volatile，可在执行线程之外读取与置位。</p>
 *
 * <p><b>使用约定</b>：一个 {@code AgentLoopGuard} 对应一圈对话（一轮 turn）。
 * turn 开始时调用 {@link #reset()}，执行过程中调用 {@link #recordToolCall(String, String)}
 * 与 {@link #isInterrupted()}，被拦截时用 {@link #describe()} 作为给用户的说明。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class AgentLoopGuard {

    /**
     * 默认重复阈值：连续 3 次完全相同的工具调用判定为死循环
     */
    public static final int DEFAULT_REPEAT_THRESHOLD = 3;

    /**
     * 连续相同调用的阈值
     */
    private final int repeatThreshold;

    /**
     * 中断信号
     */
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    /**
     * 当前连续相同调用次数（含第一次）
     */
    private final AtomicInteger consecutiveRepeats = new AtomicInteger(0);

    /**
     * 上一次调用的指纹（工具名 + 规范化入参）
     */
    private volatile String lastFingerprint;

    /**
     * 上一次调用的工具名（仅用于提示用户）
     */
    private volatile String lastToolName;

    /**
     * 使用默认阈值
     */
    public AgentLoopGuard() {
        this(DEFAULT_REPEAT_THRESHOLD);
    }

    /**
     * 指定重复阈值
     *
     * @param repeatThreshold 连续相同调用达到该次数即判定死循环；非正数回退默认值
     */
    public AgentLoopGuard(int repeatThreshold) {
        this.repeatThreshold = repeatThreshold > 0 ? repeatThreshold : DEFAULT_REPEAT_THRESHOLD;
    }

    /**
     * 重复阈值
     *
     * @return 连续相同调用达到该次数即判定死循环
     */
    public int repeatThreshold() {
        return repeatThreshold;
    }

    /**
     * 是否已被要求中断
     *
     * @return true 表示上层要求结束本轮执行
     */
    public boolean isInterrupted() {
        return interrupted.get();
    }

    /**
     * 置位中断信号（可跨线程调用）
     */
    public void interrupt() {
        interrupted.set(true);
    }

    /**
     * 复位：新的一轮 turn 开始前调用，清掉中断信号与重复计数
     */
    public void reset() {
        interrupted.set(false);
        consecutiveRepeats.set(0);
        lastFingerprint = null;
        lastToolName = null;
    }

    /**
     * 当前连续相同调用次数
     *
     * @return 连续相同调用次数（含第一次），无调用时为 0
     */
    public int consecutiveRepeats() {
        return consecutiveRepeats.get();
    }

    /**
     * 上一次调用的工具名
     *
     * @return 工具名，无调用时为 null
     */
    public String lastToolName() {
        return lastToolName;
    }

    /**
     * 登记一次工具调用，并判断是否已构成死循环。
     *
     * <p>指纹为「工具名 + 规范化入参」，只与<b>紧挨着的上一次</b>调用比较：
     * 不相同则计数复位为 1，相同则累加，累加到阈值即返回 true。</p>
     *
     * @param toolName 工具名
     * @param args     工具入参原文（可为 null）
     * @return true 表示本次调用已触发重复阈值，调用方应立即停止继续调用工具
     */
    public boolean recordToolCall(String toolName, String args) {
        String name = toolName == null ? "" : toolName.trim();
        String fingerprint = name + '\u0000' + normalize(args);
        String previous = this.lastFingerprint;
        this.lastFingerprint = fingerprint;
        this.lastToolName = name;
        if (fingerprint.equals(previous)) {
            return consecutiveRepeats.incrementAndGet() >= repeatThreshold;
        }
        consecutiveRepeats.set(1);
        return repeatThreshold <= 1;
    }

    /**
     * 给用户看的说明：为什么这轮被判成死循环
     *
     * @return 中文说明文本
     */
    public String describe() {
        String name = lastToolName == null ? "" : lastToolName;
        return "连续 " + consecutiveRepeats.get() + " 次相同工具调用（tool=" + name
                + "，阈值 " + repeatThreshold + "），已判定为死循环并终止本轮";
    }

    /**
     * 规范化入参：去首尾空白并把连续空白压成单个空格
     *
     * @param args 原始入参
     * @return 规范化结果，null 视为空串
     */
    private static String normalize(String args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        String trimmed = args.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        boolean lastSpace = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (!lastSpace) {
                    sb.append(' ');
                    lastSpace = true;
                }
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        return sb.toString();
    }
}
