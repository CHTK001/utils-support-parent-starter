package com.chua.common.support.concurrent.collapse;

/**
 * 折叠执行器配置。
 *
 * <p>折叠执行器用于将同一时刻内不同线程发起的相同或同组调用合并为一次批量调用，
 * 从而降低下游 I/O 次数与线程消耗。本配置声明批量收集与执行的相关参数。</p>
 *
 * <p>批量收集策略（{@link #collectingWaitTime}）：</p>
 * <ul>
 *   <li>小于 0：不做任何等待，立即发起批量调用</li>
 *   <li>等于 0（默认）：让出当前收集线程的时间片，待下次调度后再补收一次，兼顾实时性与批量化</li>
 *   <li>大于 0：等待指定毫秒数后再补收一次</li>
 * </ul>
 *
 * @author CH
 * @since 2026/09/03
 */
public class CollapseConfig {

    /**
     * 执行器名称/分组标识，同一名称的执行器共享同一收集器
     */
    private String name = "collapse";

    /**
     * 批量收集的最小阈值，收集到该数量的调用后立即执行批量逻辑
     */
    private int waitThreshold = 10;

    /**
     * 未达到阈值时的补收等待策略（单位毫秒），语义见类注释
     */
    private long collectingWaitTime = 0;

    /**
     * 是否启用虚拟线程（JDK 21+），启用后收集调度与批量执行默认运行在虚拟线程上
     */
    private boolean virtualThread = true;

    /**
     * 获取执行器名称。
     *
     * @return 执行器名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置执行器名称。
     *
     * @param name 执行器名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取批量收集的最小阈值。
     *
     * @return 批量收集的最小阈值
     */
    public int getWaitThreshold() {
        return waitThreshold;
    }

    /**
     * 设置批量收集的最小阈值。
     *
     * @param waitThreshold 批量收集的最小阈值
     */
    public void setWaitThreshold(int waitThreshold) {
        this.waitThreshold = waitThreshold;
    }

    /**
     * 获取未达到阈值时的补收等待时间（毫秒）。
     *
     * @return 补收等待时间（毫秒）
     */
    public long getCollectingWaitTime() {
        return collectingWaitTime;
    }

    /**
     * 设置未达到阈值时的补收等待时间（毫秒）。
     *
     * @param collectingWaitTime 补收等待时间（毫秒），小于 0 立即执行、等于 0 让出时间片、大于 0 等待指定毫秒
     */
    public void setCollectingWaitTime(long collectingWaitTime) {
        this.collectingWaitTime = collectingWaitTime;
    }

    /**
     * 是否启用虚拟线程。
     *
     * @return 是否启用虚拟线程
     */
    public boolean isVirtualThread() {
        return virtualThread;
    }

    /**
     * 设置是否启用虚拟线程。
     *
     * @param virtualThread 是否启用虚拟线程
     */
    public void setVirtualThread(boolean virtualThread) {
        this.virtualThread = virtualThread;
    }
}
