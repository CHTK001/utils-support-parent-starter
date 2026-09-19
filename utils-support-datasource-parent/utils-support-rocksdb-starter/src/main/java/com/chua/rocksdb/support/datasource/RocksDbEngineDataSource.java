package com.chua.rocksdb.support.datasource;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import org.rocksdb.RocksDB;

/**
 * RocksDB 数据源封装，持有真实 {@link RocksDB} 实例（嵌入式本地文件目录）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RocksDbEngineDataSource implements EngineDataSource<RocksDB> {

    /**
     * 数据源名称
    */
    private final String name;

    /**
     * RocksDB 数据库实例
    */
    private final RocksDB source;

    /**
     * 数据库目录路径
    */
    private final String url;

    /**
     * 构造数据源。
     *
     * @param name   数据源名称
     * @param url    数据库目录路径
     * @param source RocksDB 数据库实例
     */
    public RocksDbEngineDataSource(String name, String url, RocksDB source) {
        this.name = name;
        this.url = url;
        this.source = source;
    }

    /**
     * 获取名称
    */
    @Override
    public String name() {
        return name;
    }

    /**
     * 获取数据库实例
    */
    @Override
    public RocksDB getSource() {
        return source;
    }

    /**
     * 获取指定类型的数据库实例
    */
    @Override
    public <R> R getSource(Class<R> type) {
        return type.isInstance(source) ? type.cast(source) : null;
    }

    /**
     * 不支持运行期替换连接（替换 需 重建 数据源 实例，显式 拒绝 避免 静默 no-op）
     */
    @Override
    public EngineDataSource<RocksDB> setSource(Object source) {
        throw new UnsupportedOperationException("RocksDbEngineDataSource 不支持 运行期 替换 底层 RocksDB 实例");
    }

    /**
     * 非 SQL 方言返回 空
    */
    @Override
    public Dialect getDialect() {
        return null;
    }

    /**
     * 忽略方言设置
    */
    @Override
    public EngineDataSource<RocksDB> setDialect(Dialect dialect) {
        return this;
    }

    /**
     * 数据库目录路径
    */
    @Override
    public String url() {
        return url;
    }

    /**
     * 无用户名概念
    */
    @Override
    public String username() {
        return null;
    }

    /**
     * 无密码概念
    */
    @Override
    public String password() {
        return null;
    }

    /**
     * 关闭底层连接
    */
    @Override
    public void close() {
        if (source != null) {
            source.close();
        }
    }
}
