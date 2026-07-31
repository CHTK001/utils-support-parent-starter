package com.chua.datasync.agent.support.sink;

/**
 * CSV 数据同步 Sink，将数据写入 CSV 文件。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CsvDataSyncAgentSink extends DelimitedDataSyncAgentSink {

    /**
     * 构造 CSV Sink。
     *
     * @param sinkId Sink 实例 ID
     * @param filePath CSV 文件路径
     */
    public CsvDataSyncAgentSink(String sinkId, String filePath) {
        this(sinkId, filePath, true);
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
