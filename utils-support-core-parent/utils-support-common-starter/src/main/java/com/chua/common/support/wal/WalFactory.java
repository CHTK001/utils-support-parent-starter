package com.chua.common.support.wal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WAL 工厂方法。
 *
 * @author CH
 * @since 4.0.0.42
*/
public final class WalFactory {

    /** 创建 wal工厂 实例 */
    private WalFactory() {
    }

    /**
    * 打开或创建 WAL 实例。
    *
    * @param config 配置
    * @return WalLog 实例
    * @throws IOException 打开失败
    */
    public static WalLog open(WalConfig config) throws IOException {
        if (config == null) {
            throw new WalException("config 不能为 null");
        }
        Path dir = config.walDir();
        Files.createDirectories(dir);
        WalLog log;
        if (config.impl() == WalConfig.WalImpl.SIMPLE) {
            log = new SimpleWalLog(config);
        } else {
            log = new SegmentWalLog(config);
        }
        return log;
    }
}
