package com.chua.datasync.agent.support.source;

/**
 * TSV 数据同步 Source，从 TSV 文件读取数据。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TsvDataSyncAgentSource extends DelimitedDataSyncAgentSource {

    /**
     * 构造 TSV Source。
     *
     * @param sourceId Source 实例 ID
     * @param inputId 输入标识
     * @param filePath TSV 文件路径
     */
    public TsvDataSyncAgentSource(String sourceId, String inputId, String filePath) {
        super(sourceId, inputId, filePath, "\t");
    }
}
