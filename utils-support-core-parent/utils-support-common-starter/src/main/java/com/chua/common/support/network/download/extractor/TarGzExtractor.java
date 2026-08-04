package com.chua.common.support.network.download.extractor;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.NullUnmarked;

/**
 * TAR.GZ 解压器
 * <p>
 * 使用 Java GZIPInputStream 解压缩后解析 TAR 格式
 *
 * @author CH
 * @version 1.0.0
 * @since 2025/11/29
 */
@NullUnmarked
@Slf4j
@Spi({"tar.gz", "tgz"})
public class TarGzExtractor implements Extractor {

    /**
     * TAR 标准块大小
     */
    private static final int TAR_BLOCK_SIZE = 512;

    /**
     * TAR 文件头大小
     */
    private static final int TAR_HEADER_SIZE = 512;

    @Override
    public String[] supportedExtensions() {
        return new String[]{".tar.gz", ".tgz"};
    }

    @Override
    public boolean extract(File sourceFile, File targetDir) {
        if (log.isDebugEnabled()) {
            log.debug("开始解压 TAR.GZ 文件：{} -> {}", sourceFile.getName(), targetDir.getAbsolutePath());
        }

        try (FileInputStream fis = new FileInputStream(sourceFile);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             BufferedInputStream bis = new BufferedInputStream(gzis)) {

            extractTar(bis, targetDir);
            if (log.isDebugEnabled()) {
                log.debug("TAR.GZ 文件解压成功：{}", sourceFile.getName());
            }
            return true;
        } catch (Exception e) {
            log.error("解压 TAR.GZ 文件失败：{}", sourceFile.getName(), e);
            return false;
        }
    }

    /**
     * 解压 TAR 格式数据
     *
     * @param inputStream TAR 流输入源
     * @param targetDir   目标解压目录
     * @throws IOException IO 异常
     */
    protected void extractTar(InputStream inputStream, File targetDir) throws IOException {
        byte[] header = new byte[TAR_HEADER_SIZE];

        while (true) {
            int bytesRead = readFully(inputStream, header);
            if (bytesRead < TAR_HEADER_SIZE) {
                break;
            }

            // 检查是否为空块（两个连续空块表示文件结束）
            if (isEmptyBlock(header)) {
                break;
            }

            // 解析 TAR 文件头
            TarHeader tarHeader = parseTarHeader(header);
            if (tarHeader == null || tarHeader.name.isEmpty()) {
                break;
            }

            File entryFile = new File(targetDir, tarHeader.name);

            // 防止路径穿越攻击
            if (!entryFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                log.warn("检测到路径穿越风险，跳过文件：{}", tarHeader.name);
                skipBytes(inputStream, tarHeader.size);
                continue;
            }

            if (tarHeader.isDirectory) {
                entryFile.mkdirs();
            } else {
                // 创建父目录
                entryFile.getParentFile().mkdirs();

                // 写入文件内容
                try (FileOutputStream fos = new FileOutputStream(entryFile)) {
                    copyBytes(inputStream, fos, tarHeader.size);
                }

                // 跳过 TAR 对齐填充字节
                int padding = (TAR_BLOCK_SIZE - (int) (tarHeader.size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE;
                skipBytes(inputStream, padding);
            }
        }
    }

    /**
     * 解析 TAR 文件头信息
     *
     * @param header 文件头字节数组
     * @return TAR 头部信息对象
     */
    private TarHeader parseTarHeader(byte[] header) {
        String name = extractString(header, 0, 100);
        String sizeStr = extractString(header, 124, 12).trim();
        long size;
        if (sizeStr.isEmpty()) {
            size = 0;
        } else {
            try {
                size = Long.parseLong(sizeStr, 8);
            } catch (NumberFormatException e) {
                size = 0;
            }
        }
        char typeFlag = (char) header[156];
        boolean isDirectory = (typeFlag == '5');

        // 处理 GNU TAR 扩展前缀 (偏移量 345-499，长度 155)
        String prefix = extractString(header, 345, 155);
        if (!prefix.isEmpty()) {
            name = prefix + "/" + name;
        }

        return new TarHeader(name, size, isDirectory);
    }

    /**
     * 从字节数组中提取字符串
     *
     * @param data   源字节数组
     * @param offset 起始偏移量
     * @param length 提取长度
     * @return 提取后的字符串
     */
    private String extractString(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length && (offset + i) < data.length; i++) {
            byte b = data[offset + i];
            if (b == 0) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    /**
     * 检查字节块是否全为零（空块）
     *
     * @param block 待检查的字节块
     * @return 是否为空块
     */
    private boolean isEmptyBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 完整读取指定长度的字节到缓冲区
     *
     * @param inputStream 输入流
     * @param buffer      目标缓冲区
     * @return 实际读取的字节数
     * @throws IOException IO 异常
     */
    private int readFully(InputStream inputStream, byte[] buffer) throws IOException {
        int totalRead = 0;
        while (totalRead < buffer.length) {
            int read = inputStream.read(buffer, totalRead, buffer.length - totalRead);
            if (read == -1) {
                break;
            }
            totalRead += read;
        }
        return totalRead;
    }

    /**
     * 跳过指定数量的字节
     *
     * @param inputStream 输入流
     * @param bytes       要跳过的字节数
     * @throws IOException IO 异常
     */
    private void skipBytes(InputStream inputStream, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped <= 0) {
                // 当 skip 无效时，通过读取并丢弃数据来跳过
                byte[] buffer = new byte[(int) Math.min(8192, remaining)];
                int read = inputStream.read(buffer);
                if (read <= 0) {
                    break;
                }
                remaining -= read;
            } else {
                remaining -= skipped;
            }
        }
    }

    /**
     * 复制指定数量的字节从输入流到输出流
     *
     * @param inputStream  输入流
     * @param outputStream 输出流
     * @param bytes        要复制的字节数
     * @throws IOException IO 异常
     */
    private void copyBytes(InputStream inputStream, OutputStream outputStream, long bytes) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = bytes;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = inputStream.read(buffer, 0, toRead);
            if (read <= 0) {
                break;
            }
            outputStream.write(buffer, 0, read);
            remaining -= read;
        }
    }

    /**
     * TAR 文件头部信息类
     */
    private static class TarHeader {
        String name = "";
        long size = 0;
        boolean isDirectory = false;

        TarHeader(String name, long size, boolean isDirectory) {
            this.name = name;
            this.size = size;
            this.isDirectory = isDirectory;
        }
    }
}
