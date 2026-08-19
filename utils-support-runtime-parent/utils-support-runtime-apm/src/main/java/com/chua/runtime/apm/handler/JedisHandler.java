package com.chua.runtime.apm.handler;

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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis/Jedis 应用层 Handler — 拦截 Jedis 客户端调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code redis.clients.jedis.Jedis}（Connection/Command 派发）</li>
 *   <li>{@code redis.clients.jedis.JedisCluster}（集群客户端）</li>
 *   <li>{@code redis.clients.jedis.BinaryJedis}（基类）</li>
 * </ul>
 *
 * <p>采用与 {@link ZooKeeperHandler} 相同的零编译期依赖策略。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JedisHandler implements Plugin, RuntimeSpy.Interceptor {
    /**
     * LOG
     */
    private static final Logger LOG = Logger.getLogger(JedisHandler.class.getName());

    /**
     * redis.clients.jedis.Jedis
     */
    private static final String JEDIS_CLASS = "redis/clients/jedis/Jedis";

    /**
     * redis.clients.jedis.BinaryJedis
     */
    private static final String BINARY_JEDIS_CLASS = "redis/clients/jedis/BinaryJedis";

    /**
     * redis.clients.jedis.JedisCluster
     */
    private static final String JEDIS_CLUSTER_CLASS = "redis/clients/jedis/JedisCluster";

    /**
     * 最大记录数
     */
    private static final int MAX_RECORDS = 5000;

    /**
     * records
     */
    private final com.chua.runtime.apm.handler.BoundedRecordList<TransmissionRecord> records;
    /**
     * enabled
     */
    private boolean enabled;
    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    /** 创建 JedisHandler 实例 */
    public JedisHandler() {
        this.records = new com.chua.runtime.apm.handler.BoundedRecordList<>(10000);
        this.started = new AtomicBoolean(false);
    }

    @Override
    /** Name */
    public String name() {
        return "jedis-handler";
    }

    @Override
    /** Version */
    public String version() {
        return "1.0.0";
    }

    @Override
    /** 初始化 */
    public void init(PluginContext context) throws Exception {
        this.enabled = "true".equals(context.getProperty("jedis.enabled", "true"));
        LOG.log(Level.INFO, String.format("JedisHandler 初始化完成，启用状态: %s", enabled));
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
        LOG.log(Level.INFO, "JedisHandler 启动完成，应用层 Jedis 拦截已注册");
    }

    @Override
    /** 停止 */
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        LOG.log(Level.INFO, "JedisHandler 停止");
    }

    @Override
    /** Status */
    public String status() {
        return String.format("JedisHandler[enabled=%s, records=%d]", enabled, records.size());
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 注册拦截规则 — Jedis/BinaryJedis 关键命令方法 + JedisCluster 集群入口。
     *
     * <p>descriptor 用空串表示任意描述符（SpyTransformer 不依赖 descriptor 区分）。</p>
     */
    private void registerInterceptors() {
        String[] commands = {
                "get", "set", "del", "exists", "expire", "ttl", "incr", "decr",
                "hget", "hset", "hdel", "hgetAll", "hmget", "hmset",
                "lpush", "rpush", "lpop", "rpop", "lrange", "llen",
                "sadd", "srem", "smembers", "sismember",
                "zadd", "zrem", "zrange", "zscore", "zcard",
                "publish", "subscribe"
        };
        for (String command : commands) {
            // Jedis 主类
            RuntimeSpy.registerInterceptor(JEDIS_CLASS, command, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(JEDIS_CLASS, command, "", InterceptPoint.EXIT, this);
            // BinaryJedis 父类（部分方法在父类中）
            RuntimeSpy.registerInterceptor(BINARY_JEDIS_CLASS, command, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(BINARY_JEDIS_CLASS, command, "", InterceptPoint.EXIT, this);
        }

        // JedisCluster — 集群命令（同样的命令名，但内部按 slot 转发）
        String[] clusterCommands = {"get", "set", "del", "exists", "expire", "hget", "hset", "hgetAll"};
        for (String command : clusterCommands) {
            RuntimeSpy.registerInterceptor(JEDIS_CLUSTER_CLASS, command, "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(JEDIS_CLUSTER_CLASS, command, "", InterceptPoint.EXIT, this);
        }
    }

    @Override
    /** OnIntercept */
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
            }
        }
    }

    /**
     * CURRENT
     */
    private static final ThreadLocal<TransmissionRecord> CURRENT = new ThreadLocal<>();

    /** 处理Entry */
    private void handleEntry(InterceptContext ctx) {
        try {
            TransmissionRecord record = new TransmissionRecord();
            record.setTraceId(ctx.getTraceId());
            record.setSpanId(ctx.getSpanId());
            record.setStartTime(System.currentTimeMillis());
            record.setOperation(deriveOperation(ctx));
            record.setSoftware(Software.JEDIS);
            record.setProtocol(Protocol.REDIS);

            // source = 调用方客户端
            Object jedis = ctx.getUserData();
            Endpoint source = Endpoint.builder()
                    .kind(EndpointKind.CLIENT)
                    .protocol(Protocol.REDIS)
                    .software(Software.JEDIS)
                    .host(localHost())
                    .port(0)
                    .path("/")
                    .build();
            record.setSource(source);

            // target = Redis 集群地址（反射读取 host/port）
            Endpoint target = Endpoint.builder()
                    .kind(EndpointKind.SERVER)
                    .protocol(Protocol.REDIS)
                    .software(Software.JEDIS)
                    .host(extractHost(jedis))
                    .port(extractPort(jedis))
                    .path("/")
                    .build();
            record.setTarget(target);

            CURRENT.set(record);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("JedisHandler.entry 异常: %s", e.getMessage()));
        }
    }

    /** 处理Exit */
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
            LOG.log(Level.FINE, String.format("JedisHandler.exit 异常: %s", e.getMessage()));
        }
    }

    /** 处理Exception */
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
            LOG.log(Level.FINE, String.format("JedisHandler.exception 异常: %s", e.getMessage()));
        }
    }

    /** 添加And发送 */
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
            LOG.log(Level.FINE, String.format("JedisHandler emit 异常: %s", e.getMessage()));
        }
    }

    /** DeriveOperation */
    private static String deriveOperation(InterceptContext ctx) {
        String op = ctx.getMethodName();
        // 尝试从调用栈第一个 String 字面量作为 key
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String cn = frame.getClassName();
            if (cn != null && cn.startsWith("redis.")) {
                break;
            }
        }
        return op.toUpperCase();
    }

    /**
     * 反射读取 Jedis 实例的 host（Jedis 通常继承自 BinaryJedis，host 在 connection 字段中）。
     */
    private static String extractHost(Object jedis) {
        if (jedis == null) {
            return "?";
        }
        try {
            // Jedis -> BinaryJedis -> client (Connection)
            Field clientField = findFieldChain(jedis.getClass(), "client", "connection");
            if (clientField != null) {
                clientField.setAccessible(true);
                Object connection = clientField.get(jedis);
                if (connection != null) {
                    Field hostField = findFieldChain(connection.getClass(), "host");
                    if (hostField != null) {
                        hostField.setAccessible(true);
                        Object host = hostField.get(connection);
                        if (host != null) {
                            return host.toString();
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return "redis";
    }

    /** ExtractPort */
    private static int extractPort(Object jedis) {
        if (jedis == null) {
            return 6379;
        }
        try {
            Field clientField = findFieldChain(jedis.getClass(), "client", "connection");
            if (clientField != null) {
                clientField.setAccessible(true);
                Object connection = clientField.get(jedis);
                if (connection != null) {
                    Field portField = findFieldChain(connection.getClass(), "port");
                    if (portField != null) {
                        portField.setAccessible(true);
                        Object port = portField.get(connection);
                        if (port instanceof Number) {
                            return ((Number) port).intValue();
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return 6379;
    }

    /**
     * 在继承链中寻找名为 name 的字段。
     */
    private static Field findFieldChain(Class<?> clazz, String... names) {
        Class<?> c = clazz;
        while (c != null) {
            for (String name : names) {
                try {
                    return c.getDeclaredField(name);
                } catch (NoSuchFieldException ignore) {
                    // 继续在当前类或父类中找下一个 name
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** LocalHost */
    private static String localHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /** 获取Records */
    public List<TransmissionRecord> getRecords() {
        return records.snapshot();
    }
}