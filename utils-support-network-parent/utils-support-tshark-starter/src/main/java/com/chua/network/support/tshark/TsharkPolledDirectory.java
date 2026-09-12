package com.chua.network.support.tshark;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.lang.directory.executor.VirtualThreadPollerExecutor;
import com.chua.common.support.network.protocol.ProtocolRestorer;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.lang.algorithm.crypto.Hex;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
* tshark 数据包轮询目录。
*
* <p>基于 {@link DiffPolledDirectory} 的差异对比型目录监听器，监听 TShark 抓包输出目录。</p>
* <p>新文件出现时自动调用 TShark 进程将其转换为 JSON 格式，并使用 {@link PacketParserService}
* 解析为 {@link PacketRecord} 列表，通过 {@link Consumer} 回调给业务方。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* TsharkPolledDirectory poller = new TsharkPolledDirectory("/var/captures");
* poller.setPacketListener(records -> records.forEach(r -> log.info("parsed: {}", r.info())));
* poller.addListener(new SimplePolledListener());
*
* DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(
*     Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY, WatcherEvent.DELETE), 5, TimeUnit.SECONDS);
* poller.start(env);
* }</pre>* poller.启动(env);
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class TsharkPolledDirectory implements PolledDirectory {

    /**
    * 对象 映射器
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /**
    * RESTORERS
     */
    private static final List<ProtocolRestorer> RESTORERS;

    static {
        RESTORERS = ServiceProvider.of(ProtocolRestorer.class).collect();
    }

    /**
    * 被监听的目录路径
     */
    private final String listenPath;

    /**
    * 文件名 -> 最后修改时间戳（毫秒）的快照缓存
     */
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    /**
    * 事件监听器列表
     */
    private final List<PolledListener> listeners = new CopyOnWriteArrayList<>();

    /**
    * 数据包记录回调消费者
     */
    private final AtomicReference<Consumer<List<PacketRecord>>> packetListener = new AtomicReference<>();

    /**
    * tshark 可执行文件路径（默认 "tshark"，可通过 {@link #setTsharkBinary(String)} 自定义）
     */
    private final AtomicReference<String> tsharkBinary = new AtomicReference<>("tshark");

    /**
    * 环境配置
     */
    private DirectoryPollerEnvironment environment;

    /**
    * 当前运行中的轮询执行器
     */
    private DirectoryPollerExecutor executor;

    /**
    * 构造 tshark 轮询目录监听器。
    *
    * @param listenPath 被监听的目录路径
     */
    public TsharkPolledDirectory(String listenPath) {
        this.listenPath = listenPath;
    }

    /**
    * 设置数据包记录回调。
    *
    * <p>新文件或被修改的文件会通过 TShark 解析，结果通过此回调暴露。</p>
    *
    * @param listener 解析结果消费者
     */
    public void setPacketListener(Consumer<List<PacketRecord>> listener) {
        this.packetListener.set(listener);
    }

    /**
    * 设置 tshark 可执行文件路径或名称。
    *
    * <p>默认值为 {@code "tshark"}（依赖系统 PATH）。Windows 用户如未将 tshark 加入 PATH，
    * 可传入完整路径如 {@code "C:/Program Files/Wireshark/tshark.exe"}。</p>
    *
    * @param binary tshark 可执行文件路径或名称
     */
    public void setTsharkBinary(String binary) {
        if (binary != null && !binary.isBlank()) {
            this.tsharkBinary.set(binary);
        }
    }

    @Override
    /** 添加监听器 */
    public void addListener(PolledListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 开始 */
    public void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor) {
        this.environment = environment;

        ensureDirExists(listenPath);

        // 初始化缓存：扫描当前已有文件
        File dir = new File(listenPath);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".pcap")) {
                    cache.put(f.getName(), f.lastModified());
                }
            }
        }

        // 委托给默认执行器
        if (executor == null) {
            executor = new VirtualThreadPollerExecutor(this, environment);
        }
        this.executor = executor;
        executor.start();
        log.info("TShark 轮询目录已启动: {}", listenPath);
    }

    @Override
    /** Upgrade */
    public void upgrade() {
        File dir = new File(listenPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }

        Map<String, Long> currentSnap = new HashMap<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".pcap")) {
                    currentSnap.put(f.getName(), f.lastModified());
                }
            }
        }

        // 检测新增与修改
        for (Map.Entry<String, Long> entry : currentSnap.entrySet()) {
            String name = entry.getKey();
            Long prev = cache.get(name);
            if (prev == null) {
                cache.put(name, entry.getValue());
                fire(WatcherEvent.CREATE, name);
                handleNewOrModified(name);
            } else if (!entry.getValue().equals(prev)) {
                cache.put(name, entry.getValue());
                fire(WatcherEvent.MODIFY, name);
                handleNewOrModified(name);
            }
        }

        // 检测删除
        for (String name : new HashSet<>(cache.keySet())) {
            if (!currentSnap.containsKey(name)) {
                cache.remove(name);
                fire(WatcherEvent.DELETE, name);
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (executor != null) {
            executor.close();
        }
        cache.clear();
        listeners.clear();
        log.info("TShark 轮询目录已停止: {}", listenPath);
    }

    // ==================== 私有方法 ====================

    /**
    * 确保被监听的目录存在（不存在则创建）。
    *
    * @param path 目录路径
     */
    private void ensureDirExists(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                log.info("创建监听目录: {}", path);
            } else {
                log.warn("无法创建监听目录: {}", path);
            }
        }
    }

    /**
    * 处理新增或修改的 pcap 文件。
    *
    * <p>调用 TShark 解析为 JSON，逐行解析为 {@link PacketRecord} 列表，
    * 触发 数据包监听器 回调（即使解析失败也会以空列表回调一次）。</p>
    *
    * @param fileName 文件名
     */
    private void handleNewOrModified(String fileName) {
        List<PacketRecord> records = List.of();
        try {
            records = parsePcapFile(fileName);
        } catch (Exception e) {
            log.warn("解析 pcap 文件失败: {}", fileName, e);
        }
        try {
            Consumer<List<PacketRecord>> callback = packetListener.get();
            if (callback != null) {
                callback.accept(records);
            }
        } catch (Exception e) {
            log.error("packetListener 回调异常: {}", fileName, e);
        }
    }

    /**
    * 调用 tshark 进程将 pcap 文件解析为 JSON，并逐包转换为 {@link PacketRecord}。
    *
    * @param fileName pcap 文件名
    * @return 解析出的数据包记录列表
     */
    private List<PacketRecord> parsePcapFile(String fileName) throws Exception {
        List<PacketRecord> records = new ArrayList<>();
        File pcap = new File(listenPath, fileName);

        List<String> command = new ArrayList<>();
        command.add(tsharkBinary.get());
        command.add("-r");
        command.add(pcap.getAbsolutePath());
        command.add("-T");
        command.add("json");
        command.add("-x");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder fullOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fullOutput.append(line);
            }
        }
        process.waitFor();

        String json = fullOutput.toString().trim();
        if (!json.startsWith("[")) {
            return records;
        }
        List<?> packets = com.chua.common.support.lang.json.Json.fromJson(json, List.class);
        for (Object obj : packets) {
            String packetJson = com.chua.common.support.lang.json.Json.toJSONString(obj);
            PacketRecord record = PacketParserService.parse(packetJson);
            if (record == null) {
                continue;
            }

            String restoredText = tryRestoreProtocol(packetJson, record);
            if (restoredText != null && !restoredText.isEmpty()) {
                record = new PacketRecord(
                        record.sourceIp(), record.destinationIp(),
                        record.sourcePort(), record.destinationPort(),
                        record.protocol(), record.length(),
                        record.info(), record.rawData(),
                        record.lifecycleJson(), restoredText
                );
            }
            records.add(record);
        }
        log.debug("解析 pcap {} 完成，共 {} 包", fileName, records.size());
        return records;
    }

    @SuppressWarnings("unchecked")
    /**
    * 尝试restore协议
    *
    * @param packetJson 数据包json
    * @param record record
    * @return 尝试restore协议的结果
     */
    private static String tryRestoreProtocol(String packetJson, PacketRecord record) {
        if (RESTORERS.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(packetJson, new TypeReference<>() {});
            Map<String, Object> source = (Map<String, Object>) root.get("_source");
            if (source == null) {
                return null;
            }
            Map<String, Object> layers = (Map<String, Object>) source.get("layers");
            if (layers == null || layers.isEmpty()) {
                return null;
            }

            byte[] rawBytes = extractRawBytes(layers);
            if (rawBytes == null || rawBytes.length == 0) {
                return null;
            }

            for (ProtocolRestorer restorer : RESTORERS) {
                try {
                    if (restorer.canRestore(layers, rawBytes)) {
                        return restorer.restore(layers, rawBytes);
                    }
                } catch (Exception e) {
                    log.debug("还原器 {} 跳过: {}", restorer.getProtocolName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("协议还原失败: {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    /**
    * extractrawbytes
    *
    * @param layers layers
    * @return extractRawBytes的结果
     */
    private static byte[] extractRawBytes(Map<String, Object> layers) {
 // tshark -T json -x 输出 帧_raw: [hex, 偏移量, 长度]，第一个元素是 hex 字符串
        Object frameRaw = findDeep(layers, "frame_raw");
        if (frameRaw instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String hex && !hex.isEmpty()) {
                try {
                    return Hex.decodeHex(hex.replaceAll(":", ""));
                } catch (Exception ignored) {
                }
            }
        }
        // 兼容旧 tshark 字段名
        List<String> candidates = List.of(
                "frame.raw_data", "frame.data", "data.data",
                "tcp.payload", "udp.payload", "http.file_data",
                "dns.txt", "irc.request", "irc.response"
        );
        for (String key : candidates) {
            Object value = findDeep(layers, key);
            if (value instanceof String hex && !hex.isEmpty()) {
                try {
                    return Hex.decodeHex(hex.replaceAll(":", ""));
                } catch (Exception ignored) {
                }
            }
            if (value instanceof byte[] bytes) {
                return bytes;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    /**
    * 查找Deep
    *
    * @param map 映射
    * @param key 键
    * @return findDeep的结果
     */
    private static Object findDeep(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        if (map.containsKey(key)) {
            return map.get(key);
        }
        for (Object value : map.values()) {
            if (value instanceof Map) {
                Object found = findDeep((Map<String, Object>) value, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
    * 向所有监听器分发事件。
    *
    * @param event    事件类型
    * @param fileName 触发事件的文件名
     */
    private void fire(WatcherEvent event, String fileName) {
        EventObserver observer = EventObserver.builder()
                .currentPath(listenPath)
                .triggerFile(fileName)
                .eventType(event)
                .build();

        for (PolledListener l : listeners) {
            try {
                switch (event) {
                    case CREATE -> l.onCreate(event, observer);
                    case MODIFY -> l.onModify(event, observer);
                    case DELETE -> l.onDelete(event, observer);
                    case OVERFLOW -> l.onOverflow(event, observer);
                    default -> {
                        // noop
                    }
                }
            } catch (Exception e) {
                log.error("监听器分发异常: {}", event, e);
            }
        }
    }

    /**
    * 获取当前快照的文件名集合。
    *
    * @return 当前缓存的文件名集合
     */
    public Set<String> snapshot() {
        return new HashSet<>(cache.keySet());
    }
}