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
 * <p>
 * 提供类型安全的 Builder API，快速创建 MySQL、PostgreSQL、MongoDB 等 CDC 环境配置。
 * 最终通过 {@link #build()} 输出 {@link DirectoryPollerEnvironment}。
 * </p>
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

    public static final String KEY_CONNECTOR_NAME = "debezium.connector.name";
    public static final String KEY_CONNECTOR_TYPE = "debezium.connector.type";
    public static final String KEY_HOST = "db.host";
    public static final String KEY_PORT = "db.port";
    public static final String KEY_USERNAME = "db.username";
    public static final String KEY_PASSWORD = "db.password";
    public static final String KEY_DATABASE = "db.name";
    public static final String KEY_SLOT_NAME = "slot.name";
    public static final String KEY_PLUGIN_NAME = "plugin.name";
    public static final String KEY_AUTO_SETUP = "debezium.auto.setup";

    private DebeziumEnvironment() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder mysql(String connectorName) {
        return builder().connectorName(connectorName).connectorType("mysql");
    }

    public static Builder postgres(String connectorName) {
        return builder().connectorName(connectorName).connectorType("postgres");
    }

    public static Builder oracle(String connectorName) {
        return builder().connectorName(connectorName).connectorType("oracle");
    }

    public static Builder sqlserver(String connectorName) {
        return builder().connectorName(connectorName).connectorType("sqlserver");
    }

    public static Builder mongodb(String connectorName) {
        return builder().connectorName(connectorName).connectorType("mongodb");
    }

    public static Builder mariadb(String connectorName) {
        return builder().connectorName(connectorName).connectorType("mariadb");
    }

    public static class Builder {
        private final Map<String, String> props = new LinkedHashMap<>();
        private long pollingInterval = 10;
        private TimeUnit timeUnit = TimeUnit.SECONDS;
        private Set<WatcherEvent> events = Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY, WatcherEvent.DELETE);

        public Builder connectorName(String name) {
            props.put(KEY_CONNECTOR_NAME, name);
            return this;
        }

        public Builder connectorType(String type) {
            props.put(KEY_CONNECTOR_TYPE, type);
            return this;
        }

        public Builder host(String host) {
            props.put(KEY_HOST, host);
            return this;
        }

        public Builder port(String port) {
            props.put(KEY_PORT, port);
            return this;
        }

        public Builder port(int port) {
            props.put(KEY_PORT, String.valueOf(port));
            return this;
        }

        public Builder username(String username) {
            props.put(KEY_USERNAME, username);
            return this;
        }

        public Builder password(String password) {
            props.put(KEY_PASSWORD, password);
            return this;
        }

        public Builder database(String database) {
            props.put(KEY_DATABASE, database);
            return this;
        }

        public Builder slotName(String slotName) {
            props.put(KEY_SLOT_NAME, slotName);
            return this;
        }

        public Builder pluginName(String pluginName) {
            props.put(KEY_PLUGIN_NAME, pluginName);
            return this;
        }

        public Builder autoSetup(boolean autoSetup) {
            props.put(KEY_AUTO_SETUP, String.valueOf(autoSetup));
            return this;
        }

        public Builder property(String key, String value) {
            props.put(key, value);
            return this;
        }

        public Builder pollingInterval(long interval, TimeUnit unit) {
            this.pollingInterval = interval;
            this.timeUnit = unit;
            return this;
        }

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
            if (!"mongodb".equals(type)) {
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

        public DirectoryPollerEnvironment build() {
            check();
            DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(events, pollingInterval, timeUnit);
            props.forEach(env::setProperty);
            return env;
        }
    }
}
