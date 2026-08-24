package com.chua.starter.datasync.source;

import com.chua.datasync.agent.support.DataSyncAgentSource;
import reactor.core.publisher.Flux;
import java.util.Map;

/**
 * HTTP Agent 数据同步 Source，从远程 Agent 拉取数据。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HttpAgentDataSyncSource implements DataSyncAgentSource {

    /** 数据源标识 */
    private final String sourceId;
    /** 输入标识 */
    private final String inputId;
    /** 代理标识 */
    private final String agentId;
    /** 代理服务地址 */
    private final String agentUrl;

    /**
     * 创建 HttpAgentDataSyncSource 实例
     * @param sourceId sourceId
     * @param String String
     * @param String String
     * @param String String
     */
    public HttpAgentDataSyncSource(String sourceId, String inputId, String agentId, String agentUrl) {
        this.sourceId = sourceId;
        this.inputId = inputId;
        this.agentId = agentId;
        this.agentUrl = agentUrl;
    }

    @Override
    /** SourceId */
    public String sourceId() {
        return sourceId;
    }

    @Override
    /** InputId */
    public String inputId() {
        return inputId;
    }

    @Override
    /** 读取 */
    public Flux<Map<String, Object>> read(Map<String, Object> params) {
        // TODO[@L53]: 从远程 Agent 拉取数据
        return Flux.empty();
    }

    @Override
    /** 关闭 */
    public void close() {
    }
}
