package com.chua.common.support.wal;

import com.chua.common.support.datasource.wal.WalStoreConfig;
import com.chua.common.support.file.FileSystem;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件检索型 WAL 文件系统包装器。
 *
 * <p>在任意 {@link WalFileSystem} 基础上增加 glob/regex 文件搜索能力，
 * 用于调试、审计和批量管理 WAL 段文件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("wal-filesearch")
public class FileSearchWalFileSystem implements WalFileSystem {

    private final WalFileSystem delegate; // delegate

    /**
     * 文件搜索wal文件系统。
     * @param delegate delegate
     */
    public FileSearchWalFileSystem(WalFileSystem delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getType() {
        return "wal-filesearch";
    }

    @Override
    public long append(byte op, byte[] payload) throws IOException {
        return delegate.append(op, payload);
    }

    @Override
    public void sync() throws IOException {
        delegate.sync();
    }

    @Override
    public long currentLsn() {
        return delegate.currentLsn();
    }

    @Override
    public Optional<WalRecord> readByLsn(long lsn) throws IOException {
        return delegate.readByLsn(lsn);
    }

    @Override
    public byte[] readAt(int segmentNo, long offset, int length) throws IOException {
        return delegate.readAt(segmentNo, offset, length);
    }

    @Override
    public List<WalSegmentInfo> listSegments() throws IOException {
        return delegate.listSegments();
    }

    @Override
    public WalSegmentInfo currentSegment() {
        return delegate.currentSegment();
    }

    @Override
    public void compact() throws IOException {
        delegate.compact();
    }

    @Override
    public CheckpointMeta loadCheckpoint() throws IOException {
        return delegate.loadCheckpoint();
    }

    @Override
    public void markCheckpoint(long lsn) throws IOException {
        delegate.markCheckpoint(lsn);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    // ==================== 文件检索 ====================

    /**
     * Glob 模式搜索 WAL 段文件。
     *
     * @param pattern glob 模式（如 "*-000*.wal"）
     * @return 匹配的文件路径列表
     */
    public List<java.nio.file.Path> searchFiles(String pattern) throws IOException {
        java.nio.file.Path walDir = delegate instanceof AbstractWalFileSystem awf
                ? awf.config().baseDir().resolve("_wal")
                : java.nio.file.Paths.get("./_wal");
        if (!java.nio.file.Files.exists(walDir)) {
            return java.util.Collections.emptyList();
        }
        try (Stream<java.nio.file.Path> stream = java.nio.file.Files.list(walDir)) {
            return stream
                    .filter(p -> matchesGlob(p.getFileName().toString(), pattern))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 正则表达式搜索 WAL 段文件。
     * @param regex regex
     * @return 搜索文件的结果
     */
    public List<java.nio.file.Path> searchFiles(Pattern regex) throws IOException {
        java.nio.file.Path walDir = delegate instanceof AbstractWalFileSystem awf
                ? awf.config().baseDir().resolve("_wal")
                : java.nio.file.Paths.get("./_wal");
        if (!java.nio.file.Files.exists(walDir)) {
            return java.util.Collections.emptyList();
        }
        try (Stream<java.nio.file.Path> stream = java.nio.file.Files.list(walDir)) {
            return stream
                    .filter(p -> regex.matcher(p.getFileName().toString()).matches())
                    .collect(Collectors.toList());
        }
    }

    /**
     * 在指定 LSN 范围内搜索包含指定字节的记录（调试用）。
     * @param fromLsn 从lsn
     * @param toLsn 转为lsn
     * @param keyword keyword
     * @return 搜索内容的结果
     */
    public List<WalSearchHit> searchContent(long fromLsn, long toLsn, byte[] keyword) throws IOException {
        List<WalSearchHit> hits = new java.util.ArrayList<>();
        // 遍历所有分片，扫描记录内容
        for (WalSegmentInfo seg : listSegments()) {
            if (seg.lastLsn() < fromLsn) {
                continue;
            }
            if (seg.firstLsn() >= toLsn) {
                break;
            }
            try (java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(seg.path())))) {
                while (true) {
                    byte[] header = new byte[com.chua.common.support.wal.WalConfig.RECORD_HEADER_BYTES];
                    try {
                        in.readFully(header);
                    } catch (java.io.EOFException e) {
                        break;
                    }
                    int len = readInt(header, com.chua.common.support.wal.WalConfig.CRC32_BYTES
                            + com.chua.common.support.wal.WalConfig.LSN_BYTES
                            + com.chua.common.support.wal.WalConfig.OP_BYTES);
                    long lsn = readLong(header, com.chua.common.support.wal.WalConfig.CRC32_BYTES);
                    if (lsn < fromLsn || lsn >= toLsn) {
                        continue;
                    }
                    byte[] payload = new byte[len];
                    try {
                        in.readFully(payload);
                    } catch (java.io.EOFException e) {
                        break;
                    }
                    if (contains(payload, keyword)) {
                        hits.add(new WalSearchHit(lsn, header[com.chua.common.support.wal.WalConfig.CRC32_BYTES
                                + com.chua.common.support.wal.WalConfig.LSN_BYTES], payload));
                    }
                }
            }
        }
        return hits;
    }

    /**
     * 读取int。
     * @param src src
     * @param offset 偏移量
     * @return 读取int的结果
     */
    private static int readInt(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24) | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8) | (src[offset + 3] & 0xFF);
    }

