package com.chua.nitrite.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import org.dizitart.no2.Nitrite;

/**
 * Nitrite 数据源实现，包装 Nitrite 实例与连接信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NitriteEngineDataSource implements EngineDataSource<Object> {

    /**
     * 数据源名称
    */
    private final String name;

    /**
     * 数据库文件路径
    */
    private final String filePath;

    /**
     * Nitrite 数据库实例
    */
    private final Nitrite nitrite;

    /**
     * 构造 Nitrite 数据源。
     *
     * @param name 数据源名称
     * @param nitrite Nitrite 实例
     * @param filePath 数据库文件路径
     */
    public NitriteEngineDataSource(String name, Nitrite nitrite, String filePath) {
        this.name = name;
        this.nitrite = nitrite;
        this.filePath = filePath;
    }

    /**
     * 名称
    */
    @Override
    public String name() {
        return name;
    }

    /**
     * 获取源
    */
    @Override
    public Object getSource() {
        return nitrite;
    }

    /**
     * 获取源
    */
    @Override
    public <R> R getSource(Class<R> type) {
        return type.cast(nitrite);
    }

    /**
     * 设置源
    */
    @Override
    public EngineDataSource<Object> setSource(Object source) {
        throw new UnsupportedOperationException("运行期不支持替换数据源对象，请重新调用 addDataSource 注册新数据源");
    }

    /**
     * 获取Dialect
    */
    @Override
    public Dialect getDialect() {
        return null;
    }

    /**
     * 设置Dialect
    */
    @Override
    public EngineDataSource<Object> setDialect(Dialect dialect) {
        return this;
    }

    /**
     * tunnel端口
    */
    @Override
    public int tunnelPort() {
        return 0;
    }

    /**
     * 设置tunnel端口
    */
    @Override
    public EngineDataSource<Object> setTunnelPort(int tunnelPort) {
        return this;
    }

    /**
     * Url
    */
    @Override
    public String url() {
        return filePath;
    }

    /**
     * 用户名
    */
    @Override
    public String username() {
        return null;
    }

    /**
     * 密码
    */
    @Override
    public String password() {
        return null;
    }
}
