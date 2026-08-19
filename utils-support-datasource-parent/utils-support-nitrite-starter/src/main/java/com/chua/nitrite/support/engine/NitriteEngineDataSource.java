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

    /** 数据源名称 */
    private final String name;

    /** 数据库文件路径 */
    private final String filePath;

    /** Nitrite 数据库实例 */
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

    @Override
    public String name() {
        return name;
    }

    @Override
    public Object getSource() {
        return nitrite;
    }

    @Override
    public <R> R getSource(Class<R> type) {
        return type.cast(nitrite);
    }

    @Override
    public EngineDataSource<Object> setSource(Object source) {
        return this;
    }

    @Override
    public Dialect getDialect() {
        return null;
    }

    @Override
    public EngineDataSource<Object> setDialect(Dialect dialect) {
        return this;
    }

    @Override
    public int tunnelPort() {
        return 0;
    }

    @Override
    public EngineDataSource<Object> setTunnelPort(int tunnelPort) {
        return this;
    }

    @Override
    public String url() {
        return filePath;
    }

    @Override
    public String username() {
        return null;
    }

    @Override
    public String password() {
        return null;
    }
}
