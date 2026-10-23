package com.chua.runtime.apm;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.runtime.apm.handler.AsyncHttpClientHandler;
import com.chua.runtime.apm.handler.CassandraHandler;
import com.chua.runtime.apm.handler.ClickHouseHandler;
import com.chua.runtime.apm.handler.ConsulHandler;
import com.chua.runtime.apm.handler.CouchbaseHandler;
import com.chua.runtime.apm.handler.CuratorHandler;
import com.chua.runtime.apm.handler.CxfHandler;
import com.chua.runtime.apm.handler.DamengHandler;
import com.chua.runtime.apm.handler.Db2Handler;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.handler.DubboHandler;
import com.chua.runtime.apm.handler.ElasticsearchHandler;
import com.chua.runtime.apm.handler.EtcdHandler;
import com.chua.runtime.apm.handler.EurekaHandler;
import com.chua.runtime.apm.handler.FeignHandler;
import com.chua.runtime.apm.handler.FileHandler;
import com.chua.runtime.apm.handler.FlinkHandler;
import com.chua.runtime.apm.handler.GrpcHandler;
import com.chua.runtime.apm.handler.GraphQLHandler;
import com.chua.runtime.apm.handler.HBaseHandler;
import com.chua.runtime.apm.handler.H2Handler;
import com.chua.runtime.apm.handler.HandleLeakHandler;
import com.chua.runtime.apm.handler.HandleInventoryHandler;
import com.chua.runtime.apm.handler.HazelcastHandler;
import com.chua.runtime.apm.handler.HdfsHandler;
import com.chua.runtime.apm.handler.HibernateHandler;
import com.chua.runtime.apm.handler.HttpClientHandler;
import com.chua.runtime.apm.handler.IgniteHandler;
import com.chua.runtime.apm.handler.InfluxDbHandler;
import com.chua.runtime.apm.handler.JdkHttpClientHandler;
import com.chua.runtime.apm.handler.JdkHttpServerHandler;
import com.chua.runtime.apm.handler.JedisHandler;
import com.chua.runtime.apm.handler.JettyHandler;
import com.chua.runtime.apm.handler.JmsHandler;
import com.chua.runtime.apm.handler.KafkaHandler;
import com.chua.runtime.apm.handler.KingbaseHandler;
import com.chua.runtime.apm.handler.KubernetesHandler;
import com.chua.runtime.apm.handler.LettuceHandler;
import com.chua.runtime.apm.handler.LogHandler;
import com.chua.runtime.apm.handler.MemcachedHandler;
import com.chua.runtime.apm.handler.MongoDbHandler;
import com.chua.runtime.apm.handler.MqttHandler;
import com.chua.runtime.apm.handler.MyBatisHandler;
import com.chua.runtime.apm.handler.MySqlHandler;
import com.chua.runtime.apm.handler.NacosHandler;
import com.chua.runtime.apm.handler.NatsHandler;
import com.chua.runtime.apm.handler.Neo4jHandler;
import com.chua.runtime.apm.handler.NetHandler;
import com.chua.runtime.apm.handler.NettyHandler;
import com.chua.runtime.apm.handler.OkHttpHandler;
import com.chua.runtime.apm.handler.OpenSearchHandler;
import com.chua.runtime.apm.handler.OracleHandler;
import com.chua.runtime.apm.handler.PahoMqttHandler;
import com.chua.runtime.apm.handler.PlayFrameworkHandler;
import com.chua.runtime.apm.handler.PostgreSqlHandler;
import com.chua.runtime.apm.handler.PulsarHandler;
import com.chua.runtime.apm.handler.QuartzHandler;
import com.chua.runtime.apm.handler.RSocketHandler;
import com.chua.runtime.apm.handler.RabbitMqHandler;
import com.chua.runtime.apm.handler.ReactiveStreamsHandler;
import com.chua.runtime.apm.handler.RedissonHandler;
import com.chua.runtime.apm.handler.RestTemplateHandler;
import com.chua.runtime.apm.handler.RocketMqHandler;
import com.chua.runtime.apm.handler.AwsSdkHandler;
import com.chua.runtime.apm.handler.ShardingSphereHandler;
import com.chua.runtime.apm.handler.SentinelHandler;
import com.chua.runtime.apm.handler.SolrHandler;
import com.chua.runtime.apm.handler.SparkHandler;
import com.chua.runtime.apm.handler.SpringBatchHandler;
import com.chua.runtime.apm.handler.SpringCloudGatewayHandler;
import com.chua.runtime.apm.handler.SpringCloudStreamHandler;
import com.chua.runtime.apm.handler.SpringDataJpaHandler;
import com.chua.runtime.apm.handler.SpringIntegrationHandler;
import com.chua.runtime.apm.handler.SpringMvcHandler;
import com.chua.runtime.apm.handler.SystemOutCaptureHandler;
import com.chua.runtime.apm.handler.SqlServerHandler;
import com.chua.runtime.apm.handler.SeataHandler;
import com.chua.runtime.apm.handler.ThriftHandler;
import com.chua.runtime.apm.handler.TomcatHandler;
import com.chua.runtime.apm.handler.TraceHandler;
import com.chua.runtime.apm.handler.TransmissionHandler;
import com.chua.runtime.apm.handler.UndertowHandler;
import com.chua.runtime.apm.handler.VertxHandler;
import com.chua.runtime.apm.handler.WebClientHandler;
import com.chua.runtime.apm.handler.WebSocketHandler;
import com.chua.runtime.apm.handler.XxlJobHandler;
import com.chua.runtime.apm.handler.ZooKeeperHandler;
import com.chua.runtime.apm.storage.StorageConfig;
import com.chua.runtime.apm.storage.StorageManager;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.spy.SpyBootstrap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;

