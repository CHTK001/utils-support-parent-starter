package com.chua.datasync.agent.support.sink;

/**
 * CSV 数据同步 Sink，将数据写入 CSV 文件。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CsvDataSyncAgentSink extends DelimitedDataSyncAgentSink {

    /**
     * 构造 CSV Sink（覆盖模式，重复导出不累积历史数据）。
     *
     * <p>如需追加写入（如周期性同步落盘），请使用三参构造显式传入 {@code append=true}。</p>
     *
     * @param sinkId Sink 实例 ID
     * @param filePath CSV 文件路径
     */
    public CsvDataSyncAgentSink(String sinkId, String filePath) {
        this(sinkId, filePath, false);
    }

    /**
     * 构造 CSV Sink。
     *
     * @param sinkId Sink 实例 ID
     * @param filePath CSV 文件路径
     * @param append 是否追加模式
     */
    public CsvDataSyncAgentSink(String sinkId, String filePath, boolean append) {
        super(sinkId, filePath, ",", append);
    }
}
