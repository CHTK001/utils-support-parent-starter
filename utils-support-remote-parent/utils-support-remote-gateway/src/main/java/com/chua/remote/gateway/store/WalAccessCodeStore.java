package com.chua.remote.gateway.store;

import com.chua.common.support.datasource.wal.KvWalStoreSystem;
import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.serialize.JsonSerializer;
import com.chua.remote.gateway.AccessCodeInfo;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 接入码存储 SPI 的 WAL（预写日志）实现——纯 JDK 文件持久化（重启不丢，不依赖 spring/数据库）。
 *
 * <p>基于 common 的 {@link KvWalStoreSystem}（WAL 分片日志落盘 + 启动 {@code rebuildIndex} 重放恢复）：</p>
 * <ul>
 *   <li>KV 语义：固定 key {@code access_codes} 存全部接入码数组的 JSON 字节（接入码数量少——全量可接受）</li>
 *   <li>写：{@code put}（WAL 追加落盘 + 内存索引）；读：{@code getBytes}（重启后由 WAL 重放恢复）</li>
 *   <li>注意：KvWal 的 {@code get/range/contains} 尚未实现（返回空）——本实现仅使用已实现的 {@code put/getBytes/rebuildIndex}</li>
 * </ul>
 *
 * @author AtomCode
 */
public class WalAccessCodeStore implements AccessCodeStore {

    /** 全部接入码的固定存储 key */
    private static final String ALL_KEY = "access_codes";

    /** WAL 存储（KV：key → AccessCodeInfo[] JSON 字节） */
    private final KvWalStoreSystem wal;

    /** 接入码数组 JSON 序列化器 */
    private final JsonSerializer<AccessCodeInfo[]> serializer = new JsonSerializer<>(AccessCodeInfo[].class);

    public WalAccessCodeStore() {
        String dir = System.getProperty("user.dir", ".");
        try {
            this.wal = new KvWalStoreSystem(WalStoreConfig.builder()
                    .baseDir(Paths.get(dir.replace('\\', '/'), ".remote_data", "remote-wal"))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("WAL 接入码存储初始化失败", e);
        }
    }

    /** 读全量（WAL 恢复的内存索引） */
    private List<AccessCodeInfo> readAll() {
        try {
            Optional<byte[]> value = wal.getBytes(ALL_KEY);
            if (value.isPresent() && value.get().length > 0) {
                AccessCodeInfo[] arr = serializer.deserialize(value.get());
                if (arr != null) {
                    return new ArrayList<>(Arrays.asList(arr));
                }
            }
        } catch (Exception ignored) {
            // 读取失败——返回空列表
        }
        return new ArrayList<>();
    }

    /** 写全量（WAL 追加落盘——单 key 覆盖语义） */
    private void writeAll(List<AccessCodeInfo> all) {
        try {
            wal.put(ALL_KEY, serializer.serialize(all.toArray(new AccessCodeInfo[0])));
        } catch (Exception e) {
            throw new IllegalStateException("WAL 写入失败", e);
        }
    }

    @Override
    public List<AccessCodeInfo> findAll() {
        return readAll();
    }

    @Override
    public AccessCodeInfo findByCode(String code) {
        if (code == null) {
            return null;
        }
        for (AccessCodeInfo info : readAll()) {
            if (code.equals(info.getCode())) {
                return info;
            }
        }
        return null;
    }

    @Override
    public void save(AccessCodeInfo info) {
        if (info == null || info.getCode() == null) {
            return;
        }
        List<AccessCodeInfo> all = readAll();
        all.removeIf(i -> info.getCode().equals(i.getCode()));
        all.add(info);
        writeAll(all);
    }

    @Override
    public void delete(String code) {
        if (code == null) {
            return;
        }
        List<AccessCodeInfo> all = readAll();
        all.removeIf(i -> code.equals(i.getCode()));
        writeAll(all);
    }

    @Override
    public void recordAgent(String code, AccessCodeInfo.AgentAccessStat stat) {
        if (code == null || stat == null) {
            return;
        }
        List<AccessCodeInfo> all = readAll();
        AccessCodeInfo info = all.stream()
                .filter(i -> code.equals(i.getCode()))
                .findFirst().orElse(null);
        if (info == null) {
            return;
        }
        // 按 agentId 去重：存在则刷新 IP/时间，否则新增
        AccessCodeInfo.AgentAccessStat existing = info.getAgents().stream()
                .filter(a -> stat.getAgentId().equals(a.getAgentId()))
                .findFirst().orElse(null);
        if (existing == null) {
            info.getAgents().add(stat);
            info.setUsedAgents(info.getAgents().size());
        } else {
            existing.setIp(stat.getIp());
            existing.setTime(stat.getTime());
        }
        writeAll(all);
    }

    @Override
    public void removeAgent(String code, String agentId) {
        if (code == null || agentId == null) {
            return;
        }
        List<AccessCodeInfo> all = readAll();
        AccessCodeInfo info = all.stream()
                .filter(i -> code.equals(i.getCode()))
                .findFirst().orElse(null);
        if (info == null) {
            return;
        }
        info.getAgents().removeIf(a -> agentId.equals(a.getAgentId()));
        info.setUsedAgents(info.getAgents().size());
        writeAll(all);
    }
}
