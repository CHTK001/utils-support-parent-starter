package com.chua.common.support.network.discovery.peermesh;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.Discovery;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
* 磁盘持久化存储：读写 known_peers.json，写时加锁。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class DiskStore {

    /** 文件路径 */
    private final Path filePath;
    /** 锁 */
    private final ReentrantLock lock = new ReentrantLock();

    /**
    * 构造函数。
    *
    * @param peersFile 持久化文件路径，可为 null 表示禁用
     */
    public DiskStore(String peersFile) {
        if (peersFile != null && !peersFile.isBlank()) {
            this.filePath = Paths.get(peersFile);
        } else {
            this.filePath = null;
        }
    }

    /**
    * 加载磁盘上的节点列表。
    *
    * @return 节点列表（可能为空但非 null）
     */
    public List<NodeTable.NodeEntry> load() {
        if (filePath == null) {
            return Collections.emptyList();
        }
        lock.lock();
        try {
            if (!Files.isRegularFile(filePath)) {
                return Collections.emptyList();
            }
            try {
                String content = Files.readString(filePath);
                List<Discovery> discoList = Json.fromJsonToList(content, Discovery.class);
                if (discoList == null) {
                    return Collections.emptyList();
                }
                long now = System.currentTimeMillis();
                List<NodeTable.NodeEntry> result = new ArrayList<>();
                for (Discovery d : discoList) {
                    int epoch = 0;
                    if (d.getMetadata() != null) {
                        String epochStr = d.getMetadata().get("epoch");
                        if (epochStr != null) {
                            try {
                                epoch = Integer.parseInt(epochStr);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    result.add(new NodeTable.NodeEntry(d, now, epoch));
                }
                return result;
            } catch (RuntimeException e) {
                log.warn("磁盘文件解析失败，将清空重建: {}", e.getMessage());
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException ex) {
                    // ignore
                }
                return Collections.emptyList();
            }
        } catch (IOException e) {
            log.warn("读取磁盘文件失败: {}", e.getMessage());
            return Collections.emptyList();
        } finally {
            lock.unlock();
        }
    }

    /**
    * 保存节点列表到磁盘。
    *
    * @param entries 要保存的节点列表
     */
    public void save(List<NodeTable.NodeEntry> entries) {
        if (filePath == null) {
            return;
        }
        lock.lock();
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<Discovery> discoList = new ArrayList<>();
            for (NodeTable.NodeEntry entry : entries) {
                Discovery d = entry.getDiscovery();
                Map<String, String> meta = d.getMetadata() != null ? new HashMap<>(d.getMetadata()) : new HashMap<>();
                meta.put("epoch", Integer.toString(entry.getEpoch()));
                Discovery copy = Discovery.builder()
                        .id(d.getId())
                        .serverId(d.getServerId())
                        .protocol(d.getProtocol())
                        .timeout(d.getTimeout())
                        .weight(d.getWeight())
                        .host(d.getHost())
                        .port(d.getPort())
                        .uriSpec(d.getUriSpec())
                        .metadata(meta)
                        .env(d.getEnv())
                        .build();
                discoList.add(copy);
            }
            String json = Json.toJson(discoList);
            Path temp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("保存磁盘文件失败: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
    * 保存单个条目（追加模式，线程安全）。
    *
    * @param entry 节点条目
     */
    public void saveOne(NodeTable.NodeEntry entry) {
        if (filePath == null) {
            return;
        }
        lock.lock();
        try {
            List<NodeTable.NodeEntry> list = load();
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (Objects.equals(list.get(i).getDiscovery().getServerId(), entry.getDiscovery().getServerId())) {
                    list.set(i, entry);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                list.add(entry);
            }
            save(list);
        } catch (Exception e) {
            log.warn("保存单个条目失败: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
    * 删除磁盘文件（用于清理）。
     */
    public void delete() {
        if (filePath == null) {
            return;
        }
        lock.lock();
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.debug("删除磁盘文件失败: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }
}
