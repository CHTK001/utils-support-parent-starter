package com.chua.debezium.support.environment;

import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Debezium 环境配置构造器。
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * DirectoryPollerEnvironment env = DebeziumEnvironment.mysql("cdc-1")
 *     .host("localhost").port(3306).username("root").password("pass").database("mydb")
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 2024/12/12
 */
public class DebeziumEnvironment {

    /**
     * 连接器名属性键
     */
    public static final String KEY_CONNECTOR_NAME = "debezium.connector.name";

    /**
     * 连接器类型属性键
     */
    public static final String KEY_CONNECTOR_TYPE = "debezium.connector.type";

    /**
     * 数据库主机属性键
     */
    public static final String KEY_HOST = "db.host";

    /**
     * 数据库端口属性键
     */
    public static final String KEY_PORT = "db.port";

    /**
     * 数据库用户名属性键
     */
    public static final String KEY_USERNAME = "db.username";

    /**
     * 数据库密码属性键
     */
    public static final String KEY_PASSWORD = "db.password";

    /**
     * 数据库名属性键
     */
    public static final String KEY_DATABASE = "db.name";

    /**
     * PostgreSQL 复制槽名属性键
     */
    public static final String KEY_SLOT_NAME = "slot.name";

    /**
     * PostgreSQL 解码插件名属性键
     */
    public static final String KEY_PLUGIN_NAME = "plugin.name";

    /**
     * 自动配置开关属性键
     */
    public static final String KEY_AUTO_SETUP = "debezium.auto.setup";

    /**
     * MongoDB 连接器类型标识
     */
    private static final String CONNECTOR_TYPE_MONGODB = "mongodb";

    /**
     * 私有构造。
     */
    private DebeziumEnvironment() {
    }

    /**
     * 创建通用 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * MySQL CDC Builder 快捷入口。
     *
     * @param connectorName 连接器名
     * @return Builder 实例
     */
    public static Builder mysql(String connectorName) {
        return builder().connectorName(connectorName).connectorType("mysql");
    }

    /**
     * PostgreSQL CDC Builder 快捷入口。
     *
     * @param connectorName 连接器名
     * @return Builder 实例
     */
    public static Builder postgres(String connectorName) {
        return builder().connectorName(connectorName).connectorType("postgres");
    }

    /**
     * Oracle CDC Builder 快捷入口。
     *
     * @param connectorName 连接器名
     * @return Builder 实例
     */
    public static Builder oracle(String connectorName) {
        return builder().connectorName(connectorName).connectorType("oracle");
    }

    /**
     * SQL Server CDC Builder 快捷入口。
     *
     * @param connectorName 连接器名
     * @return Builder 实例
     */
    public static Builder sqlserver(String connectorName) {
        return builder().connectorName(connectorName).connectorType("sqlserver");
    }

    /**
     * MongoDB CDC Builder 快捷入口。
     *
     * @param connectorName 连接器名
     * @return Builder 实例
     */
    public static Builder mongodb(String connectorName) {
        return builder().connectorName(connectorName).connectorType(CONNECTOR_TYPE_MONGODB);
    }

    /**
     * MariaDB CDC Builder 快捷入口。
     *
     * @param connectorName 连接器名
     * @return Builder 实例
     */
    public static Builder mariadb(String connectorName) {
        return builder().connectorName(connectorName).connectorType("mariadb");
    }

    /**
     * Debezium 环境配置 Builder。
     */
    public static class Builder {

        /**
         * 配置属性集合
         */
        private final Map<String, String> props = new LinkedHashMap<>();

        /**
         * 轮询间隔
         */
        private long pollingInterval = 10;

        /**
         * 轮询间隔单位
         */
        private TimeUnit timeUnit = TimeUnit.SECONDS;

        /**
         * 监听事件集合
         */
        private Set<WatcherEvent> events = Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY, WatcherEvent.DELETE);

        /**
         * 设置连接器名。
         *
         * @param name 连接器名
         * @return 当前 Builder
         */
        public Builder connectorName(String name) {
            props.put(KEY_CONNECTOR_NAME, name);
            return this;
        }

        /**
         * 设置连接器类型。
         *
         * @param type 连接器类型
         * @return 当前 Builder
         */
        public Builder connectorType(String type) {
            props.put(KEY_CONNECTOR_TYPE, type);
            return this;
        }

