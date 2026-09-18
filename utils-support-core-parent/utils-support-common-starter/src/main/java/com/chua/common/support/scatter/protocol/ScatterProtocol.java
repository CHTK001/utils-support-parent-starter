package com.chua.common.support.scatter.protocol;

/**
 * scatter 帧协议常量（最小编码）。
 *
 * <p>帧格式（二进制紧凑，无冗余分隔符）：</p>
 * <pre>
 * [0]      magic      1B  固定 0x53 ('S')
 * [1]      type       1B  0x01 REQ / 0x02 PUSH / 0x03 RESP / 0x04 ACK / 0x05 ELEC
 * [2..5]   requestId  4B  int（大端）
 * [6]      pathLen    1B  path 字节长度（0-255）
 * [7..]    path       N B 服务路径（UTF-8）
 * [..]     payloadLen 4B  int payload 字节长度（大端）
 * [..]     payload    M B 载荷（JSON 序列化的服务表/请求参数）
 * </pre>
 * <p>单帧最小开销：固定头 10B + path。相比字符串拼接协议，无分隔符/无冗余头。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
public final class ScatterProtocol {

    /** 帧魔数 */
    public static final byte MAGIC = 'S';

    /** 类型：拉取服务表（请求） */
    public static final byte TYPE_REQ = 0x01;
    /** 类型：推送服务 hash（扩散） */
    public static final byte TYPE_PUSH = 0x02;
    /** 类型：响应（服务表/ACK） */
    public static final byte TYPE_RESP = 0x03;
    /** 类型：确认 */
    public static final byte TYPE_ACK = 0x04;
    /** 类型：选举通知 */
    public static final byte TYPE_ELEC = 0x05;

    /**
     * 构造方法，创建 ScatterProtocol 实例。
     */
    private ScatterProtocol() {
    }
}
