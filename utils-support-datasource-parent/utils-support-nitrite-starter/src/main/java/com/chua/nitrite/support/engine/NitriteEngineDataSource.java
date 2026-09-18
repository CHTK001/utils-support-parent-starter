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
    /** 名称 */
    public String name() {
        return name;
    }

    @Override
    /** 获取源 */
    public Object getSource() {
        return nitrite;
    }

    @Override
    /** 获取源 */
    public <R> R getSource(Class<R> type) {
        return type.cast(nitrite);
    }

    @Override
    /** 设置源 */
    public EngineDataSource<Object> setSource(Object source) {
        return this;
    }

    @Override
    /** 获取Dialect */
    public Dialect getDialect() {
        return null;
    }

    @Override
    /** 设置Dialect */
    public EngineDataSource<Object> setDialect(Dialect dialect) {
        return this;
    }

    @Override
    /** tunnel端口 */
    public int tunnelPort() {
        return 0;
    }

    @Override
    /** 设置tunnel端口 */
    public EngineDataSource<Object> setTunnelPort(int tunnelPort) {
        return this;
    }

    @Override
    /** Url */
    public String url() {
        return filePath;
    }

    @Override
    /** 用户名 */
    public String username() {
        return null;
    }

    @Override
    /** 密码 */
    public String password() {
        return null;
    }
}
