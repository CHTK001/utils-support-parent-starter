package com.chua.metrics.support;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.directory.DiffPolledDirectory;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
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
        MetricsNativeLibrary library = MetricsNativeLibrary.create();
        if (library == null) {
            log.warn("metrics 原生库不可用，指标采集降级为空实现；请自行添加依赖 com.chua:utils-support-native-metrics:4.0.0.43");
            this.nativeLib = null;
            return;
        }
        this.nativeLib = library;
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

    /**
     * 是否delegatedoperating系统
    */
    @Override
    public boolean isDelegatedOperatingSystem() {
        return false;
    }

    /**
     * 列表和modified
    */
    @Override
    protected List<SnapshotWrapper> listAndModified(String path) {
        if (nativeLib == null) {
            return Wraps.list();
        }
        String json = nativeLib.poll();
        if (json == null || json.isEmpty()) {
            return Wraps.list();
        }

        MetricsSnapshot snapshot = parser.parse(json);
        if (snapshot == null) {
            return Wraps.list();
        }

        SnapshotWrapper wrapper = new SnapshotWrapper();
        wrapper.setSnapshot(snapshot);
        wrapper.setTimestamp(snapshot.getTimestamp());

        List<SnapshotWrapper> list = Wraps.list();
        list.add(wrapper);
        return list;
    }

    /**
     * 获取文件名
    */
    @Override
    protected String getFileName(SnapshotWrapper item) {
        return String.valueOf(item.getTimestamp());
    }

    /**
     * 获取Modified
    */
    @Override
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

    /**
     * 关闭
    */
    @Override
    public void close() {
        if (nativeLib != null) {
            nativeLib.close();
        }
        super.close();
    }
}
