package com.chua.common.support.nativeapi;

/**
 * SMB 服务端原生库 SPI。
 *
 * <p>由 {@code utils-support-native-smb} 提供实现。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface SmbServerSpi extends NativeService {

    /**
     * 加载原生库。
     */
    void loadLibrary();

    /**
     * 原生库是否已加载。
     *
     * @return 已加载返回 {@code true}
     */
    boolean isLoaded();

    @Override
    default boolean available() {
        if (isLoaded()) {
            return true;
        }
        loadLibrary();
        return isLoaded();
    }

    /**
     * 启动 SMB 服务端。
     *
     * @param bindAddr  绑定地址
     * @param port      端口
     * @param shareName 共享名
     * @param rootPath  根目录
     * @param user      用户名
     * @param password  口令
     * @return 服务端句柄；失败返回负数
     */
    long start(String bindAddr, int port, String shareName, String rootPath,
               String user, String password);

    /**
     * 停止 SMB 服务端。
     *
     * @param handle 服务端句柄
     */
    void stop(long handle);

    /**
     * 列出共享。
     *
     * @param handle 服务端句柄
     * @return 共享名数组
     */
    String[] listShares(long handle);
}
