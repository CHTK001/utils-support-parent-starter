package com.chua.datasync.agent.support.sink;

/**
 * TSV 数据同步 Sink，将数据写入 TSV 文件。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TsvDataSyncAgentSink extends DelimitedDataSyncAgentSink {

    /**
     * 构造 TSV Sink。
     *
     * @param sinkId Sink 实例 标识
     * @param filePath TSV 文件路径
     */
    public TsvDataSyncAgentSink(String sinkId, String filePath) {
        this(sinkId, filePath, true);
    }

    /**
     * 构造 TSV Sink。
     *
     * @param sinkId Sink 实例 标识
     * @param filePath TSV 文件路径
     * @param append 是否追加模式
     */
    public TsvDataSyncAgentSink(String sinkId, String filePath, boolean append) {
        super(sinkId, filePath, "\t", append);
    }
}
