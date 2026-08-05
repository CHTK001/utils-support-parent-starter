package com.chua.common.support.file.tar;


/**
 * Tar 文件格式常量定义。
 *
 * @author CH
 */
public final class TarConstants {

    /**
     * 结束块大小，通常为两个 512 字节的块。
     */
    public static final int EOF_BLOCK = 1024;

    /**
     * 数据块大小，标准 Tar 格式为 512 字节。
     */
    public static final int DATA_BLOCK = 512;

    /**
     * 文件头块大小，标准 Tar 格式为 512 字节。
     */
    public static final int HEADER_BLOCK = 512;

    private TarConstants() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
