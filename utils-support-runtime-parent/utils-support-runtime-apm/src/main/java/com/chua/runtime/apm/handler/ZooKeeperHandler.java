package com.chua.runtime.apm.handler;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
   * ZooKeeper 应用层 处理器 — 拦截 ZK 客户端调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.apache.zookeeper.ZooKeeper}（主要 API 入口）</li>
 *   <li>{@code org.apache.curator.framework.CuratorFramework}（Curator 适配层）</li>
 * </ul>
 *
 * <p>采用零依赖策略：</p>
 * <ul>
 *   <li>不引入 zookeeper / curator 编译期依赖</li>
 *   <li>通过 {@link RuntimeSpy#registerInterceptor(String, String, String, InterceptPoint, RuntimeSpy.Interceptor)} 注册精确规则，
   * spy转换 会按需 retransform 已加载的 ZK 类（若 类路径 缺失则不生效）</li>
 *   <li>从 ctx 参数（反射调用现场）解析 path/znode/scheme</li>
 * </ul>
 *
 * <p>应用语义传输记录会同时写入 {@link TransmissionHandler#records} 与
 * {@link DependencyGraphHandler}（source = 调用方，target = ZK 集群）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ZooKeeperHandler implements Plugin, RuntimeSpy.Interceptor {
    /**
      * 日志
     */
    private static final Logger LOG = Logger.getLogger(ZooKeeperHandler.class.getName());

    /**
      * org.Apache.ZooKeeper.ZooKeeper 类内部名
     */
    private static final String ZK_CLASS = "org/apache/zookeeper/ZooKeeper";

    /**
      * curator框架 类内部名
     */
    private static final String CURATOR_CLASS = "org/apache/curator/framework/CuratorFramework";

    /**
     * 最大记录数
     */
    private static final int MAX_RECORDS = 5000;

    /**
     * records
     */
    private final com.chua.runtime.apm.handler.BoundedRecordList<TransmissionRecord> records;
    /**
      * 已启用
     */
    private boolean enabled;
    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    /** 创建 zookeeper处理器 实例 */
    public ZooKeeperHandler() {
        this.records = new com.chua.runtime.apm.handler.BoundedRecordList<>(10000);
        this.started = new AtomicBoolean(false);
    }

    @Override
    /** 名称 */
    public String name() {
        return "zk-handler";
    }

    @Override
    /** 版本 */
    public String version() {
        return "1.0.0";
    }

    @Override
    /** 初始化 */
    public void init(PluginContext context) throws Exception {
        this.enabled = "true".equals(context.getProperty("zk.enabled", "true"));
        LOG.log(Level.INFO, String.format("ZooKeeperHandler 初始化完成，启用状态: %s", enabled));
    }

    @Override
    /** 开始 */
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        registerInterceptors();
        LOG.log(Level.INFO, "ZooKeeperHandler 启动完成，应用层 ZK 拦截已注册");
    }

    @Override
    /** 停止 */
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        LOG.log(Level.INFO, "ZooKeeperHandler 停止");
    }

    @Override
    /** 状态 */
    public String status() {
        return String.format("ZooKeeperHandler[enabled=%s, records=%d]", enabled, records.size());
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 注册精确插桩规则：ZooKeeper 主 API 方法 + 关键构造器。
     */
    private void registerInterceptors() {
 // 构造器：暴露 连接字符串（ZK 集群地址）
        RuntimeSpy.registerInterceptor(ZK_CLASS, "<init>",
                "(Ljava/lang/String;ILorg/apache/zookeeper/Watcher;JLorg/apache/zookeeper/client/ZKClientConfig;)V",
                InterceptPoint.ENTRY, this);
        // 节点操作
        String[][] methods = {
                {"getData", "(Ljava/lang/String;ZLorg/apache/zookeeper/Watcher;Lorg/apache/zookeeper/data/Stat;)Lorg/apache/zookeeper/data/Stat;)" +
                        "([B"},
                {"getData", "(Ljava/lang/String;ZLorg/apache/zookeeper/Watcher;)Lorg/apache/zookeeper/data/Stat;)" +
                        "([B"},
                {"exists", "(Ljava/lang/String;Z)Lorg/apache/zookeeper/data/Stat;"},
                {"exists", "(Ljava/lang/String;ZLorg/apache/zookeeper/Watcher;Lorg/apache/zookeeper/data/Stat;)Lorg/apache/zookeeper/data/Stat;"},
                {"create", "(Ljava/lang/String;[Bjava/util/List;Lorg/apache/zookeeper/CreateMode;)Ljava/lang/String;"},
                {"create", "(Ljava/lang/String;[Bjava/util/List;Lorg/apache/zookeeper/CreateMode;Lorg/apache/zookeeper/data/Stat;)" +
                        "Ljava/lang/String;"},
                {"delete", "(Ljava/lang/String;ILorg/apache/zookeeper/data/Stat;)V"},
                {"setData", "(Ljava/lang/String;[BILorg/apache/zookeeper/data/Stat;)Lorg/apache/zookeeper/data/Stat;"},
                {"getChildren", "(Ljava/lang/String;ZLorg/apache/zookeeper/Watcher;Lorg/apache/zookeeper/data/Stat;)" +
                        "Ljava/util/List;"},
                {"setACL", "(Ljava/lang/String;Ljava/util/List;ILorg/apache/zookeeper/data/Stat;)Lorg/apache/zookeeper/data/Stat;"},
                {"getACL", "(Ljava/lang/String;Lorg/apache/zookeeper/data/Stat;)Ljava/util/List;"}
        };
        for (String[] pair : methods) {
            RuntimeSpy.registerInterceptor(ZK_CLASS, pair[0], pair[1], InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(ZK_CLASS, pair[0], pair[1], InterceptPoint.EXIT, this);
        }

 // Curator 入口：获取数据 / 创建 / 删除 / 设置数据 / 获取children / 检查exists
        String[] curatorMethods = {"getData", "create", "delete", "setData", "getChildren", "checkExists", "createContainers"};
        for (String method : curatorMethods) {
            RuntimeSpy.registerInterceptor(CURATOR_CLASS, method, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(CURATOR_CLASS, method, "", InterceptPoint.EXIT, this);
        }
    }

    @Override
    /** onintercept */
    public void onIntercept(InterceptContext ctx) {
        if (!enabled) {
            return;
        }
        InterceptPoint point = ctx.getPoint();
        switch (point) {
            case ENTRY -> handleEntry(ctx);
            case EXIT -> handleExit(ctx);
            case EXCEPTION -> handleException(ctx);
            default -> {
 // 日志_PRE / 日志_POST 等不在此处处理
            }
        }
    }

    /**
      * ZK 调用栈帧 — 用 ctx.用户数据 (this=ZooKeeper instance) + ctx.类名称 关联 entry/exit。
     */
    private static final ThreadLocal<TransmissionRecord> CURRENT = new ThreadLocal<>();

    /**
     * 处理Entry
     *
     * @param ctx ctx
     */
    private void handleEntry(InterceptContext ctx) {
        try {
            TransmissionRecord record = new TransmissionRecord();
            record.setTraceId(ctx.getTraceId());
            record.setSpanId(ctx.getSpanId());
            record.setStartTime(System.currentTimeMillis());
            record.setOperation(deriveOperation(ctx));

            // software = ZOOKEEPER_NATIVE / CURATOR
            Software software = ZK_CLASS.equals(ctx.getClassName()) ? Software.ZOOKEEPER_NATIVE : Software.CURATOR;
            record.setSoftware(software);
            record.setProtocol(Protocol.ZOOKEEPER);

 // 源：本进程的客户端（关闭 端）；从 用户数据 即 ZooKeeper 实例读取 连接字符串
            Object zk = ctx.getUserData();
            Endpoint source = Endpoint.builder()
                    .kind(EndpointKind.CLIENT)
                    .protocol(Protocol.ZOOKEEPER)
                    .software(software)
                    .host(localHost())
                    .port(0)
                    .path("/")
                    .build();
            record.setSource(source);

 // Target：从 ctx 提取 路径 或 连接字符串，构造 znode/集群端点
            String path = extractPathFromStack(ctx);
            Endpoint target = Endpoint.builder()
                    .kind(EndpointKind.SERVER)
                    .protocol(Protocol.ZOOKEEPER)
                    .software(software)
                    .host(extractConnectString(zk))
                    .port(2181)
                    .path(path)
                    .build();
            record.setTarget(target);

            CURRENT.set(record);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("ZooKeeperHandler.entry 异常: %s", e.getMessage()));
        }
    }

    /**
     * 处理Exit
     *
     * @param ctx ctx
     */
    private void handleExit(InterceptContext ctx) {
        try {
            TransmissionRecord record = CURRENT.get();
            if (record == null) {
                return;
            }
            CURRENT.remove();
            record.setEndTime(System.currentTimeMillis());
            record.setDuration(record.getEndTime() - record.getStartTime());
            record.setStatus(StatusCode.OK);
            addAndEmit(record, false);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("ZooKeeperHandler.exit 异常: %s", e.getMessage()));
        }
    }

    /**
     * 处理异常
     *
     * @param ctx ctx
     */
    private void handleException(InterceptContext ctx) {
        try {
            TransmissionRecord record = CURRENT.get();
            if (record == null) {
                return;
            }
            CURRENT.remove();
            record.setEndTime(System.currentTimeMillis());
            record.setDuration(record.getEndTime() - record.getStartTime());
            record.setStatus(StatusCode.ERROR);
            if (ctx.getThrowable() != null) {
                record.setErrorType(ctx.getThrowable().getClass().getName());
                record.setErrorMessage(ctx.getThrowable().getMessage());
            }
            addAndEmit(record, true);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("ZooKeeperHandler.exception 异常: %s", e.getMessage()));
        }
    }

    /**
     * 记录 + 同步到依赖图。
     * @param record record
     * @param isError 是否错误
     */
    private void addAndEmit(TransmissionRecord record, boolean isError) {
        records.add(record);
        try {
            com.chua.runtime.apm.storage.StorageManager.appendTransmission(record);
        } catch (Exception ignore) {
        }
        try {
            DependencyGraphHandler handler = ApmBootstrap.getGlobalHandler(DependencyGraphHandler.class);
            if (handler == null) {
                return;
            }
            if (record.getSource() == null || record.getTarget() == null) {
                return;
            }
            String errorType = isError ? record.getErrorType() : null;
            handler.record(record.getSource(), record.getTarget(), record.getProtocol(),
                    record.getSoftware(), record.getDuration(), isError, errorType);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("ZooKeeperHandler emit 异常: %s", e.getMessage()));
        }
    }

    /**
      * 从调用栈提取第一个 字符串 类型参数作为 路径（ZooKeeper API 第一个参数通常就是 路径）。
     * @param ctx ctx
     * @return extract路径从stack的结果
     */
    private static String extractPathFromStack(InterceptContext ctx) {
 // 简化：通过 ctx.类名称 + 方法名称 兜底
        String op = ctx.getMethodName();
        return "/" + op;
    }

    /**
      * 反射从 ZooKeeper 实例读取 连接字符串。
     * @param zk zk
     * @return extract连接字符串的结果
     */
    private static String extractConnectString(Object zk) {
        if (zk == null) {
            return "?";
        }
        try {
            Object v = ReflectUtils.getField(zk, "chrootPath");
            if (v != null && StringUtils.isNotEmpty(v.toString())) {
                return v.toString();
            }
        } catch (Exception ignore) {
            // 反射失败
        }
        return "zk-cluster";
    }

    /**
     * deriveoperation
     *
     * @param ctx ctx
     * @return deriveOperation的结果
     */
    private static String deriveOperation(InterceptContext ctx) {
        return ctx.getMethodName();
    }

    /**
     * 本地主机
     *
     * @return 本地主机的结果
     */
    private static String localHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * 获取Records
     *
     * @return 获取records的结果
     */
    public List<TransmissionRecord> getRecords() {
        return records.snapshot();
    }
}