package com.chua.common.support.nativeapi;

/**
 * RDP 直连原生桥 SPI。
 *
 * <p>由 {@code utils-support-native-rdp} 提供实现（rdpj.dll + JNA）。句柄统一用
 * {@code long} 透传，避免契约层依赖 JNA 类型；帧头为 8 个 int
 * （width, height, stride, bpp, x, y, cx, cy），{@link #frameHeader(long, int[])}
 * 只回填前 4 个调用方实际需要的字段。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface RdpNativeSpi extends NativeService {

    /**
     * 创建 RDP 实例。
     *
     * @return 原生句柄；失败返回 0
     */
    long create();

    /**
     * 请求桌面尺寸。
     *
     * @param handle 句柄
     * @param width  宽
     * @param height 高
     * @return 0 表示成功
     */
    int resize(long handle, int width, int height);

    /**
     * 发起连接（异步）。
     *
     * @param handle   句柄
     * @param host     主机
     * @param port     端口
     * @param user     用户名
     * @param password 口令
     * @param locale   区域
     * @return 0 表示已启动
     */
    int connect(long handle, String host, int port, String user, String password, String locale);

    /**
     * 连接状态。
     *
     * @param handle 句柄
     * @return 0 连接中 1 已连接 2 已关闭 -1 失败
     */
    int state(long handle);

    /**
     * 最近一次错误描述。
     *
     * @param handle 句柄
     * @return 错误文本
     */
    String lastError(long handle);

    /**
     * 读取当前帧头。
     *
     * @param handle 句柄
     * @param header 输出数组，至少 4 个元素：width / height / stride / bpp
     * @return 有新帧返回 {@code true}
     */
    boolean frameHeader(long handle, int[] header);

    /**
     * 取增量帧（32 字节帧头 + 像素）。
     *
     * @param handle 句柄
     * @param buffer 输出缓冲，长度需按帧头计算
     * @return 写入字节数；无新帧返回 0，失败返回负数
     */
    int frame(long handle, byte[] buffer);

    /**
     * 投递鼠标事件。
     *
     * @param handle 句柄
     * @param x      坐标 X
     * @param y      坐标 Y
     * @param button 按钮
     * @param down   按下标志
     * @return 0 表示已入队
     */
    int mouse(long handle, int x, int y, int button, int down);

    /**
     * 投递键盘事件。
     *
     * @param handle 句柄
     * @param code   RDP scancode
     * @param down   按下标志
     * @return 0 表示已入队
     */
    int key(long handle, short code, int down);

    /**
     * 投递滚轮事件。
     *
     * @param handle 句柄
     * @param x      坐标 X
     * @param y      坐标 Y
     * @param delta  滚动量
     * @return 0 表示已入队
     */
    int wheel(long handle, int x, int y, int delta);

    /**
     * 关闭连接。
     *
     * @param handle 句柄
     */
    void close(long handle);

    /**
     * 释放句柄。
     *
     * @param handle 句柄
     */
    void destroy(long handle);
}
