package com.chua.common.support.network.tcp;

import com.chua.common.support.network.ProtocolType;

import java.io.InputStream;
import java.io.OutputStream;

/**
* 协议嗅探处理器：负责识别 TCP 连接头部字节对应的协议，并独占处理该连接的后续字节流。
*
* <p>与 {@link ProtocolSniffingTcpServer} 配合，实现单端口多协议共存（如
* sip 认证信令与 frp 内网穿透数据平面共用同一公网端口）。服务器接受连接后
* 先窥探头部字节，按注册顺序找到第一个 {@link #matches(byte[])} 命中的处理器，
* 将已窥探字节回推后交由该处理器独占处理。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface ProtocolSniffHandler {

    /**
    * 协议类型，用于标记该处理器承载的协议。
    *
    * @return 协议类型
     */
    ProtocolType protocolType();

    /**
    * 判断给定头部字节是否属于本处理器承载的协议。
    *
    * @param head 已窥探到的头部字节（长度不超过嗅探上限）
    * @return true 表示命中，交由本处理器处理
     */
    boolean matches(byte[] head);

    /**
    * 判断给定头部字节是否可能是本处理器承载协议的前缀（数据不足，仍需继续读取）。
    *
    * <p>当连接按 TCP 分段陆续到达时，头部可能只是完整前缀的一部分（如
    * {@code AUTH|clientId|...} 只收到 {@code AUT}），此时 {@link #matches(byte[])}
    * 无法命中，但后续字节可能补全前缀，因此需要继续读取而非直接判定未知。</p>
    *
    * @param head 已窥探到的头部字节
    * @return true 表示当前字节是该协议前缀的前缀，建议继续读取
     */
    default boolean isPrefix(byte[] head) {
        return false;
    }

    /**
    * 处理一条连接：服务器已把窥探到的头部字节回推至输入流开头，
    * 处理器从输入流读取完整握手与业务数据，并向输出流写回响应。
    *
    * @param in  输入流（含已回推的头部字节）
    * @param out 输出流
    * @throws Exception 处理异常
     */
    void handle(InputStream in, OutputStream out) throws Exception;
}
