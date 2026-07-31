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

    private final String sourceId;
    private final String inputId;
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
