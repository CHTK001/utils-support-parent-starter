package com.chua.common.support.constant;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 字符集常量类
 * <p>
 * 提供常用的字符集常量，如 UTF-8、ASCII、ISO-8859-1 等。
 * </p>
 *
 * <p>
 * 常见字符集说明：
 * <ul>
 *   <li>UTF-8：可变长度字符编码，可以用来表示 Unicode 标准中任何字符，是 Web 互联网中最常用的编码方式。</li>
 *   <li>ASCII：基于拉丁字母的一套电脑编码系统，主要用于现代英语，共 128 个字符（0-127）。</li>
 *   <li>ISO-8859-1：Latin-1 字符集，单字节编码，支持 256 个字符，常用于早期 Web 和某些特定协议。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 2024-05-20
 * @version 1.0.0
 */
@SuppressWarnings("ALL")
public interface CharsetConstant {
    /**
     * UTF-8 字符集
     * <p>
     * UTF-8 是一种针对 Unicode 的可变长度字符编码。
     * 它是 Web 互联网中最常用的编码方式，能够表示 Unicode 标准中的任何字符。
     * </p>
     */
    Charset UTF_8 = StandardCharsets.UTF_8;


    /**
     * GBK 字符集
     * <p>
     * GBK 是一种中文字符编码，是中文信息交换码（GB 2312）的扩展，支持简体中文及繁体中文。
     * 它是双字节编码，能够表示 65536 个字符。
     * </p>
     */
    Charset GBK = Charset.forName("GBK");
    /**
     * ASCII 字符集
     * <p>
     * ASCII 是一种基于拉丁字母的电脑编码系统，共包含 128 个字符（0-127）。
     * 主要用于现代英语及其他西欧语言的控制字符和可打印字符表示。
     * </p>
     */
    Charset ASCII = Charset.forName("US-ASCII");

    /**
     * ISO-8859-1 字符集
     * <p>
     * ISO-8859-1 又称 Latin-1，是单字节编码，共支持 256 个字符。
     * 它是 ISO/IEC 8859 标准的一部分，常用于早期 Web 页面及某些网络协议。
     * </p>
     */
    Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
}
