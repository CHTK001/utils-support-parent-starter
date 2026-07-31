package com.chua.common.support.lang.date;

import java.time.LocalDateTime;
import java.time.Duration;

/**
 * 时间区间。
 * <p>描述一个起止时间范围，支持区间判断、交集、并集及持续时间计算。</p>
 *
 * @author CH
 * @since 2026/07/19
 */
public final class DateTimeRange {

    /**
     * 开始时间
     */
    private final LocalDateTime startTime;

    /**
     * 结束时间
     */
    private final LocalDateTime endTime;

    /**
     * 构造时间区间。
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @throws IllegalArgumentException 开始时间晚于结束时间
     */
    public DateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("startTime cannot be after endTime");
        }
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /**
     * 获取开始时间。
     *
     * @return startTime
     */
    public LocalDateTime getStartTime() {
        return startTime;
    }

    /**
     * 获取结束时间。
     *
     * @return endTime
     */
    public LocalDateTime getEndTime() {
        return endTime;
    }

    /**
     * 判断当前时间是否在区间内。
     *
     * @return true 在区间内
     */
    public boolean isInRange() {
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(startTime) && !now.isAfter(endTime);
    }

    /**
     * 判断指定时间是否在区间内。
     *
     * @param time 指定时间
     * @return true 在区间内
     */
    public boolean isInRange(LocalDateTime time) {
        return !time.isBefore(startTime) && !time.isAfter(endTime);
    }

    /**
     * 判断两个时间区间是否有重叠。
     *
     * @param other 另一个时间区间
     * @return true 有重叠
     */
    public boolean isOverlapping(DateTimeRange other) {
        return !this.startTime.isAfter(other.endTime) && !this.endTime.isBefore(other.startTime);
    }

    /**
     * 计算两个时间区间的交集。
     *
     * @param other 另一个时间区间
     * @return 交集区间，无交集返回 null
     */
    public DateTimeRange intersection(DateTimeRange other) {
        if (!isOverlapping(other)) {
            return null;
        }
        LocalDateTime start = this.startTime.isAfter(other.startTime) ? this.startTime : other.startTime;
        LocalDateTime end = this.endTime.isBefore(other.endTime) ? this.endTime : other.endTime;
        return new DateTimeRange(start, end);
    }

    /**
     * 计算两个时间区间的并集。
     *
     * @param other 另一个时间区间
     * @return 并集区间
     */
    public DateTimeRange union(DateTimeRange other) {
        LocalDateTime start = this.startTime.isBefore(other.startTime) ? this.startTime : other.startTime;
        LocalDateTime end = this.endTime.isAfter(other.endTime) ? this.endTime : other.endTime;
        return new DateTimeRange(start, end);
    }

    /**
     * 计算时间区间的持续时间（秒）。
     *
     * @return 持续时间（秒）
     */
    public long getDuration() {
        return Duration.between(startTime, endTime).getSeconds();
    }
}
