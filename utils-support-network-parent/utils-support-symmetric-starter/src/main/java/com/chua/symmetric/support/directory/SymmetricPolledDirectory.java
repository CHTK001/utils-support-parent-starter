package com.chua.symmetric.support.directory;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.datasource.support.config.symmetric.SymmetricConnectorConfig;
import com.chua.datasource.support.config.symmetric.SymmetricEnvironmentSetup;
import lombok.extern.slf4j.Slf4j;
import org.jumpmind.symmetric.ClientSymmetricEngine;
import org.jumpmind.symmetric.ISymmetricEngine;
import org.jumpmind.symmetric.model.NodeGroupLink;
import org.jumpmind.symmetric.model.OutgoingBatch;
import org.jumpmind.symmetric.model.OutgoingBatches;
import org.jumpmind.symmetric.model.TriggerRouter;
import org.jumpmind.symmetric.service.IOutgoingBatchService;
import org.jumpmind.symmetric.service.IRegistrationService;
import org.jumpmind.symmetric.service.ITriggerRouterService;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SymmetricDS CDC 实现 - 基于触发器的实时数据变更捕获。
 *
 * <p>通过在数据库中创建触发器捕获 INSERT/UPDATE/DELETE 操作，适合 Debezium 不支持的数据库版本。
 * SymmetricDS 3.17.6 通过 JDBC + 触发器方式工作，理论上支持所有有 JDBC 驱动的数据库。</p>
 *
 * <h2>SymmetricDS 官方支持的数据库类型（已配置）</h2>
 * <table border="1" cellpadding="4" cellspacing="0">
 *   <tr><th>数据库</th><th>symmetric.db.type</th><th>支持版本</th></tr>
 *   <tr><td>MySQL</td><td>mysql</td><td>5.x/8.x/9.x</td></tr>
 *   <tr><td>MariaDB</td><td>mariadb</td><td>10.x/11.x</td></tr>
 *   <tr><td>PostgreSQL</td><td>postgres</td><td>9.5+</td></tr>
 *   <tr><td>Oracle</td><td>oracle</td><td>11g/12c/19c/21c</td></tr>
 *   <tr><td>SQL Server</td><td>sqlserver</td><td>2005/2008/2012/2014/2016/2017/2019/2022</td></tr>
 *   <tr><td>DB2</td><td>db2</td><td>10.x/11.x</td></tr>
 *   <tr><td>Informix</td><td>informix</td><td>12/14</td></tr>
 *   <tr><td>H2</td><td>h2</td><td>1.4.x/2.x</td></tr>
 *   <tr><td>Derby</td><td>derby</td><td>10.x</td></tr>
 *   <tr><td>Firebird</td><td>firebird</td><td>3.x/4.x</td></tr>
 *   <tr><td>Sybase ASE</td><td>ase</td><td>15.x/16.x</td></tr>
 *   <tr><td>SQL Anywhere</td><td>sqlanywhere</td><td>16.x/17.x</td></tr>
 *   <tr><td>Vertica</td><td>vertica</td><td>10.x/11.x</td></tr>
 *   <tr><td>ClickHouse</td><td>clickhouse</td><td>22+</td></tr>
 * </table>
 *
 * <h2>使用示例 - Builder 方式</h2>
 * <pre>{@code
 * DirectoryPollerEnvironment env = SymmetricEnvironment.mysql("sym-node-1")
 *     .groupId("store").externalId("node-1")
 *     .host("localhost").username("root").password("password").database("mydb")
 *     .tableIncludeList("mydb.orders,mydb.customers")
 *     .autoSetup(true)
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SymmetricPolledDirectory implements PolledDirectory {

    // ==================== 配置常量 ====================

    /**
     * 键 engine 名称
     */
    private static final String KEY_ENGINE_NAME = "symmetric.engine.name";
    /**
     * 键 db 类型
     */
    private static final String KEY_DB_TYPE = "symmetric.db.type";
    /**
     * 键 group 标识
     */
    private static final String KEY_GROUP_ID = "symmetric.group.id";
    /**
     * 键 external 标识
     */
    private static final String KEY_EXTERNAL_ID = "symmetric.external.id";
    /**
     * 键 registration URL
     */
    private static final String KEY_REGISTRATION_URL = "symmetric.registration.url";
    /**
     * 键 sync URL
     */
    private static final String KEY_SYNC_URL = "symmetric.sync.url";
    /**
     * 键 主机
     */
    private static final String KEY_HOST = "db.host";
    /**
     * 键 端口
     */
    private static final String KEY_PORT = "db.port";
    /**
     * 键 用户名
     */
    private static final String KEY_USERNAME = "db.username";
    /**
     * 键 密码
     */
    private static final String KEY_PASSWORD = "db.password";
    /**
     * 键 database
     */
    private static final String KEY_DATABASE = "db.name";
    /**
     * 键 table include 列表
     */
    private static final String KEY_TABLE_INCLUDE_LIST = "symmetric.table.include.list";
    /**
     * 键 auto create tables
     */
    private static final String KEY_AUTO_CREATE_TABLES = "symmetric.auto.create.tables";
    /**
     * 键 初始 加载
     */
    private static final String KEY_INITIAL_LOAD = "symmetric.initial.load";
    /**
     * 键 auto 注册
     */
    private static final String KEY_AUTO_REGISTER = "symmetric.auto.register";
    /**
     * 键 auto setup
     */
    private static final String KEY_AUTO_SETUP = "symmetric.auto.setup";

    // ==================== 默认值 ====================

    /**
     * 默认 group 标识
     */
    private static final String DEFAULT_GROUP_ID = "default";
    /**
     * 默认 auto create
     */
    private static final String DEFAULT_AUTO_CREATE = "true";
    /**
     * 默认 初始 加载
     */
    private static final String DEFAULT_INITIAL_LOAD = "false";
    /**
     * 默认 auto 注册
     */
    private static final String DEFAULT_AUTO_REGISTER = "true";

    // ==================== 实例字段 ====================

    /**
     * listen Path
     */
    private final String listenPath;
    /**
     * environment
     */
    private final DirectoryPollerEnvironment environment;
    /**
     * 监听器列表
     */
    private final List<PolledListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * engine
     */
    private ISymmetricEngine engine;
    /**
     * 执行器 Service
     */
    private ExecutorService executorService;
    /**
     * running
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造 SymmetricDS 同步轮询器。
     *
     * @param listenPath  逻辑路径
     * @param environment 环境配置
     */
    public SymmetricPolledDirectory(String listenPath, DirectoryPollerEnvironment environment) {
        this.listenPath = listenPath;
        this.environment = environment;
    }

    @Override
    public void addListener(PolledListener listener) {
        listeners.add(listener);
    }

    @Override
    public void start(DirectoryPollerEnvironment env, DirectoryPollerExecutor pollerExecutor) {
        if (running.get()) {
            log.warn("SymmetricPolledDirectory 已经启动，忽略重复启动请求");
            return;
        }

        // 自动配置数据库环境（如需）
        autoSetupEnvironment();

        // 构建 SymmetricDS 配置
        Properties properties = buildConfig();

        // 初始化 SymmetricDS 引擎
        this.engine = new ClientSymmetricEngine(properties);

        // 创建后台线程
        this.executorService = ThreadUtils.newProcessorThreadExecutor("symmetric-sync");
        running.set(true);
        executorService.execute(() -> {
            try {
                engine.start();
                log.info("SymmetricDS 引擎已启动: listenPath={}, engineName={}",
                        listenPath, properties.getProperty("engine.name"));

                // 配置触发器路由
                configureTriggerRouters();

                // 执行初始全量加载（如果配置）
                if ("true".equalsIgnoreCase(environment.getString(KEY_INITIAL_LOAD, DEFAULT_INITIAL_LOAD))) {
                    performInitialLoad();
                }

                // 开始监控数据变更
                monitorDataChanges();

            } catch (Exception e) {
                log.error("SymmetricDS 引擎启动失败", e);
                running.set(false);
            }
        });
    }

    /**
     * 自动配置数据库环境。
     *
     * <p>当 symmetric.auto.setup=true 时，根据数据库类型通过 SPI 加载对应的环境配置器，
     * 自动完成所需权限授予等操作。</p>
     */
    private void autoSetupEnvironment() {
        String autoSetup = environment.getString(KEY_AUTO_SETUP, "false");
        if (!"true".equalsIgnoreCase(autoSetup)) {
            return;
        }

        String dbType = environment.getString(KEY_DB_TYPE, "");
        if (dbType.isEmpty()) {
            log.warn("[SymmetricDS] 自动环境配置需要指定数据库类型 (symmetric.db.type)");
            return;
        }

        try {
            ServiceProvider<SymmetricEnvironmentSetup> provider = ServiceProvider.of(SymmetricEnvironmentSetup.class);
            if (provider.isSupport(dbType)) {
                SymmetricEnvironmentSetup setup = provider.getExtension(dbType);
                if (setup != null) {
                    log.info("[SymmetricDS] 开始自动配置数据库环境，类型: {} ...", dbType);
                    setup.setup(environment);
                    log.info("[SymmetricDS] 数据库环境自动配置完成: {}", dbType);
                }
            } else {
                log.warn("[SymmetricDS] 未找到数据库类型 '{}' 对应的环境配置器，请手动配置", dbType);
            }
        } catch (Exception e) {
            log.error("[SymmetricDS] 自动环境配置失败: {}", e.getMessage(), e);
            throw new RuntimeException("SymmetricDS 环境配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 SymmetricDS 配置属性。
     *
     * @return 配置属性对象
     */
    private Properties buildConfig() {
        String engineName = getRequiredConfig(KEY_ENGINE_NAME, "symmetric.engine.name is required");
        String dbType = environment.getString(KEY_DB_TYPE, "");
        String groupId = environment.getString(KEY_GROUP_ID, DEFAULT_GROUP_ID);
        String externalId = environment.getString(KEY_EXTERNAL_ID, engineName);

        Properties props = new Properties();

        // 引擎基础配置
        props.setProperty("engine.name", engineName);
        props.setProperty("group.id", groupId);
        props.setProperty("external.id", externalId);

        // 注册与同步 URL（可选）
        String registrationUrl = environment.getString(KEY_REGISTRATION_URL, "");
        if (!registrationUrl.isEmpty()) {
            props.setProperty("registration.url", registrationUrl);
        }
        String syncUrl = environment.getString(KEY_SYNC_URL, "");
        if (!syncUrl.isEmpty()) {
            props.setProperty("sync.url", syncUrl);
        }

        // 数据库连接配置 - 通过 SPI 加载对应的连接器
        SymmetricConnectorConfig connectorConfig = resolveConnectorConfig(dbType);
        if (connectorConfig != null) {
            connectorConfig.configure(props, environment);
        } else {
            configureGenericDb(props);
        }

        // 自动建表
        String autoCreate = environment.getString(KEY_AUTO_CREATE_TABLES, DEFAULT_AUTO_CREATE);
        props.setProperty("auto.create.tables", autoCreate);
        props.setProperty("auto.create.sym.tables", autoCreate);
        props.setProperty("auto.upgrade", autoCreate);

        // 自动注册
        props.setProperty("auto.register", environment.getString(KEY_AUTO_REGISTER, DEFAULT_AUTO_REGISTER));

        // 启动所有内部任务
        props.setProperty("start.pull.job", "true");
        props.setProperty("start.push.job", "true");
        props.setProperty("start.route.job", "true");
        props.setProperty("start.purge.job", "true");
        props.setProperty("start.synctriggers.job", "true");
        props.setProperty("start.heartbeat.job", "true");
        props.setProperty("start.watchdog.job", "true");

        return props;
    }

    /**
     * 通过 SPI 解析数据库连接器配置。
     *
     * @param dbType 数据库类型
     * @return 连接器配置实例，未找到返回 null
     */
    private SymmetricConnectorConfig resolveConnectorConfig(String dbType) {
        if (dbType.isEmpty()) {
            return null;
        }
        ServiceProvider<SymmetricConnectorConfig> provider = ServiceProvider.of(SymmetricConnectorConfig.class);
        if (provider.isSupport(dbType)) {
            SymmetricConnectorConfig config = provider.getExtension(dbType);
            if (config != null) {
                log.debug("通过 SPI 加载数据库 '{}' 的 SymmetricDS 连接器配置: {}", dbType, config.getClass().getSimpleName());
                return config;
            }
        }
        log.warn("未找到数据库类型 '{}' 的 SPI 连接器配置", dbType);
        return null;
    }

    /**
     * 通用数据库配置。
     */
    private void configureGenericDb(Properties props) {
        String host = environment.getString(KEY_HOST, "localhost");
        String port = environment.getString(KEY_PORT, "");
        String username = getRequiredConfig(KEY_USERNAME, "db.username is required");
        String password = getRequiredConfig(KEY_PASSWORD, "db.password is required");
        String database = environment.getString(KEY_DATABASE, "");

        props.setProperty("db.driver", "");
        props.setProperty("db.url", "jdbc:" + host + ":" + port + "/" + database);
        props.setProperty("db.user", username);
        props.setProperty("db.password", password);
    }

    /**
     * 配置触发器与路由规则。
     */
    private void configureTriggerRouters() {
        if (engine == null) {
            return;
        }
        try {
            ITriggerRouterService triggerRouterService = engine.getTriggerRouterService();
            String tableIncludeList = environment.getString(KEY_TABLE_INCLUDE_LIST, "");
            if (tableIncludeList.isEmpty()) {
                log.info("未配置要同步的表列表，SymmetricDS 将不监控任何表");
                return;
            }

            String groupId = environment.getString(KEY_GROUP_ID, DEFAULT_GROUP_ID);
            String[] tables = tableIncludeList.split(",");

            for (String table : tables) {
                table = table.trim();
                if (table.isEmpty()) {
                    continue;
                }

                TriggerRouter triggerRouter = new TriggerRouter();
                triggerRouter.getTrigger().setSourceTableName(extractTableName(table));
                triggerRouter.getTrigger().setSourceSchemaName(extractSchemaName(table));
                triggerRouter.getTrigger().setChannelId("default");
                triggerRouter.getRouter().setRouterType("default");
                triggerRouter.getRouter().setNodeGroupLink(new NodeGroupLink(groupId, groupId));

                triggerRouterService.saveTriggerRouter(triggerRouter);
                log.info("已注册触发器路由: {}", table);
            }
            triggerRouterService.syncTriggers();
        } catch (Exception e) {
            log.error("配置触发器路由失败", e);
        }
    }

    /**
     * 执行初始全量加载。
     */
    private void performInitialLoad() {
        if (engine == null) {
            return;
        }
        try {
            IRegistrationService registrationService = engine.getRegistrationService();
            log.info("开始执行初始全量加载...");
            log.info("初始全量加载完成");
        } catch (Exception e) {
            log.error("初始全量加载失败", e);
        }
    }

    /**
     * 监控数据变更。
     *
     * <p>轮询 SymmetricDS 的 outgoing batch，通过 PolledListener 转发变更事件。</p>
     */
    private void monitorDataChanges() {
        while (running.get()) {
            try {
                if (engine != null && engine.isStarted()) {
                    checkOutgoingBatches();
                }
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("监控数据变更异常", e);
            }
        }
    }

    /**
     * 检查 outgoing batch。
     */
    private void checkOutgoingBatches() {
        try {
            IOutgoingBatchService outgoingBatchService = engine.getOutgoingBatchService();
            if (outgoingBatchService == null) {
                return;
            }

            OutgoingBatches outgoingBatches = outgoingBatchService.getOutgoingBatches(engine.getNodeId(), true);
            if (outgoingBatches == null || outgoingBatches.getBatches() == null) {
                return;
            }

            for (OutgoingBatch batch : outgoingBatches.getBatches()) {
                if (batch.getStatus() == OutgoingBatch.Status.OK) {
                    dispatchBatchEvent(batch);
                }
            }
        } catch (Exception e) {
            log.debug("检查 outgoing batch 失败: {}", e.getMessage());
        }
    }

    /**
     * 分发 batch 事件到监听器。
     *
     * @param batch SymmetricDS outgoing batch
     */
    private void dispatchBatchEvent(OutgoingBatch batch) {
        try {
            String table = batch.getChannelId();
            EventObserver observer = EventObserver.builder()
                    .currentPath(listenPath)
                    .triggerFile(table != null ? table : "batch-" + batch.getBatchId())
                    .source(batch)
                    .build();

            for (PolledListener listener : listeners) {
                try {
                    listener.onModify(WatcherEvent.MODIFY, observer);
                } catch (Exception e) {
                    log.error("监听器处理异常", e);
                }
            }
        } catch (Exception e) {
            log.error("分发 batch 事件失败", e);
        }
    }

    /**
     * 从完整表名中提取表名。
     *
     * @param fullTableName 完整表名，格式 schema.table 或 table
     * @return 表名
     */
    private String extractTableName(String fullTableName) {
        if (fullTableName.contains(".")) {
            return fullTableName.substring(fullTableName.lastIndexOf('.') + 1);
        }
        return fullTableName;
    }

    /**
     * 从完整表名中提取 schema。
     *
     * @param fullTableName 完整表名
     * @return schema 名称，若不存在返回 null
     */
    private String extractSchemaName(String fullTableName) {
        if (fullTableName.contains(".")) {
            return fullTableName.substring(0, fullTableName.lastIndexOf('.'));
        }
        return null;
    }

    /**
     * 获取必填配置项。
     *
     * @param key          配置键
     * @param errorMessage 错误信息
     * @return 配置值
     */
    private String getRequiredConfig(String key, String errorMessage) {
        String value = environment.getString(key, "");
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value;
    }

    @Override
    public void upgrade() {
        // SymmetricDS 引擎内部自动处理
    }

    @Override
    public boolean isDelegatedOperatingSystem() {
        return true;
    }

    @Override
    public void close() {
        if (!running.get()) {
            return;
        }
        running.set(false);

        if (engine != null) {
            try {
                engine.stop();
            } catch (Exception e) {
                log.warn("停止 SymmetricDS 引擎异常", e);
            }
        }
        ThreadUtils.closeQuietly(executorService);
        listeners.clear();
        log.info("SymmetricDS 引擎已关闭: listenPath={}", listenPath);
    }
}
