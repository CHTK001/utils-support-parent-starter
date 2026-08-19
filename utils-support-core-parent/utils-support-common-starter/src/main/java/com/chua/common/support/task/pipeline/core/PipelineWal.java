package com.chua.common.support.task.pipeline.core;

import com.chua.common.support.lang.json.JacksonJsonProvider;
import com.chua.common.support.wal.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 流水线 WAL 持久化 — 基于 WAL 机制实现上下文持久化，防止突然崩溃导致数据丢失。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li><strong>上下文持久化</strong> — 每个节点执行后自动将上下文快照写入 WAL</li>
 *   <li><strong>崩溃恢复</strong> — 通过 WAL 回放恢复到最近检查点，从断点继续执行</li>
 *   <li><strong>自动销毁</strong> — 流水线正常完成或 stop 时自动清理 WAL 文件</li>
 * </ul>
 *
 * <p><strong>WAL 操作类型：</strong></p>
 * <ul>
 *   <li>{@link #OP_START} (1) — 流水线启动，payload = 序列化 input</li>
 *   <li>{@link #OP_NODE_COMPLETE} (2) — 节点完成，payload = 序列化 ContextSnapshot</li>
 *   <li>{@link #OP_CHECKPOINT} (3) — 检查点，payload = 空</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 在 PipelineBuilder 中启用 WAL
 * Pipeline pipeline = PipelineBuilder.newBuilder("order")
 *     .wal("./wal-data")            // 启用 WAL，指定存储目录
 *     .task("step1", ctx -> { ... }).taskEnd()
 *     .task("step2", ctx -> { ... }).taskEnd()
 *     .build();
 *
 * // 首次执行 — 等同于普通 execute，但每个节点完成后自动持久化
 * PipelineContext ctx = pipeline.execute("input");
 *
 * // 恢复执行 — 从 WAL 断点继续（无 WAL 数据时等同于 execute）
 * PipelineContext restored = pipeline.resume("input");
 *
 * // 终止并销毁 WAL
 * pipeline.stop();
 * }</pre>
 *
 * <p><strong>序列化解耦：</strong>WAL 的上下文快照序列化直接使用 Jackson
 * （{@code JacksonJsonProvider.getMapper()}），不经过 {@code Json} 静态门面，
 * 因此 WAL 文件格式不受 {@code JsonProvider} SPI 实现切换（Jackson/Gson/fastjson/fory）影响。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see WalLog
 * @see WalFactory
 * @see WalConfig
 */
public class PipelineWal implements AutoCloseable {

    /**
     * WAL 操作类型：流水线启动
     */
    public static final byte OP_START = 1;

    /**
     * WAL 操作类型：节点完成
     */
    public static final byte OP_NODE_COMPLETE = 2;

    /**
     * WAL 操作类型：检查点
     */
    public static final byte OP_CHECKPOINT = 3;

    /**
     * 流水线 ID（用作 WAL namespace）
     */
    private final String pipelineId;

    /**
     * WAL 日志实例
     */
    private WalLog walLog;

    /**
     * WAL 配置
     */
    private final WalConfig walConfig;

    /**
     * JSON 序列化使用的 ObjectMapper — 直接复用 {@link JacksonJsonProvider#getMapper()} 的全局配置
     * （含 java.time 支持、日期格式、统一门户注解适配），线程安全单例。
     */
    private static final ObjectMapper MAPPER = JacksonJsonProvider.getMapper();

    /**
     * 是否已打开
     */
    private boolean opened;

    /**
     * 上下文快照 — 用于 WAL 回放时恢复上下文状态
     *
     * <p>记录每个节点完成后的上下文关键状态，用于崩溃恢复。</p>
     */
    public static class ContextSnapshot {
        /** 当前节点 ID */
        public String currentNodeId;
        /** 下一节点 ID */
        public String nextNodeId;
        /** 当前数据（JSON 序列化形式） */
        public Object currentData;
        /** 执行历史 */
        public List<String> history;
        /** 当前动作 */
        public String action;
        /** 扩展属性（JSON 序列化形式） */
        public Map<String, Object> attributes;
        /** 节点输出（JSON 序列化形式） */
        public Map<String, Object> nodeOutputs;

        /** 默认构造器（JSON 反序列化用） */
        public ContextSnapshot() {}

        /** 从上下文创建快照 */
        public ContextSnapshot(PipelineContext<?> ctx) {
            this.currentNodeId = ctx.getCurrentNodeId();
            this.nextNodeId = ctx.getNextNodeId();
            this.currentData = ctx.getCurrentData();
            this.history = new ArrayList<>(ctx.getHistory());
            this.action = ctx.getAction() != null ? ctx.getAction().name() : Action.NEXT.name();
            this.attributes = new LinkedHashMap<>(ctx.getAttributes());
            this.nodeOutputs = new LinkedHashMap<>(ctx.getNodeOutputs());
        }
    }

    /**
     * 创建 PipelineWal 实例。
     *
     * @param pipelineId 流水线 ID
     * @param walDir     WAL 存储目录
     */
    public PipelineWal(String pipelineId, String walDir) {
        this.pipelineId = pipelineId;
        this.walConfig = WalConfig.builder()
                .walDir(Paths.get(walDir))
                .namespace(pipelineId)
                .impl(WalConfig.WalImpl.SIMPLE)
                .syncOnWrite(true)
                .build();
    }

    /**
     * 创建 PipelineWal 实例（使用自定义 WalConfig）。
     *
     * @param pipelineId 流水线 ID
     * @param walConfig  WAL 配置
     */
    public PipelineWal(String pipelineId, WalConfig walConfig) {
        this.pipelineId = pipelineId;
        this.walConfig = WalConfig.builder()
                .walDir(walConfig.walDir())
                .namespace(pipelineId)
                .impl(walConfig.impl())
                .syncOnWrite(walConfig.syncOnWrite())
                .fsyncBatchSize(walConfig.fsyncBatchSize())
                .fsyncBatchIntervalMs(walConfig.fsyncBatchIntervalMs())
                .useMemoryMap(walConfig.useMemoryMap())
                .maxSegmentBytes(walConfig.maxSegmentBytes())
                .maxRecordsPerSegment(walConfig.maxRecordsPerSegment())
                .keepCheckpointedSegments(walConfig.keepCheckpointedSegments())
                .magic(walConfig.magic())
                .build();
    }

    /**
     * 打开 WAL 日志。
     *
     * @throws IOException IO 异常
     */
    public void open() throws IOException {
        if (!opened) {
            this.walLog = WalFactory.open(walConfig);
            this.opened = true;
        }
    }

    /**
     * 记录流水线启动事件。
     *
     * @param input 输入数据
     * @param <T>   数据类型
     * @throws IOException IO 异常
     */
    public <T> void appendStart(T input) throws IOException {
        ensureOpen();
        byte[] payload = serializeObject(input);
        walLog.append(OP_START, payload);
    }

    /**
     * 记录节点完成事件 — 持久化当前上下文快照。
     *
     * @param ctx 流水线上下文
     * @throws IOException IO 异常
     */
    public void appendNodeComplete(PipelineContext<?> ctx) throws IOException {
        ensureOpen();
        ContextSnapshot snapshot = new ContextSnapshot(ctx);
        byte[] payload = serializeObject(snapshot);
        walLog.append(OP_NODE_COMPLETE, payload);
    }

    /**
     * 标记检查点 — 表示当前上下文已成功持久化到某个节点。
     *
     * <p>检查点之后的 WAL 记录在回放时会被跳过（已持久化完成）。</p>
     *
     * @throws IOException IO 异常
     */
    public void markCheckpoint() throws IOException {
        ensureOpen();
        walLog.append(OP_CHECKPOINT, new byte[0]);
        walLog.markCheckpoint(walLog.currentLsn());
    }

    /**
     * 从 WAL 回放恢复上下文状态。
     *
     * <p>回放流程：</p>
     * <ol>
     *   <li>加载检查点元信息</li>
     *   <li>从检查点之后回放所有记录</li>
     *   <li>重建最后一个 NODE_COMPLETE 记录对应的上下文状态</li>
     * </ol>
     *
     * @param input 原始输入数据（用于创建新上下文）
     * @param <T>   数据类型
     * @return 恢复的上下文，无 WAL 数据时返回 null
     * @throws IOException IO 异常
     */
    public <T> PipelineContext<T> replay(T input) throws IOException {
        ensureOpen();

        // 保存最后一个节点完成的快照
        ContextSnapshot lastSnapshot = null;
        Object originalInput = null;

        WalReplayResult result = walLog.replay((lsn, op, payload) -> {
            // 回放处理在循环外通过 result.records 进行
            return true;
        });

        // 遍历回放记录，找到最后的快照
        for (WalRecord record : result.records()) {
            switch (record.op()) {
                case OP_START:
                    originalInput = deserializeObject(record.payload());
                    break;
                case OP_NODE_COMPLETE:
                    ContextSnapshot snapshot = deserializeSnapshot(record.payload());
                    if (snapshot != null) {
                        lastSnapshot = snapshot;
                    }
                    break;
                case OP_CHECKPOINT:
                    // 检查点记录，无需特殊处理
                    break;
                default:
                    break;
            }
        }

        if (lastSnapshot == null) {
            return null;
        }

        // 从快照恢复上下文
        @SuppressWarnings("unchecked")
        T restoredInput = originalInput != null ? (T) originalInput : input;
        PipelineContext<T> ctx = new PipelineContext<>(pipelineId, restoredInput);

        // 恢复快照状态
        ctx.setCurrentNodeId(lastSnapshot.currentNodeId);
        ctx.setNextNodeId(lastSnapshot.nextNodeId);
        ctx.setCurrentData((T) lastSnapshot.currentData);
        if (lastSnapshot.history != null) {
            ctx.getHistory().clear();
            ctx.getHistory().addAll(lastSnapshot.history);
        }
        if (lastSnapshot.action != null) {
            ctx.setAction(Action.valueOf(lastSnapshot.action));
        }
        if (lastSnapshot.attributes != null) {
            ctx.getAttributes().clear();
            ctx.getAttributes().putAll(lastSnapshot.attributes);
        }
        if (lastSnapshot.nodeOutputs != null) {
            ctx.getNodeOutputs().clear();
            ctx.getNodeOutputs().putAll(lastSnapshot.nodeOutputs);
        }

        return ctx;
    }

    /**
     * 检查是否存在 WAL 数据（可用于判断是否需要恢复）。
     *
     * @return true 表示存在 WAL 数据
     */
    public boolean hasWalData() {
        if (!opened || walLog == null) {
            return false;
        }
        try {
            CheckpointMeta checkpoint = walLog.loadCheckpoint();
            // 有检查点或当前 LSN > 0 表示有数据
            return !checkpoint.isEmpty() || walLog.currentLsn() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 关闭 WAL 日志（正常完成时调用，保留 WAL 文件）。
     */
    @Override
    public void close() {
        if (opened && walLog != null) {
            try {
                walLog.close();
            } catch (IOException ignored) {
                // 关闭时忽略 IO 异常
            }
            opened = false;
        }
    }

    /**
     * 销毁 WAL — 关闭日志并删除所有 WAL 文件。
     *
     * <p>用于 stop 场景：流水线被强制终止时，清理所有持久化数据。</p>
     */
    public void destroy() {
        close();
        // 删除 WAL 目录下的 namespace 相关文件
        try {
            Path walDir = walConfig.walDir();
            if (Files.exists(walDir)) {
                Files.walk(walDir)
                        .filter(p -> p.getFileName().toString().startsWith(pipelineId))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException ignored) {
            // 销毁时忽略 IO 异常
        }
    }

    /**
     * 获取 WAL 存储目录。
     *
     * @return WAL 目录路径
     */
    public Path getWalDir() {
        return walConfig.walDir();
    }

    /**
     * 获取流水线 ID。
     *
     * @return 流水线 ID
     */
    public String getPipelineId() {
        return pipelineId;
    }

    /**
     * 是否已打开。
     *
     * @return true 表示已打开
     */
    public boolean isOpened() {
        return opened;
    }

    // ==================== 内部方法 ====================

    private void ensureOpen() throws IOException {
        if (!opened) {
            open();
        }
    }

    /**
     * 序列化对象为字节数组。
     */
    private byte[] serializeObject(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        try {
            String json = MAPPER.writeValueAsString(obj);
            return json.getBytes("UTF-8");
        } catch (Exception e) {
            // 序列化失败时返回空数组
            return new byte[0];
        }
    }

    /**
     * 从字节数组反序列化对象。
     */
    private Object deserializeObject(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            String json = new String(payload, "UTF-8");
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 反序列化上下文快照。
     */
    private ContextSnapshot deserializeSnapshot(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            String json = new String(payload, "UTF-8");
            return MAPPER.readValue(json, ContextSnapshot.class);
        } catch (Exception e) {
            return null;
        }
    }
}