package com.chua.smb.server;

import com.chua.common.support.nativeapi.NativeServices;
import com.chua.common.support.nativeapi.SmbServerSpi;

/**
 * SMB 服务端原生库门面。
 *
 * <p>实现由 {@code utils-support-native-smb} 通过 SPI 注册；本类只依赖
 * {@link SmbServerSpi} 契约，不再反射 native 类。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class RustSmbServerBridge {

    private static final String CAPABILITY = "SMB 服务端能力";

    private RustSmbServerBridge() {
    }

    static void loadLibrary() {
        SmbServerSpi spi = require();
        spi.loadLibrary();
        if (!spi.isLoaded()) {
            throw new IllegalStateException(CAPABILITY + "不可用：原生库未加载");
        }
    }

    static long start(String bindAddr, int port, String shareName, String rootPath,
                      String user, String password) {
        return require().start(bindAddr, port, shareName, rootPath, user, password);
    }

    static void stop(long handle) {
        require().stop(handle);
    }

    private static SmbServerSpi require() {
        return NativeServices.require(SmbServerSpi.class, CAPABILITY);
    }
}
