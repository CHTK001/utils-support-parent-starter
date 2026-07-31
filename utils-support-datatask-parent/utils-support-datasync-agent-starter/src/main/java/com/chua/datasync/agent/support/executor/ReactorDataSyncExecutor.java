package com.chua.datasync.agent.support.executor;

import com.chua.chronicle.support.dispatcher.ChronicleDispatcherProvider;
import com.chua.datasync.agent.support.DataSyncAgentException;
import com.chua.common.support.concurrent.dispatcher.ConsumerDispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
public class ReactorDataSyncExecutor {

    protected DispatcherProvider chronicleProvider;
    private final String agentId;
    private final boolean serverMode;

    public ReactorDataSyncExecutor(String agentId, boolean serverMode) {
        this.agentId = agentId;
        this.serverMode = serverMode;
    }

    public String getAgentId() {
        return agentId;
    }

    public boolean isServerMode() {
        return serverMode;
    }

    public void setDispatcherProvider(DispatcherProvider chronicleProvider) {
        this.chronicleProvider = chronicleProvider;
    }

    public void start() {
        if (chronicleProvider == null) {
            String path = System.getProperty("java.io.tmpdir") + "/chronicle-datasync";
            chronicleProvider = new ChronicleDispatcherProvider(
                    DispatcherConfig.builder().dataPath(path).build());
        }
        chronicleProvider.start();
    }

    public void stop() {
        if (chronicleProvider != null) {
            chronicleProvider.close();
        }
    }

    public void subscribe(String sinkId, Consumer<List<Map<String, Object>>> consumer) {
        chronicleProvider.subscribe(new ConsumerDispatcherDefinition<>(consumer, List.of(buildTopic(sinkId))));
    }

    public void publish(String sinkId, List<Map<String, Object>> data) {
        chronicleProvider.publish(buildTopic(sinkId), data);
    }

    private String buildTopic(String sinkId) {
        if (serverMode) {
            return "server-" + agentId;
        }
        return "consumer-" + agentId + "-" + sinkId;
    }
}