/**
 * APM 启动器 — 程序化启动所有 APM 处理器。
 *
 * <p>用途：</p>
 * <ul>
 *   <li>由 runtime-starter 调用，为指定应用注入插桩能力</li>
 *   <li>由 SpyBootstrap 的插件机制间接使用（SPI 注册）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ApmBootstrap {

    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(ApmBootstrap.class.getName());
    /**
     * 全局唯一实例（runtime智能体.premain 启动时设置）
     */
    private static volatile ApmBootstrap globalInstance;

    /**
     * 处理器列表 — 副本on写入array列表 保证并发读(处理器 列表)与启动期注册/启动写不冲突。
     */
    private final List<Plugin> handlers;

    /**
     * 是否已启动
     */
    private volatile boolean started;

    /**
     * 外部显式指定的存储配置（优先级最高，覆盖 agent options 与系统属性）。
     *
     * <p>宿主应用（如 hotsport 的本地 APM 引擎）在 {@link #start()} 之前调用
     * {@link #storageConfig(Map)} 注入，即可让 {@code plugin.*} 配置真正生效。</p>
     */
    private volatile Map<String, String> storageOverrides = java.util.Collections.emptyMap();

    /**
     * 创建 APM 启动器。
     *
     * @param pluginDir 插件目录
     */
    public ApmBootstrap(Path pluginDir) {
        this.handlers = new CopyOnWriteArrayList<>();
        this.started = false;
        registerDefaults(pluginDir);
        globalInstance = this;
    }

    /**
     * 指定存储配置（需在 {@link #start()} 之前调用）。
     *
     * <p>键名使用 {@code apm.storage.*}，与 agent options / 系统属性同名；</p>
     * <p>优先级：本方法注入值 &gt; Agent options &gt; 系统属性 &gt; 默认值。</p>
     *
     * @param overrides 存储配置（可为空或 null）
     * @return 自身，便于链式调用
     */
    public ApmBootstrap storageConfig(Map<String, String> overrides) {
        this.storageOverrides = overrides == null
                ? java.util.Collections.emptyMap()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(overrides));
        return this;
    }

    /**
     * 注册默认的处理器。
     *
     * @param pluginDir 插件目录
     */
    private void registerDefaults(Path pluginDir) {
        PluginContext context = new PluginContext(pluginDir);
        handlers.add(new LogHandler());
        handlers.add(new NetHandler());
        handlers.add(new FileHandler());
        handlers.add(new TraceHandler());
        handlers.add(new TransmissionHandler());
        handlers.add(new DependencyGraphHandler());
        handlers.add(new HandleLeakHandler());
        handlers.add(new HandleInventoryHandler());
        handlers.add(new SystemOutCaptureHandler());
        handlers.add(new ZooKeeperHandler());
        handlers.add(new JedisHandler());
        handlers.add(new LettuceHandler());
        handlers.add(new RedissonHandler());
        handlers.add(new KafkaHandler());
        handlers.add(new RocketMqHandler());
        handlers.add(new MySqlHandler());
        handlers.add(new PostgreSqlHandler());
        handlers.add(new OracleHandler());
        handlers.add(new SqlServerHandler());
        handlers.add(new H2Handler());
        handlers.add(new Db2Handler());
        handlers.add(new ClickHouseHandler());
        handlers.add(new DamengHandler());
        handlers.add(new KingbaseHandler());
        handlers.add(new CassandraHandler());
        handlers.add(new InfluxDbHandler());
        handlers.add(new HBaseHandler());
        handlers.add(new PulsarHandler());
        handlers.add(new ThriftHandler());
        handlers.add(new ShardingSphereHandler());
        handlers.add(new Neo4jHandler());
        handlers.add(new MongoDbHandler());
        handlers.add(new MemcachedHandler());
        handlers.add(new HazelcastHandler());
        handlers.add(new CouchbaseHandler());
        handlers.add(new EtcdHandler());
        handlers.add(new IgniteHandler());
        handlers.add(new RSocketHandler());
        handlers.add(new NatsHandler());
        handlers.add(new GraphQLHandler());
        handlers.add(new RabbitMqHandler());
        handlers.add(new MqttHandler());
        handlers.add(new JmsHandler());
        handlers.add(new ElasticsearchHandler());
        handlers.add(new SolrHandler());
        handlers.add(new GrpcHandler());
        handlers.add(new DubboHandler());
        handlers.add(new NacosHandler());
        handlers.add(new EurekaHandler());
        handlers.add(new ConsulHandler());
        handlers.add(new HttpClientHandler());
        handlers.add(new FeignHandler());
        handlers.add(new RestTemplateHandler());
        handlers.add(new SpringCloudGatewayHandler());
        handlers.add(new WebClientHandler());
        handlers.add(new OkHttpHandler());
        handlers.add(new JdkHttpClientHandler());
        handlers.add(new JdkHttpServerHandler());
        handlers.add(new TomcatHandler());
        handlers.add(new JettyHandler());
        handlers.add(new UndertowHandler());
        handlers.add(new NettyHandler());
        handlers.add(new SpringMvcHandler());
        handlers.add(new SpringDataJpaHandler());
        handlers.add(new MyBatisHandler());
        handlers.add(new HibernateHandler());
        handlers.add(new WebSocketHandler());
        handlers.add(new SpringCloudStreamHandler());
        handlers.add(new ReactiveStreamsHandler());
        handlers.add(new VertxHandler());
        handlers.add(new PlayFrameworkHandler());
        handlers.add(new XxlJobHandler());
        handlers.add(new SentinelHandler());
        handlers.add(new SeataHandler());
        handlers.add(new CxfHandler());
        handlers.add(new OpenSearchHandler());
        handlers.add(new HdfsHandler());
        handlers.add(new SparkHandler());
        handlers.add(new FlinkHandler());
        handlers.add(new SpringIntegrationHandler());
        handlers.add(new KubernetesHandler());
        handlers.add(new AwsSdkHandler());
        handlers.add(new QuartzHandler());
        handlers.add(new SpringBatchHandler());
        handlers.add(new CuratorHandler());
        handlers.add(new PahoMqttHandler());
        handlers.add(new AsyncHttpClientHandler());
 // 每个 处理器 独立 尝试/卡扣,单个失败不阻断其他
        for (Plugin handler : handlers) {
            try {
                handler.init(context);
            } catch (Exception e) {
                LOG.log(Level.WARNING, String.format("默认处理器[%s] 初始化失败", handler.name()), e);
            }
        }
    }

    /**
     * 注册自定义处理器。
     *
     * <p>同时使用默认 {@link PluginContext} 调用 {@link Plugin#init(PluginContext)}，
     * 否则处理器中需要初始化的字段（如 已启用）将保持默认值，启动() 会被短路。</p>
     *
     * @param handler 插件处理器
     */
    public synchronized void addHandler(Plugin handler) {
        if (handler == null) {
            return;
        }
        handlers.add(handler);
        try {
            PluginContext context = new PluginContext(Paths.get(System.getProperty("java.io.tmpdir")));
            handler.init(context);
        } catch (Exception e) {
            LOG.log(Level.WARNING, String.format("自定义处理器[%s] 初始化失败", handler.name()), e);
        }
    }

    /**
     * 启动所有处理器。
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        // 1. 先启动存储层(Handlers 在 addRecord 时会调用 StorageManager.append)
        // 取值优先级：Agent options > 系统属性 apm.storage.* > 默认值。
        // Agent options 即 -javaagent:jar=storage.type=sqlite,... 或 plugin.agent.options，
        // 部署方按环境调整存储行为时无需改系统属性。
        Map<String, String> configMap = Wraps.map();
        configMap.put("apm.storage.type",
                resolveConfig("storage.type", "apm.storage.type", "inmemory"));
        configMap.put("apm.storage.retention.ms",
                resolveConfig("storage.retention.ms", "apm.storage.retention.ms",
                        String.valueOf(7L * 24 * 60 * 60 * 1000)));
        configMap.put("apm.storage.capacity",
                resolveConfig("storage.capacity", "apm.storage.capacity", "100000"));
        String storagePath = resolveConfig("storage.path", "apm.storage.path", "");
        if (!storagePath.isEmpty()) {
            configMap.put("apm.storage.path", storagePath);
        }
        try {
            StorageManager.init(new StorageConfig(configMap));
        } catch (Throwable e) {
            // StorageManager 内部已做降级，这里再兜一层：观测组件永不阻断宿主启动
            LOG.log(Level.SEVERE, "APM 存储层启动异常，APM 采集本次不生效（业务进程继续启动）", e);
        }

        // 2. 启动所有 Handler — 失败标记但不阻断(便于排查)
        int startedCount = 0;
        for (Plugin handler : handlers) {
            try {
                handler.start();
                startedCount++;
                LOG.log(Level.INFO, String.format("APM 处理器[%s] 启动", handler.name()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("APM 处理器[%s] 启动失败", handler.name()), e);
            }
        }
        started = true;
        if (startedCount == 0) {
            LOG.log(Level.WARNING, "APM 所有 Handler 启动失败,APM 实际不工作");
        }
    }

    /**
     * 按优先级解析存储配置项：
     * 宿主注入值 &gt; Agent options（短名 / 全名）&gt; 系统属性 &gt; 默认值。
     *
     * @param agentOptionKey Agent options 短名 key（如 storage.type）
     * @param sysPropKey     系统属性名（如 apm.storage.type）
     * @param defaultValue   默认值
     * @return 生效值
     */
    private String resolveConfig(String agentOptionKey, String sysPropKey, String defaultValue) {
        // 1. 宿主显式注入（plugin.* 配置）优先级最高
        String fromHost = storageOverrides.get(sysPropKey);
        if (fromHost != null && !fromHost.isEmpty()) {
            return fromHost;
        }
        Map<String, String> options;
        try {
            options = SpyBootstrap.agentOptions();
        } catch (Throwable t) {
            // spy 模块不可见（宿主自建 ApmBootstrap 且未挂 agent）时降级为纯系统属性
            options = java.util.Collections.emptyMap();
        }
        String fromAgent = options.get(agentOptionKey);
        if (fromAgent == null || fromAgent.isEmpty()) {
            // 兼容全名 key（用户写 apm.storage.type 而非 storage.type）
            fromAgent = options.get("apm." + agentOptionKey);
        }
        if (fromAgent != null && !fromAgent.isEmpty()) {
            return fromAgent;
        }
        String fromSys = System.getProperty(sysPropKey);
        if (fromSys != null && !fromSys.isEmpty()) {
            return fromSys;
        }
        return defaultValue;
    }

    /**
     * 停止所有处理器。
     *
     * <p>正确顺序:</p>
     * <ol>
     *   <li>每个 handler.stop() — 注销 SpyTransformer 拦截规则(防止新事件入队)</li>
     *   <li>短暂等待(让 in-flight 事件完成落盘)</li>
     *   <li>StorageManager.shutdown() — 关闭存储</li>
     * </ol>
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }
 // 1. 逆序停止 处理器 — 注销拦截规则
        for (int i = handlers.size() - 1; i >= 0; i--) {
            Plugin handler = handlers.get(i);
            try {
                handler.stop();
                LOG.log(Level.INFO, String.format("APM 处理器[%s] 停止", handler.name()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("APM 处理器[%s] 停止失败", handler.name()), e);
            }
        }
 // 2. 短暂排空 — 让 入-flight 的事件完成 onintercept 落盘
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 3. 最后关闭存储
        try {
            StorageManager.shutdown();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "StorageManager 关闭失败", e);
        }
        started = false;
    }

    /**
     * 所有处理器状态。
     *
     * @return 状态字符串
     */
    public String status() {
        StringBuilder sb = new StringBuilder();
        for (Plugin handler : handlers) {
            sb.append(handler.status()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 获取指定类型的处理器。
     *
     * @param type 处理器类型
     * @param <T>  处理器泛型
     * @return 处理器实例，不存在返回 空
     */
    public <T extends Plugin> T getHandler(Class<T> type) {
        for (Plugin handler : handlers) {
            if (type.isInstance(handler)) {
                return type.cast(handler);
            }
        }
        return null;
    }

    /**
     * 获取全局 apmbootstrap 实例。
     *
     * @return 全局实例，未启动时返回 空
     */
    public static ApmBootstrap getGlobal() {
        return globalInstance;
    }

    /**
     * 获取全局指定类型的处理器。
     *
     * @param type 处理器类型
     * @param <T>  处理器泛型
     * @return 处理器实例，全局未启动或类型不存在时返回 空
     */
    public static <T extends Plugin> T getGlobalHandler(Class<T> type) {
        ApmBootstrap global = globalInstance;
        if (global == null) {
            return null;
        }
        return global.getHandler(type);
    }

    /**
     * 获取所有已注册的处理器。
     *
     * @return 不可修改的处理器列表
     */
    public List<Plugin> getHandlers() {
        return java.util.Collections.unmodifiableList(handlers);
    }

    /**
     * 是否已启动。
     *
     * @return 已启动返回 true
     */
    public boolean isStarted() {
        return started;
    }
}
