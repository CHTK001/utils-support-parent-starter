package com.chua.qqwry.support;

import com.chua.common.support.network.ip.IpLocation;
import com.chua.common.support.network.ip.IpPosition;
import com.chua.common.support.spi.annotations.Spi;

/**
 * qqwry IP 地理位置查询 SPI 实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("qqwry")
public class QqwryIpPosition implements IpPosition {

    /**
     * 默认_db
    */
    private static final String DEFAULT_DB = "qqwry.dat";

    /**
     * 读取器
    */
    private final QQWryReader reader;

    /**
     * 使用默认路径构造。
     */
    public QqwryIpPosition() {
        this(DEFAULT_DB);
    }

    /**
     * 指定数据库文件路径构造。
     *
     * @param dbPath 数据库文件路径
     */
    public QqwryIpPosition(String dbPath) {
        try {
            this.reader = new QQWryReader(dbPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load QQWry database: " + dbPath
                    + "，请自行添加依赖 com.chua:utils-support-resource-qqwry:4.0.0.43", e);
        }
    }

    /**
     * 查询
    */
    @Override
    public IpLocation query(String ip) {
        return reader.query(ip);
    }
}
