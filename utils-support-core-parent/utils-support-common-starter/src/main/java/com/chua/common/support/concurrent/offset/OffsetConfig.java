package com.chua.common.support.concurrent.offset;

import java.nio.file.Path;

/**
* offset 配置。
*
* @author CH
* @since 4.0.0.43
 */
public class OffsetConfig {

    /**
    * 是否持久化到文件
    */
    private boolean persistent = true;

    /**
    * 文件存储根目录
    */
    private Path basePath = Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "datalake", "offset");

    /**
    * 字节缓存刷新间隔（毫秒）
    */
    private long flushInterval = 5000L;

    /**
    * SPI provider 名称，默认 "file"
    */
    private String provider = "file";

    /**
     * 构造方法，创建 偏移量配置 实例。
     */
    OffsetConfig() {
    }

    /**
     * 构造方法，创建 偏移量配置 实例。
     *
     * @param basePath base路径，不允许为 null
     * @param persistent persistent（布尔开关）
     */
    OffsetConfig(Path basePath, boolean persistent) {
        this.basePath = basePath;
        this.persistent = persistent;
    }

    /**
     * 获取BasePath
     * @return 路径 对象
     */
    public Path getBasePath() {
        return basePath;
    }

    /**
     * 是否Persistent
     * @return 是否成功（true 表示成功）
     */
    public boolean isPersistent() {
        return persistent;
    }

    /**
     * 获取刷新Interval
     * @return 结果数值
     */
    public long getFlushInterval() {
        return flushInterval;
    }

    /**
     * 获取Provider
     * @return 结果字符串
     */
    public String getProvider() {
        return provider;
    }

    /**
    * 设置文件存储根目录
    *
    * @param basePath 根目录
    * @return this
    */
    public OffsetConfig setBasePath(Path basePath) {
        this.basePath = basePath;
        return this;
    }

    /**
    * 设置是否持久化
    *
    * @param persistent 持久化标志
    * @return this
    */
    public OffsetConfig setPersistent(boolean persistent) {
        this.persistent = persistent;
        return this;
    }

    /**
    * 设置刷新间隔（毫秒）
    *
    * @param flushInterval 刷新间隔
    * @return this
    */
    public OffsetConfig setFlushInterval(long flushInterval) {
        this.flushInterval = flushInterval;
        return this;
    }

    /**
    * 设置 SPI provider 名称
    *
    * @param provider provider 名
    * @return this
    */
    public OffsetConfig setProvider(String provider) {
        this.provider = provider;
        return this;
    }

    /**
    * 创建默认配置。
    *
    * @return 默认配置
    */
    public static OffsetConfig createDefault() {
        return new OffsetConfig();
    }
}
