package com.chua.metrics.support;

import lombok.Data;

import java.util.List;

/**
 * 系统指标快照数据模型，包含所有采集的系统指标。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class MetricsSnapshot {
    /**
     * 时间戳（Unix 时间戳，毫秒）
     */
    private long timestamp;

    /**
     * CPU 核心指标列表
     */
    private List<CpuCore> cpuCores;

    /**
     * 内存插槽指标列表
     */
    private List<MemorySlot> memorySlots;

    /**
     * 交换分区总容量（字节）
     */
    private long swapTotal;

    /**
     * 交换分区已用容量（字节）
     */
    private long swapUsed;

    /**
     * 磁盘信息列表
     */
    private List<DiskInfo> disks;

    /**
     * 磁盘 IO 列表
     */
    private List<DiskIo> diskIo;

    /**
     * 网络接口列表
     */
    private List<NetworkInterface> networks;

    /**
     * 进程信息列表（按 CPU 使用率排序的前 N 条）
     */
    private List<ProcessInfo> processes;

    /**
     * GPU 信息列表
     */
    private List<GpuInfo> gpus;

    /**
     * 电池信息列表
     */
    private List<BatteryInfo> batteries;

    /**
     * 系统负载信息
     */
    private SystemLoad load;
}
