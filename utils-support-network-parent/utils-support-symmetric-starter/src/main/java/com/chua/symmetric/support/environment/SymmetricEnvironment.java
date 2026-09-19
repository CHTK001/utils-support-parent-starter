package com.chua.symmetric.support.environment;

import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * symmetricds 环境配置构造器。
 *
 * <p>提供类型安全的 Builder API，快速创建各数据库的 SymmetricDS 同步环境配置。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * DirectoryPollerEnvironment env = SymmetricEnvironment.mysql("sym-node-1")
 *     .groupId("store").externalId("node-1")
 *     .host("localhost").username("root").password("pass").database("mydb")
 *     .autoSetup(true)
 *     .build();
 * }</pre>database("mydb")
 *     .autoSetup(true)
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SymmetricEnvironment {

    /**
     * 键 engine 名称
     */
    public static final String KEY_ENGINE_NAME = "symmetric.engine.name";
    /**
     * 键 db 类型
     */
    public static final String KEY_DB_TYPE = "symmetric.db.type";
    /**
     * 键 群体 标识
     */
    public static final String KEY_GROUP_ID = "symmetric.group.id";
    /**
     * 键 外部 标识
     */
    public static final String KEY_EXTERNAL_ID = "symmetric.external.id";
    /**
     * 键 registration URL
     */
    public static final String KEY_REGISTRATION_URL = "symmetric.registration.url";
    /**
     * 键 同步 URL
     */
    public static final String KEY_SYNC_URL = "symmetric.sync.url";
    /**
     * 键 主机
     */
    public static final String KEY_HOST = "db.host";
    /**
     * 键 端口
     */
    public static final String KEY_PORT = "db.port";
    /**
     * 键 用户名
     */
    public static final String KEY_USERNAME = "db.username";
    /**
     * 键 密码
     */
    public static final String KEY_PASSWORD = "db.password";
    /**
     * 键 database
     */
    public static final String KEY_DATABASE = "db.name";
    /**
     * 键 table include 列表
     */
    public static final String KEY_TABLE_INCLUDE_LIST = "symmetric.table.include.list";
    /**
     * 键 table exclude 列表
     */
    public static final String KEY_TABLE_EXCLUDE_LIST = "symmetric.table.exclude.list";
    /**
     * 键 auto 创建 tables
     */
    public static final String KEY_AUTO_CREATE_TABLES = "symmetric.auto.create.tables";
    /**
     * 键 初始 加载
     */
    public static final String KEY_INITIAL_LOAD = "symmetric.initial.load";
    /**
     * 键 auto 注册
     */
    public static final String KEY_AUTO_REGISTER = "symmetric.auto.register";
    /**
     * 键 auto setup
     */
    public static final String KEY_AUTO_SETUP = "symmetric.auto.setup";

    /**
     * 创建 symmetric环境 实例
    */
    private SymmetricEnvironment() {}

    /**
     * 创建通用的 构建器。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建 MySQL 环境的 构建器。
     * @param engineName engine名称
     * @return mysql的结果
     */
    public static Builder mysql(String engineName) { return builder().engineName(engineName).dbType("mysql"); }
    /**
     * 创建 PostgreSQL 环境的 构建器。
     * @param engineName engine名称
     * @return postgres的结果
     */
    public static Builder postgres(String engineName) { return builder().engineName(engineName).dbType("postgres"); }
    /**
     * 创建 Oracle 环境的 构建器。
     * @param engineName engine名称
     * @return oracle的结果
     */
    public static Builder oracle(String engineName) { return builder().engineName(engineName).dbType("oracle"); }
    /**
     * 创建 SQL 服务端 环境的 构建器。
     * @param engineName engine名称
     * @return sqlserver的结果
     */
    public static Builder sqlserver(String engineName) { return builder().engineName(engineName).dbType("sqlserver"); }
    /**
     * 创建 mariadb 环境的 构建器。
     * @param engineName engine名称
     * @return mariadb的结果
     */
    public static Builder mariadb(String engineName) { return builder().engineName(engineName).dbType("mariadb"); }
    /**
     * 创建 DB2 环境的 构建器。
     * @param engineName engine名称
     * @return db2的结果
     */
    public static Builder db2(String engineName) { return builder().engineName(engineName).dbType("db2"); }
    /**
     * 创建 Informix 环境的 构建器。
     * @param engineName engine名称
     * @return informix的结果
     */
    public static Builder informix(String engineName) { return builder().engineName(engineName).dbType("informix"); }
    /**
     * 创建 H2 环境的 构建器。
     * @param engineName engine名称
     * @return h2的结果
     */
    public static Builder h2(String engineName) { return builder().engineName(engineName).dbType("h2"); }
    /**
     * 创建 Derby 环境的 构建器。
     * @param engineName engine名称
     * @return derby的结果
     */
    public static Builder derby(String engineName) { return builder().engineName(engineName).dbType("derby"); }
    /**
     * 创建 Firebird 环境的 构建器。
     * @param engineName engine名称
     * @return firebird的结果
     */
    public static Builder firebird(String engineName) { return builder().engineName(engineName).dbType("firebird"); }
    /**
     * 创建 Sybase ASE 环境的 构建器。
     * @param engineName engine名称
     * @return ase的结果
     */
    public static Builder ase(String engineName) { return builder().engineName(engineName).dbType("ase"); }
    /**
     * 创建 SQL Anywhere 环境的 构建器。
     * @param engineName engine名称
     * @return sqlanywhere的结果
     */
    public static Builder sqlanywhere(String engineName) { return builder().engineName(engineName).dbType("sqlanywhere"); }
    /**
     * 创建 Vertica 环境的 构建器。
     * @param engineName engine名称
     * @return vertica的结果
     */
    public static Builder vertica(String engineName) { return builder().engineName(engineName).dbType("vertica"); }
    /**
     * 创建 click房子 环境的 构建器。
     * @param engineName engine名称
     * @return clickhouse的结果
     */
    public static Builder clickhouse(String engineName) { return builder().engineName(engineName).dbType("clickhouse"); }

    /**
     * symmetricds 环境配置 构建器。
     * @author CH
     * @since 4.0.0
     */
    public static class Builder {
        /**
         * props
         */
        private final Map<String, String> props = new LinkedHashMap<>();
        /**
         * polling间隔
         */
        private long pollingInterval = 10;
        /**
         * 时间 Unit
         */
        private TimeUnit timeUnit = TimeUnit.SECONDS;
        /**
         * 事件
         */
        private Set<WatcherEvent> events = Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY, WatcherEvent.DELETE);

        /**
         * engine名称
         *
         * @param engineName engine名称
         * @return engine名称的结果
         */
        public Builder engineName(String engineName) {
            props.put(KEY_ENGINE_NAME, engineName);
            return this;
        }
        /**
         * db类型
         *
         * @param dbType db类型
         * @return db类型的结果
         */
        public Builder dbType(String dbType) {
            props.put(KEY_DB_TYPE, dbType);
            return this;
        }
        /**
         * 分组标识
         *
         * @param groupId 群体标识
         * @return 群体id的结果
         */
        public Builder groupId(String groupId) {
            props.put(KEY_GROUP_ID, groupId);
            return this;
        }
        /**
         * 外部id
         *
         * @param externalId 外部标识
         * @return 外部id的结果
         */
        public Builder externalId(String externalId) {
            props.put(KEY_EXTERNAL_ID, externalId);
            return this;
        }
        /**
         * registrationurl
         *
         * @param url url
         * @return registrationUrl的结果
         */
        public Builder registrationUrl(String url) {
            props.put(KEY_REGISTRATION_URL, url);
            return this;
        }
        /**
         * 同步url
         *
         * @param url url
         * @return 同步url的结果
         */
        public Builder syncUrl(String url) {
            props.put(KEY_SYNC_URL, url);
            return this;
        }
        /**
         * 主机
         *
         * @param host 主机
         * @return 主机的结果
         */
        public Builder host(String host) {
            props.put(KEY_HOST, host);
            return this;
        }
        /**
         * 端口
         *
         * @param port 端口
         * @return 端口的结果
         */
        public Builder port(String port) {
            props.put(KEY_PORT, port);
            return this;
        }
        /**
         * 端口
         *
         * @param port 端口
         * @return 端口的结果
         */
        public Builder port(int port) {
            props.put(KEY_PORT, String.valueOf(port));
            return this;
        }
        /**
         * 用户名
         *
         * @param username 用户名
         * @return 用户名的结果
         */
        public Builder username(String username) {
            props.put(KEY_USERNAME, username);
            return this;
        }
        /**
         * 密码
         *
         * @param password 密码
         * @return 密码的结果
         */
        public Builder password(String password) {
            props.put(KEY_PASSWORD, password);
            return this;
        }
        /**
         * Database
         *
         * @param database database
         * @return database的结果
         */
        public Builder database(String database) {
            props.put(KEY_DATABASE, database);
            return this;
        }
        /**
         * tableinclude列表
         *
         * @param list 列表
         * @return tableinclude列表的结果
         */
        public Builder tableIncludeList(String list) {
            props.put(KEY_TABLE_INCLUDE_LIST, list);
            return this;
        }
        /**
         * tableexclude列表
         *
         * @param list 列表
         * @return tableexclude列表的结果
         */
        public Builder tableExcludeList(String list) {
            props.put(KEY_TABLE_EXCLUDE_LIST, list);
            return this;
        }
        /**
         * Auto创建Tables
         *
         * @param v v
         * @return auto创建tables的结果
         */
        public Builder autoCreateTables(boolean v) {
            props.put(KEY_AUTO_CREATE_TABLES, String.valueOf(v));
            return this;
        }
        /**
         * Initial加载
         *
         * @param v v
         * @return initial加载的结果
         */
        public Builder initialLoad(boolean v) {
            props.put(KEY_INITIAL_LOAD, String.valueOf(v));
            return this;
        }
        /**
         * Auto注册
         *
         * @param v v
         * @return auto注册的结果
         */
        public Builder autoRegister(boolean v) {
            props.put(KEY_AUTO_REGISTER, String.valueOf(v));
            return this;
        }
        /**
         * autosetup
         *
         * @param v v
         * @return autoSetup的结果
         */
        public Builder autoSetup(boolean v) {
            props.put(KEY_AUTO_SETUP, String.valueOf(v));
            return this;
        }
        /**
         * 财产
         *
         * @param key 键
         * @param value 值
         * @return 财产的结果
         */
        public Builder property(String key, String value) {
            props.put(key, value);
            return this;
        }
        /**
         * polling间隔
         *
         * @param interval 间隔
         * @param unit unit
         * @return polling间隔的结果
         */
        public Builder pollingInterval(long interval, TimeUnit unit) {
            this.pollingInterval = interval;
            this.timeUnit = unit;
            return this;
        }
        /**
         * 事件
         *
         * @param events 事件
         * @return 事件的结果
         */
        public Builder events(WatcherEvent... events) {
            this.events = Set.of(events);
            return this;
        }

        /**
         * 构建 目录poller环境。
         *
         * @return 环境配置对象
         */
        public DirectoryPollerEnvironment build() {
            DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(events, pollingInterval, timeUnit);
            props.forEach(env::setProperty);
            return env;
        }
    }
}
