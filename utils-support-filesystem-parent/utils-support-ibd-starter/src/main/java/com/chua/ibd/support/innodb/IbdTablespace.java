package com.chua.ibd.support.innodb;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 {@code .ibd} 表空间文件的只读访问入口。
 *
 * <p>负责三件事：</p>
 * <ol>
 *   <li>从第 0 页的 FSP header 推出<b>页大小</b>、space id、总页数；</li>
 *   <li>按页号随机读一页；</li>
 *   <li>扫描全文件找出 SDI 页与某个索引的叶子页。</li>
 * </ol>
 *
 * <p><b>页大小不能写死 16K</b>：{@code innodb_page_size} 可以是 4K/8K/16K/32K/64K，
 * 档位藏在 {@code FSP_SPACE_FLAGS} 的 bit6-9 里。用错页大小会把所有偏移算歪，
 * 表现是「能读出页头但内容全是乱码」。</p>
 *
 * <p><b>为什么要扫页而不是顺着 B+ 树走</b>：扫页只需页头两个字段
 * （{@code PAGE_INDEX_ID} 与 {@code PAGE_LEVEL}），既不用解析节点指针记录，
 * 也不怕中间层页损坏；代价只是把文件读一遍，对还原场景完全可接受。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdTablespace implements Closeable {

    /**
    * 表空间文件路径。
    */
    private final Path path;

    /**
    * 只读文件通道。
    */
    private final FileChannel channel;

    /**
    * 页大小（字节）。
    */
    private final int pageSize;

    /**
    * 表空间 id。
    */
    private final long spaceId;

    /**
    * {@code FSP_SPACE_FLAGS} 原始值。
    */
    private final long spaceFlags;

    /**
    * 文件里能完整读出的页数。
    */
    private final long pageCount;

    /**
    * 索引 id → 该索引的叶子页页号（惰性扫描）。
    */
    private Map<Long, List<Long>> leafPagesByIndex;

    /**
    * 打开表空间。
    *
    * @param file {@code .ibd} 文件
    * @throws IOException 读取失败
    */
    private IbdTablespace(Path file) throws IOException {
        this.path = file;
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        ByteBuffer first = ByteBuffer.allocate(IbdConstants.FIL_HEADER_SIZE + 120);
        int read = channel.read(first, 0);
        if (read < IbdConstants.FIL_HEADER_SIZE + 120) {
            channel.close();
            throw new IOException("不是有效的 InnoDB 表空间（文件不足 158 字节）: " + file);
        }
        byte[] head = first.array();
        this.spaceFlags = readUnsignedInt(head, IbdConstants.OFF_FSP_SPACE_FLAGS);
        this.pageSize = IbdConstants.pageSizeOf(spaceFlags);
        this.spaceId = readUnsignedInt(head, IbdConstants.OFF_FSP_SPACE_ID);
        long size = channel.size();
        this.pageCount = size / pageSize;
        if (pageCount < 1) {
            channel.close();
            throw new IOException("不是有效的 InnoDB 表空间（不足一页 " + pageSize + " 字节）: " + file);
        }
    }

    /**
    * 打开表空间。
    *
    * @param file {@code .ibd} 文件
    * @return 表空间对象，使用后需 {@link #close()}
    * @throws IOException 读取失败
    */
    public static IbdTablespace open(File file) throws IOException {
        return new IbdTablespace(file.toPath());
    }

    /**
    * 表空间文件路径。
    *
    * @return 路径
    */
    public Path path() {
        return path;
    }

    /**
    * 页大小。
    *
    * @return 页大小（字节）
    */
    public int pageSize() {
        return pageSize;
    }

    /**
    * 表空间 id。
    *
    * @return space id
    */
    public long spaceId() {
        return spaceId;
    }

    /**
    * {@code FSP_SPACE_FLAGS} 原始值。
    *
    * @return 标志位
    */
    public long spaceFlags() {
        return spaceFlags;
    }

    /**
    * 页数。
    *
    * @return 文件里能完整读出的页数
    */
    public long pageCount() {
        return pageCount;
    }

    /**
    * 读一页。
    *
    * @param pageNo 页号
    * @return 页内容（长度等于页大小）
    * @throws IOException 页号越界或读取失败
    */
    public byte[] readPage(long pageNo) throws IOException {
        if (pageNo < 0 || pageNo >= pageCount) {
            throw new IOException("页号越界: " + pageNo + "（本表空间共 " + pageCount + " 页）");
        }
        byte[] page = new byte[pageSize];
        ByteBuffer buffer = ByteBuffer.wrap(page);
        long offset = pageNo * pageSize;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, offset + buffer.position());
            if (n < 0) {
                break;
            }
        }
        return page;
    }

    /**
    * 扫描出所有 SDI 页的页号。
    *
    * <p>SDI 页的页类型是 {@code 17853}（{@code FIL_PAGE_SDI}），与普通索引页
    * {@code 17855} 只差一个数字，必须分清。</p>
    *
    * @return SDI 页页号（升序）
    * @throws IOException 读取失败
    */
    public List<Long> sdiPages() throws IOException {
        List<Long> pages = new ArrayList<>();
        for (long i = 0; i < pageCount; i++) {
            byte[] page = readPage(i);
            if (IbdPageHeader.pageType(page) == IbdConstants.FIL_PAGE_SDI) {
                pages.add(i);
            }
        }
        return pages;
    }

    /**
    * 扫描出所有索引页（{@code FIL_PAGE_INDEX}）的页号，按「索引 id → 叶子页」分组。
    *
    * @return 索引 id → 叶子页页号（升序）
    * @throws IOException 读取失败
    */
    public Map<Long, List<Long>> leafPagesByIndex() throws IOException {
        if (leafPagesByIndex != null) {
            return leafPagesByIndex;
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (long i = 0; i < pageCount; i++) {
            byte[] page = readPage(i);
            if (IbdPageHeader.pageType(page) != IbdConstants.FIL_PAGE_INDEX) {
                continue;
            }
            if (IbdPageHeader.level(page) != 0) {
                continue;
            }
            result.computeIfAbsent(IbdPageHeader.indexId(page), k -> new ArrayList<>()).add(i);
        }
        this.leafPagesByIndex = result;
        return result;
    }

    /**
    * 取某个索引的叶子页页号。
    *
    * @param indexId 索引 id
    * @return 叶子页页号；该索引没有叶子页时返回空列表
    * @throws IOException 读取失败
    */
    public List<Long> leafPagesOf(long indexId) throws IOException {
        List<Long> pages = leafPagesByIndex().get(indexId);
        return pages == null ? List.of() : pages;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
    * 从字节数组读 4 字节无符号整数（大端）。
    *
    * @param data   字节数组
    * @param offset 偏移
    * @return 无符号值
    */
    static long readUnsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
    * 从字节数组读 2 字节无符号整数（大端）。
    *
    * @param data   字节数组
    * @param offset 偏移
    * @return 无符号值
    */
    static int readUnsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /**
    * 从字节数组读 2 字节有符号整数（大端）。
    *
    * @param data   字节数组
    * @param offset 偏移
    * @return 有符号值
    */
    static short readShort(byte[] data, int offset) {
        return (short) readUnsignedShort(data, offset);
    }

    /**
    * 从字节数组读 8 字节无符号整数（大端）。
    *
    * @param data   字节数组
    * @param offset 偏移
    * @return 无符号值（超出 long 范围时按 long 解释）
    */
    static long readLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }
}
