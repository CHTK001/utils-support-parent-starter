package com.chua.remote.gateway.store;

import com.chua.remote.gateway.AccessCodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接入码存储 SPI 的默认实现（内存态）。
 *
 * <p>网关独立运行（无 Spring/数据库）时的兜底方案；接入码随进程生命周期。</p>
 *
 * @author AtomCode
 */
public class InMemoryAccessCodeStore implements AccessCodeStore {

    private final Map<String, AccessCodeInfo> store = new ConcurrentHashMap<>();

    @Override
    public List<AccessCodeInfo> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public AccessCodeInfo findByCode(String code) {
        return store.get(code);
    }

    @Override
    public void save(AccessCodeInfo info) {
        store.put(info.getCode(), info);
    }

    @Override
    public void delete(String code) {
        store.remove(code);
    }

    @Override
    public void recordAgent(String code, AccessCodeInfo.AgentAccessStat stat) {
        AccessCodeInfo info = store.get(code);
        if (info == null || stat == null) {
            return;
        }
        boolean exists = info.getAgents().stream()
                .anyMatch(a -> stat.getAgentId().equals(a.getAgentId()));
        if (!exists) {
            info.getAgents().add(stat);
            info.setUsedAgents(info.getAgents().size());
        }
    }

    @Override
    public void removeAgent(String code, String agentId) {
        AccessCodeInfo info = store.get(code);
        if (info == null) {
            return;
        }
        info.getAgents().removeIf(a -> agentId.equals(a.getAgentId()));
        info.setUsedAgents(info.getAgents().size());
    }
}
