package com.chua.metrics.support;

import com.chua.common.support.lang.directory.DiffPolledDirectory;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
* 指标 服务实现（基于 polled目录）。
*
* <p>内部维护一个轮询目录实例，通过定时获取 native 内存数据，
* 解析为指标模型对象，触发 polled目录 的升级流程。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class MetricsService extends DiffPolledDirectory<MetricsService.SnapshotWrapper> implements AutoCloseable {

    /**
    * 快照包装类，用于 diffpolled目录 的泛型参数。
    *
    * @since 4.0.0.42
    * @author CH
    */
    @Data
    public static class SnapshotWrapper {
        /**
        * 原始指标快照
        */
        private MetricsSnapshot snapshot;

        /**
        * 时间戳
        */
        private long timestamp;
    }

    /**
    * JSON 解析器
    */
    private final MetricsJsonParser parser = new MetricsJsonParser();

    /**
    * native 库实例
    */
    private final MetricsNativeLibrary nativeLib;

    /**
    * 构造 指标服务 实例。
    *
    * @param intervalMs native 内部采样间隔（毫秒）
    * @return 指标服务的结果
    */
    public MetricsService(long intervalMs) {
        super("/metrics");
        this.nativeLib = Objects.requireNonNull(
                MetricsNativeLibrary.create(),
                "无法加载 metrics_native native 库"
        );
        this.nativeLib.start(intervalMs);
    }

    /**
    * 启动轮询，使用默认执行器。
    *
    * @param environment 轮询环境配置
    */
    public void start(DirectoryPollerEnvironment environment) {
        super.start(environment, null);
    }

    @Override
    /** 是否delegatedoperating系统 */
    public boolean isDelegatedOperatingSystem() {
        return false;
    }

    @Override
    /** 列表和modified */
    protected List<SnapshotWrapper> listAndModified(String path) {
        String json = nativeLib.poll();
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }

        MetricsSnapshot snapshot = parser.parse(json);
        if (snapshot == null) {
            return new ArrayList<>();
        }

        SnapshotWrapper wrapper = new SnapshotWrapper();
        wrapper.setSnapshot(snapshot);
        wrapper.setTimestamp(snapshot.getTimestamp());

        List<SnapshotWrapper> list = new ArrayList<>();
        list.add(wrapper);
        return list;
    }

    @Override
    /** 获取文件名 */
    protected String getFileName(SnapshotWrapper item) {
        return String.valueOf(item.getTimestamp());
    }

    @Override
    /** 获取Modified */
    protected Long getModified(SnapshotWrapper item) {
        return item.getTimestamp();
    }

    /**
    * 获取当前最新的指标快照。
    *
    * @return 指标快照，无数据时返回 空
    */
    public MetricsSnapshot getCurrentSnapshot() {
        List<SnapshotWrapper> dataList = listAndModified("/metrics");
        if (dataList.isEmpty()) {
            return null;
        }
        return dataList.getFirst().getSnapshot();
    }

    @Override
    /** 关闭 */
    public void close() {
        if (nativeLib != null) {
            nativeLib.close();
        }
        super.close();
    }
}
