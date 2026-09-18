package com.chua.common.support.network.download;

import com.chua.common.support.utils.DigestUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
* 下载通用工具方法，供 {@link DefaultDownloadService} 和 {@link Aria2DownloadService} 复用。
*
* <p>本类为纯静态工具类，禁止实例化。
*
* @author CH
* @since 4.0.0.42
 */
public final class DownloadUtils {

    /**
     * 构造方法，创建 DownloadUtils 实例。
     */
    private DownloadUtils() {
    }

    /**
    * 从 URL 中解析文件名。
    *
    * <p>优先使用显式指定的文件名；若未指定，则从 URL 路径的最后一段提取并做 URL 解码。
    *
    * @param url           下载地址
    * @param explicitName  显式指定的文件名，可为 null 或空白
    * @return 解析后的文件名
    */
    public static String resolveFilename(String url, String explicitName) {
        if (explicitName != null && !explicitName.isBlank()) {
            return explicitName;
        }
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return URLDecoder.decode(path.substring(lastSlash + 1), StandardCharsets.UTF_8);
        }
        return "download";
    }

    /**
    * 计算文件的 MD5 哈希值（32 位小写十六进制字符串）。
    *
    * <p>底层委托 {@link DigestUtils#md5Hex(Path)} 实现，使用 8KB 缓冲区分块读取，
    * 适用于大文件，避免一次性加载全部数据到内存。
    *
    * @param file 待计算 MD5 的文件
    * @return 32 位小写十六进制 MD5 字符串；计算失败时返回空字符串
    */
    public static String computeMd5(Path file) {
        try {
            return DigestUtils.md5Hex(file);
        } catch (IOException e) {
            return "";
        }
    }
}