    /**
     * 读取long。
     * @param src src
     * @param offset 偏移量
     * @return 读取long的结果
     */
    private static long readLong(byte[] src, int offset) {
        return ((long)(src[offset] & 0xFF) << 56) | ((long)(src[offset + 1] & 0xFF) << 48)
                | ((long)(src[offset + 2] & 0xFF) << 40) | ((long)(src[offset + 3] & 0xFF) << 32)
                | ((long)(src[offset + 4] & 0xFF) << 24) | ((long)(src[offset + 5] & 0xFF) << 16)
                | ((long)(src[offset + 6] & 0xFF) << 8) | (src[offset + 7] & 0xFF);
    }

    /**
     * 匹配glob。
     * @param filename 文件名
     * @param pattern 模式
     * @return 匹配glob的结果
     */
    private static boolean matchesGlob(String filename, String pattern) {
        // 简单 glob：* → .*, ? → .
        String regex = "^" + pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$";
        return java.util.regex.Pattern.matches(regex, filename);
    }

    /**
     * contains。
     * @param data 数据
     * @param keyword keyword
     * @return contains的结果
     */
    private static boolean contains(byte[] data, byte[] keyword) {
        if (keyword.length == 0 || keyword.length > data.length) {
            return false;
        }
        for (int i = 0; i <= data.length - keyword.length; i++) {
            boolean match = true;
            for (int j = 0; j < keyword.length; j++) {
                if (data[i + j] != keyword[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /**
     * wal搜索hit。
     * @param lsn lsn
     * @param op op
     * @param payload payload
     * @return wal搜索hit的结果
     */
    public record WalSearchHit(long lsn, byte op, byte[] payload) {
        /**
         * 键snippet。
         * @return 键snippet的结果
         */
        public String keySnippet() {
            if (payload == null || payload.length < 4) {
                return "";
            }
            int len = ByteBuffer.wrap(payload).getInt();
            if (len <= 0 || len > payload.length - 4) {
                return "";
            }
            return new String(payload, 4, Math.min(len, 32), StandardCharsets.UTF_8);
        }
    }

    // ==================== FileSystem 接口 ====================
    @Override public com.chua.common.support.file.builder.ReadBuilder read(File file) {
        throw new UnsupportedOperationException();
    }
    @Override public com.chua.common.support.file.builder.WriteBuilder write(File file) {
        throw new UnsupportedOperationException();
    }
}
