package com.chua.common.support.network.tcp;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
* 基于字节前缀识别的协议嗅探处理器抽象基类。
*
* <p>子类声明若干 UTF-8 文本前缀（如 {@code AUTH|}、{@code CONNECT|}），本类自动实现
* {@link #matches(byte[])}（头部以任一前缀开头且长度足够）与 {@link #isPrefix(byte[])}
* （头部是某一前缀的前缀，仍需更多字节才能判定），子类仅需实现 {@link #handle} 处理具体协议。</p>
*
* @author CH
* @since 4.0.0.42
 */
public abstract class PrefixProtocolSniffHandler implements ProtocolSniffHandler {

    /**
    * 识别前缀字节表（按构造顺序保持注册语义）
     */
    private final byte[][] prefixes;

    /**
    * 创建基于前缀识别的处理器。
    *
    * @param prefixes 协议前缀（UTF-8 文本），如 AUTH|、CONNECT|
     */
    protected PrefixProtocolSniffHandler(String... prefixes) {
        this.prefixes = new byte[prefixes.length][];
        for (int i = 0; i < prefixes.length; i++) {
            this.prefixes[i] = prefixes[i].getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public boolean matches(byte[] head) {
        for (byte[] prefix : prefixes) {
            if (head.length >= prefix.length && startsWith(head, prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPrefix(byte[] head) {
        for (byte[] prefix : prefixes) {
            if (head.length < prefix.length && startsWith(prefix, head)) {
                return true;
            }
        }
        return false;
    }

    /**
    * 判断数据是否以指定前缀开头。
    *
    * @param data   数据字节
    * @param prefix 前缀字节
    * @return true 表示数据以该前缀开头
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (prefix.length > data.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}