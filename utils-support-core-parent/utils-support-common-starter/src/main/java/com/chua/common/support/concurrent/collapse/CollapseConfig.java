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
    * 批量收集的最小阈值，收集到该数量的调用后立即执行批量逻辑；0 表示不等待（每批立即执行），负值非法
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
    * 是否整批合并执行（不做入参分组）。
    *
    * <p>true：同一收集批次内的全部调用合并为一组，执行一次批量逻辑，
    * 配合 {@link CollapseResultMapper} 按调用者拆分回填各自结果（单次调用携带集合入参的场景）；</p>
    *
    * <p>false（默认）：按入参 equals 分组，相同入参的调用合并执行一次并广播结果。</p>
    */
    private boolean mergeAll = false;

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
    * @param waitThreshold 批量收集的最小阈值，必须大于等于 0（0 表示不等待、每批立即执行）
    */
    public void setWaitThreshold(int waitThreshold) {
        if (waitThreshold < 0) {
            throw new IllegalArgumentException("waitThreshold must be >= 0: " + waitThreshold);
        }
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

    /**
    * 是否整批合并执行。
    *
    * @return 是否整批合并执行
    */
    public boolean isMergeAll() {
        return mergeAll;
    }

    /**
    * 设置是否整批合并执行。
    *
    * @param mergeAll 是否整批合并执行
    */
    public void setMergeAll(boolean mergeAll) {
        this.mergeAll = mergeAll;
    }
}
