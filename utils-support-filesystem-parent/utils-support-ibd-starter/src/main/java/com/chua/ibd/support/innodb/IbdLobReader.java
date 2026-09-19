package com.chua.ibd.support.innodb;

import java.io.IOException;
import java.util.Arrays;

/**
 * 溢出页（off-page）读取器：把记录里那个 20 字节的「外部引用」还原成完整内容。
 *
 * <p>当一行放不下时，InnoDB 会把大字段（{@code TEXT} / {@code BLOB} / 超长
 * {@code VARCHAR}）的内容挪到独立的页里，行内只留一个 20 字节引用：
 * {@code space_id(4) + page_no(4) + offset(4) + length(8)}。</p>
 *
 * <p>被引用的页有两种世代，靠<b>页类型</b>区分：</p>
 * <ul>
 *   <li>{@code 24}（{@code LOB_FIRST}，MySQL 8.0 新格式）—— 首页里放一串
 *       60 字节的「索引项」，每项指向一个数据页；数据页内容从偏移 49 开始，
 *       首页内联的数据从偏移 696 开始。索引项之间用
 *       {@code next(page 4 + offset 2)} 串成链表；</li>
 *   <li>其它（{@code 22} 等旧格式）—— 数据直接从偏移 38 开始，按
 *       {@code FIL_PAGE_NEXT} 串页，末尾用 {@code 0xFFFFFFFF} 收尾。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdLobReader {

    /**
     * MySQL 8.0 新格式的 LOB 首页页类型。
     */
    private static final int FIL_PAGE_TYPE_LOB_FIRST = 24;

    /**
     * 首页里第一个索引项的偏移。
     */
    private static final int FIRST_PAGE_ENTRY_OFFSET = 96;

    /**
     * 索引项长度。
     */
    private static final int INDEX_ENTRY_SIZE = 60;

    /**
     * 索引项内「数据页号」字段的偏移。
     */
    private static final int ENTRY_PAGE_NO_OFFSET = 48;

    /**
     * 索引项内「数据长度（低 16 位是标志）」字段的偏移。
     */
    private static final int ENTRY_DATA_LEN_OFFSET = 52;

    /**
     * 索引项内 {@code next} 指针（页 4 字节 + 偏移 2 字节）的偏移。
     */
    private static final int ENTRY_NEXT_OFFSET = 6;

    /**
     * 首页内联数据的偏移。
     */
    private static final int FIRST_PAGE_DATA_OFFSET = 696;

    /**
     * 普通数据页里内容的偏移。
     */
    private static final int DATA_PAGE_DATA_OFFSET = 49;

    /**
     * 旧格式数据页里内容的偏移。
     */
    private static final int LEGACY_DATA_OFFSET = IbdConstants.FIL_HEADER_SIZE;

    /**
     * 单条链表的遍历上限，防止坏页造成死循环。
     */
    private static final int MAX_LOB_PAGES = 1 << 20;

    /**
     * 链表结束标志。
     */
    private static final long FIL_NULL = 0xFFFFFFFFL;

    /**
     * 表空间读取器。
     */
    private final IbdTablespace tablespace;

    /**
     * 页大小。
     */
    private final int pageSize;

    /**
     * 构造溢出页读取器。
     *
     * @param tablespace 表空间
     */
    public IbdLobReader(IbdTablespace tablespace) {
        this.tablespace = tablespace;
        this.pageSize = tablespace.pageSize();
    }

    /**
     * 按 20 字节外部引用读出完整内容。
     *
     * @param reference 行内的 20 字节引用
     * @return 字段内容
     * @throws IOException 读取失败
     */
    public byte[] read(byte[] reference) throws IOException {
        if (reference == null || reference.length < IbdConstants.BTR_EXTERN_FIELD_REF_SIZE) {
            return reference == null ? new byte[0] : reference;
        }
        long pageNo = IbdTablespace.readUnsignedInt(reference, 4);
        long declaredLength = IbdTablespace.readLong(reference, 12);
        if (pageNo >= tablespace.pageCount()) {
            return new byte[0];
        }
        byte[] firstPage = tablespace.readPage(pageNo);
        byte[] content = IbdPageHeader.pageType(firstPage) == FIL_PAGE_TYPE_LOB_FIRST
                ? readNewFormat(firstPage, pageNo)
                : readLegacyFormat(pageNo);
        if (declaredLength >= 0 && declaredLength < content.length) {
            return Arrays.copyOf(content, (int) declaredLength);
        }
        return content;
    }

    /**
     * 读 MySQL 8.0 新格式（{@code LOB_FIRST} + 索引项链表）。
     *
     * @param firstPage   首页内容
     * @param firstPageNo 首页页号
     * @return 完整内容
     * @throws IOException 读取失败
     */
    private byte[] readNewFormat(byte[] firstPage, long firstPageNo) throws IOException {
        byte[] out = new byte[0];
        if (firstPage.length < FIRST_PAGE_ENTRY_OFFSET + INDEX_ENTRY_SIZE) {
            return out;
        }
        byte[] entry = Arrays.copyOfRange(firstPage, FIRST_PAGE_ENTRY_OFFSET,
                FIRST_PAGE_ENTRY_OFFSET + INDEX_ENTRY_SIZE);
        for (int guard = 0; guard < MAX_LOB_PAGES; guard++) {
            long dataPageNo = IbdTablespace.readUnsignedInt(entry, ENTRY_PAGE_NO_OFFSET);
            long rawLength = IbdTablespace.readUnsignedInt(entry, ENTRY_DATA_LEN_OFFSET);
            int dataLength = (int) (rawLength >>> 16);
            if (dataPageNo == 0) {
                break;
            }
            if (dataLength > 0) {
                byte[] chunk = dataPageNo == firstPageNo
                        ? slice(firstPage, FIRST_PAGE_DATA_OFFSET, dataLength)
                        : slice(tablespace.readPage(dataPageNo), DATA_PAGE_DATA_OFFSET, dataLength);
                out = concat(out, chunk);
            }
            long nextPageNo = IbdTablespace.readUnsignedInt(entry, ENTRY_NEXT_OFFSET);
            int nextOffset = IbdTablespace.readUnsignedShort(entry, ENTRY_NEXT_OFFSET + 4);
            if (nextPageNo <= 0 || nextPageNo >= FIL_NULL || nextPageNo >= tablespace.pageCount()) {
                break;
            }
            byte[] nextPage = tablespace.readPage(nextPageNo);
            if (nextOffset < 0 || nextOffset + INDEX_ENTRY_SIZE > nextPage.length) {
                break;
            }
            entry = Arrays.copyOfRange(nextPage, nextOffset, nextOffset + INDEX_ENTRY_SIZE);
        }
        return out;
    }

    /**
     * 读旧格式（数据直接跟在页头后面，按 {@code FIL_PAGE_NEXT} 串页）。
     *
     * @param firstPageNo 首页页号
     * @return 完整内容
     * @throws IOException 读取失败
     */
    private byte[] readLegacyFormat(long firstPageNo) throws IOException {
        byte[] out = new byte[0];
        long pageNo = firstPageNo;
        for (int guard = 0; guard < MAX_LOB_PAGES; guard++) {
            if (pageNo <= 0 || pageNo >= tablespace.pageCount()) {
                break;
            }
            byte[] page = tablespace.readPage(pageNo);
            out = concat(out, slice(page, LEGACY_DATA_OFFSET, page.length - LEGACY_DATA_OFFSET));
            long next = IbdTablespace.readUnsignedInt(page, IbdConstants.OFF_FIL_NEXT);
            if (next == FIL_NULL || next == pageNo) {
                break;
            }
            pageNo = next;
        }
        return out;
    }

    /**
     * 从页里安全截取一段。
     *
     * @param page   页内容
     * @param offset 起始偏移
     * @param length 期望长度
     * @return 截取结果（超出页尾时自动截短）
     */
    private static byte[] slice(byte[] page, int offset, int length) {
        if (offset >= page.length || length <= 0) {
            return new byte[0];
        }
        int end = Math.min(page.length, offset + length);
        return Arrays.copyOfRange(page, offset, end);
    }

    /**
     * 拼接两段字节。
     *
     * @param left  前一段
     * @param right 后一段
     * @return 拼接结果
     */
    private static byte[] concat(byte[] left, byte[] right) {
        if (right.length == 0) {
            return left;
        }
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    /**
     * 页大小。
     *
     * @return 页大小
     */
    public int pageSize() {
        return pageSize;
    }
}
