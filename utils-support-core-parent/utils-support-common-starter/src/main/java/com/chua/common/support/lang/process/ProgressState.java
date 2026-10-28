package com.chua.common.support.lang.process;
import java.time.Duration;
import java.time.Instant;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/**
 * 进度状态，记录任务的进度信息。
 * <p>
 * 包含当前进度、最大值、开始时间、已消耗时间等状态数据。
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
public class ProgressState {
    /**
     * 任务名称
     */
    String taskName;
    /**
     * 附加消息
     */
    String extraMessage = "";
    /**
     * 是否为不定进度模式
     */
    boolean indefinite = false;
    /**
     * 起始位置
     * <p>
     * 进度条示意图：
     * [===============|=========>             ]
     *  0             start     current        max
     */
    long start;
    /**
     * 当前进度
     */
    long current;
    /**
     * 最大值
     */
    long max;
    /**
     * 开始时间
     */
    Instant startInstant;
    /**
     * 开始前已消耗时间
     */
    Duration elapsedBeforeStart;
    /**
     * 是否存活
     */
    volatile boolean alive = true;
    /**
     * 是否暂停
     */
    volatile boolean paused = false;
    /**
     * 构造 ProgressState 实例。
     *
     *
     * @param taskName
     * @param initialMax                               0
     * @param startFrom
     * @param elapsedBeforeStart
     */
    ProgressState(String taskName, long initialMax, long startFrom, Duration elapsedBeforeStart) {
        this.taskName = taskName;
        if (initialMax < 0) {
            indefinite = true;
        } else {
            this.max = initialMax;
        }
        this.start = startFrom;
        this.current = startFrom;
        this.startInstant = Instant.now();
        this.elapsedBeforeStart = elapsedBeforeStart;
    }
    /**
     * 获取 TaskName。
     *
     *
     * @return
     */
    public String getTaskName() {
        return taskName;
    }
    /**
     * 获取 ExtraMessage。
     *
     *
     * @return
     */
    public synchronized String getExtraMessage() {
        return extraMessage;
    }
    /**
     * 获取 Start。
     *
     *
     * @return
     */
    public synchronized long getStart() {
        return start;
    }
    /**
     * 获取 Current。
     *
     *
     * @return
     */
    public synchronized long getCurrent() {
        return current;
    }
    /**
     * 获取 Max。
     *
     *
     * @return
     */
    public synchronized long getMax() {
        return max;
    }
    /**
     *                         0.0-1.0
     *
     * @return                               0.0   1.0
     */
    public synchronized double getNormalizedProgress() {
        if (max <= 0) {
            return 0.0;
        } else if (current > max) {
            return 1.0;
        } else {
            return ((double) current) / max;
        }
    }
    /**
     * 获取 StartInstant。
     *
     *
     * @return
     */
    public synchronized Instant getStartInstant() {
        return startInstant;
    }
    /**
     * 获取 ElapsedBeforeStart。
     *
     *
     * @return
     */
    public synchronized Duration getElapsedBeforeStart() {
        return elapsedBeforeStart;
    }
    /**
     * 获取 ElapsedAfterStart。
     *
     *
     * @return
     */
    public synchronized Duration getElapsedAfterStart() {
        return (startInstant == null)
                ? Duration.ZERO
                : Duration.between(startInstant, Instant.now());
    }
    /**
     * 获取 TotalElapsed。
     *
     *
     * @return                                   +
     */
    public synchronized Duration getTotalElapsed() {
        return getElapsedBeforeStart().plus(getElapsedAfterStart());
    }
    /**
     * 是否为 Indefinite。
     *
     *
     * @return true                      false
     */
    public synchronized boolean isIndefinite() {
        return indefinite;
    }
    /**
     * 是否为 Alive。
     *
     *
     * @return true                      false
     */
    public synchronized boolean isAlive() {
        return alive;
    }
    /**
     * 是否为 Paused。
     *
     *
     * @return true                   false
     */
    public synchronized boolean isPaused() {
        return paused;
    }
    /**
     * 设置为确定状态。
     */
    synchronized void setAsDefinite() {
        indefinite = false;
    }
    /**
     * 设置为不确定状态。
     */
    synchronized void setAsIndefinite() {
        indefinite = true;
    }
    /**
     * 获取最大提示长度。
     *
     *
     * @param n
     */
    synchronized void maxHint(long n) {
        max = n;
    }
    /**
     * 按步长推进。
     *
     *
     * @param n
     */
    synchronized void stepBy(long n) {
        current += n;
        if (current > max) {
            max = current;
        }
    }
    /**
     * 分步转换。
     *
     *
     * @param n
     */
    synchronized void stepTo(long n) {
        current = n;
        if (current > max) {
            max = current;
        }
    }
    /**
     * 设置ExtraMessage。
     *
     *
     * @param msg
     */
    synchronized void setExtraMessage(String msg) {
        extraMessage = msg;
    }
    /**
     * 暂停。
     * <p>
     *
     */
    synchronized void pause() {
        paused = true;
        start = current;
        elapsedBeforeStart = elapsedBeforeStart.plus(Duration.between(startInstant, Instant.now()));
    }
    /**
     * 恢复。
     * <p>
     *
     */
    synchronized void resume() {
        paused = false;
        startInstant = Instant.now();
    }
    /**
     * 重置。
     * <p>
     *
     */
    synchronized void reset() {
        start = 0;
        current = 0;
        startInstant = Instant.now();
        elapsedBeforeStart = Duration.ZERO;
    }
    /**
     * <p>
     *                         false
     */
    synchronized void kill() {
        alive = false;
    }
}
