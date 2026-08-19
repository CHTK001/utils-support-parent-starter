package com.chua.starter.datasync.source;

import com.chua.datasync.agent.support.DataSyncAgentSource;
import reactor.core.publisher.Flux;
import java.util.Map;

/**
 * 文件系统数据同步 Source，从本地文件读取数据。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FileSystemDataSyncSource implements DataSyncAgentSource {

    /** 数据源标识 */
    /** 来源ID */
    private final String sourceId;
    /** 输入标识 */
    /** 输入ID */
    private final String inputId;
    /** 文件路径 */
    private final String filePath;

    public FileSystemDataSyncSource(String sourceId, String inputId, String filePath) {
        this.sourceId = sourceId;
        this.inputId = inputId;
        this.filePath = filePath;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String inputId() {
        return inputId;
    }

    @Override
    public Flux<Map<String, Object>> read(Map<String, Object> params) {
        // TODO: 从文件系统读取数据
        return Flux.empty();
    }

    @Override
    public void close() {
    }
}