        /**
         * 设置数据库主机。
         *
         * @param host 主机地址
         * @return 当前 Builder
         */
        public Builder host(String host) {
            props.put(KEY_HOST, host);
            return this;
        }

        /**
         * 设置数据库端口（字符串）。
         *
         * @param port 端口字符串
         * @return 当前 Builder
         */
        public Builder port(String port) {
            props.put(KEY_PORT, port);
            return this;
        }

        /**
         * 设置数据库端口（int）。
         *
         * @param port 端口数值
         * @return 当前 Builder
         */
        public Builder port(int port) {
            props.put(KEY_PORT, String.valueOf(port));
            return this;
        }

        /**
         * 设置数据库用户名。
         *
         * @param username 用户名
         * @return 当前 Builder
         */
        public Builder username(String username) {
            props.put(KEY_USERNAME, username);
            return this;
        }

        /**
         * 设置数据库密码。
         *
         * @param password 密码
         * @return 当前 Builder
         */
        public Builder password(String password) {
            props.put(KEY_PASSWORD, password);
            return this;
        }

        /**
         * 设置数据库名。
         *
         * @param database 数据库名
         * @return 当前 Builder
         */
        public Builder database(String database) {
            props.put(KEY_DATABASE, database);
            return this;
        }

        /**
         * 设置 PostgreSQL 复制槽名。
         *
         * @param slotName 复制槽名
         * @return 当前 Builder
         */
        public Builder slotName(String slotName) {
            props.put(KEY_SLOT_NAME, slotName);
            return this;
        }

        /**
         * 设置 PostgreSQL 解码插件名。
         *
         * @param pluginName 插件名
         * @return 当前 Builder
         */
        public Builder pluginName(String pluginName) {
            props.put(KEY_PLUGIN_NAME, pluginName);
            return this;
        }

        /**
         * 设置是否自动初始化环境。
         *
         * @param autoSetup 是否自动配置
         * @return 当前 Builder
         */
        public Builder autoSetup(boolean autoSetup) {
            props.put(KEY_AUTO_SETUP, String.valueOf(autoSetup));
            return this;
        }

        /**
         * 设置任意自定义属性。
         *
         * @param key   属性键
         * @param value 属性值
         * @return 当前 Builder
         */
        public Builder property(String key, String value) {
            props.put(key, value);
            return this;
        }

        /**
         * 设置轮询间隔。
         *
         * @param interval 间隔
         * @param unit     时间单位
         * @return 当前 Builder
         */
        public Builder pollingInterval(long interval, TimeUnit unit) {
            this.pollingInterval = interval;
            this.timeUnit = unit;
            return this;
        }

        /**
         * 设置监听事件类型。
         *
         * @param events 事件类型
         * @return 当前 Builder
         */
        public Builder events(WatcherEvent... events) {
            this.events = Set.of(events);
            return this;
        }

        /**
         * 校验必填属性并返回所有缺失键。
         *
         * @return 缺失属性名列表，空表示全部有效
         */
        public List<String> validate() {
            List<String> missing = new ArrayList<>();
            String type = props.get(KEY_CONNECTOR_TYPE);
            if (type == null) {
                missing.add(KEY_CONNECTOR_TYPE);
                return missing;
            }
            if (props.get(KEY_CONNECTOR_NAME) == null) {
                missing.add(KEY_CONNECTOR_NAME);
            }
            // 关系型数据库需要 host / username
            if (!CONNECTOR_TYPE_MONGODB.equals(type)) {
                if (props.get(KEY_HOST) == null) {
                    missing.add(KEY_HOST);
                }
                if (props.get(KEY_USERNAME) == null) {
                    missing.add(KEY_USERNAME);
                }
            }
            return missing;
        }

        /**
         * 校验必填属性，失败时抛出 {@link IllegalArgumentException}。
         *
         * @return this
         * @throws IllegalArgumentException 缺少必填属性时抛出
         */
        public Builder check() {
            List<String> missing = validate();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("缺少必填的 Debezium 环境属性: " + String.join(", ", missing));
            }
            return this;
        }

        /**
         * 构建 DirectoryPollerEnvironment。
         *
         * @return 已配置的环境实例
         */
        public DirectoryPollerEnvironment build() {
            check();
            DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(events, pollingInterval, timeUnit);
            props.forEach(env::setProperty);
            return env;
        }
    }
}
