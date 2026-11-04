package com.chua.common.support.apm;

/**
 * 通用查询条件。
 * <p>
 * 所有引擎实现共用一份条件对象：{@code null} / 空串表示「不过滤」，
 * 时间窗口统一用毫秒时间戳（{@link #startTime} / {@link #endTime}），
 * 分页统一 {@code pageNum} 从 1 开始、{@code pageSize} 为每页条数。
 * </p>
 *
 * @param serviceName    服务名（可空）
 * @param instanceName   实例名（可空）
 * @param endpointName   端点名（可空）
 * @param traceId        链路 ID（可空）
 * @param keyword        关键字（端点/日志检索用，可空）
 * @param state          链路状态过滤，null 视作 {@link OtnlTraceState#ALL}
 * @param startTime      起始毫秒时间戳
 * @param endTime        结束毫秒时间戳
 * @param step           时序聚合步长（SECOND / MINUTE / HOUR / DAY），可空
 * @param pageNum        页码，从 1 开始
 * @param pageSize       每页条数
 * @param orderByDuration 是否按耗时倒序（否则按开始时间倒序）
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlQuery(String serviceName,
                        String instanceName,
                        String endpointName,
                        String traceId,
                        String keyword,
                        OtnlTraceState state,
                        long startTime,
                        long endTime,
                        String step,
                        int pageNum,
                        int pageSize,
                        boolean orderByDuration) {

    /**
     * 最近 N 分钟的空条件
     *
     * @param minutes 分钟数
     * @return 查询条件
     */
    public static OtnlQuery lastMinutes(int minutes) {
        return ofMinutes(minutes, null);
    }

    /**
     * 最近 N 分钟 + 服务名
     *
     * @param minutes     分钟数
     * @param serviceName 服务名，可为空
     * @return 查询条件
     */
    public static OtnlQuery ofMinutes(int minutes, String serviceName) {
        long end = System.currentTimeMillis();
        long start = end - Math.max(1, minutes) * 60_000L;
        return new OtnlQuery(serviceName, null, null, null, null, OtnlTraceState.ALL,
                start, end, null, 1, 20, false);
    }

    /**
     * 空条件（引擎实现自行补默认时间窗口）
     *
     * @return 查询条件
     */
    public static OtnlQuery empty() {
        long end = System.currentTimeMillis();
        return new OtnlQuery(null, null, null, null, null, OtnlTraceState.ALL,
                end - 30 * 60_000L, end, null, 1, 20, false);
    }

    /**
     * 复制并设置服务名
     *
     * @param name 服务名
     * @return 新条件
     */
    public OtnlQuery withService(String name) {
        return new OtnlQuery(name, instanceName, endpointName, traceId, keyword, state,
                startTime, endTime, step, pageNum, pageSize, orderByDuration);
    }

    /**
     * 复制并设置实例名
     *
     * @param name 实例名
     * @return 新条件
     */
    public OtnlQuery withInstance(String name) {
        return new OtnlQuery(serviceName, name, endpointName, traceId, keyword, state,
                startTime, endTime, step, pageNum, pageSize, orderByDuration);
    }

    /**
     * 复制并设置关键字
     *
     * @param value 关键字
     * @return 新条件
     */
    public OtnlQuery withKeyword(String value) {
        return new OtnlQuery(serviceName, instanceName, endpointName, traceId, value, state,
                startTime, endTime, step, pageNum, pageSize, orderByDuration);
    }

    /**
     * 复制并设置分页
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 新条件
     */
    public OtnlQuery withPage(int page, int size) {
        return new OtnlQuery(serviceName, instanceName, endpointName, traceId, keyword, state,
                startTime, endTime, step, page, size, orderByDuration);
    }

    /**
     * 复制并设置时间窗口
     *
     * @param from 起始毫秒
     * @param to   结束毫秒
     * @return 新条件
     */
    public OtnlQuery withTime(long from, long to) {
        return new OtnlQuery(serviceName, instanceName, endpointName, traceId, keyword, state,
                from, to, step, pageNum, pageSize, orderByDuration);
    }

    /**
     * 复制并设置链路状态
     *
     * @param value 状态
     * @return 新条件
     */
    public OtnlQuery withState(OtnlTraceState value) {
        return new OtnlQuery(serviceName, instanceName, endpointName, traceId, keyword, value,
                startTime, endTime, step, pageNum, pageSize, orderByDuration);
    }

    /**
     * 复制并设置排序方式
     *
     * @param byDuration true 表示按耗时倒序
     * @return 新条件
     */
    public OtnlQuery withOrderByDuration(boolean byDuration) {
        return new OtnlQuery(serviceName, instanceName, endpointName, traceId, keyword, state,
                startTime, endTime, step, pageNum, pageSize, byDuration);
    }

    /**
     * 归一化后的状态（null → ALL）
     *
     * @return 状态
     */
    public OtnlTraceState stateOrAll() {
        return state == null ? OtnlTraceState.ALL : state;
    }

    /**
     * 归一化后的页码（至少 1）
     *
     * @return 页码
     */
    public int pageNumOrOne() {
        return Math.max(1, pageNum);
    }

    /**
     * 归一化后的每页条数（1~500，默认 20）
     *
     * @return 每页条数
     */
    public int pageSizeOrTwenty() {
        return pageSize <= 0 ? 20 : Math.min(pageSize, 500);
    }
}
