package com.chua.ibd.support.innodb;

/**
 * InnoDB 表空间（{@code .ibd}）物理布局常量。
 *
 * <p>这里的偏移全部是「相对页首」的字节偏移，页大小由 FSP header 里的
 * {@code FSP_SPACE_FLAGS} 推出（见 {@link #pageSizeOf(long)}）。</p>
 *
 * <h3>一张页的骨架</h3>
 * <pre>
 * 0                     38        64              94                页尾
 * +---------------------+---------+---------------+-----------------+
 * | FIL 页头 (38B)      | 索引页头| infimum 记录  | 用户记录 ...     |
 * | 页号/类型/space id  | (56B)   | (5B头+8B数据) |                  |
 * +---------------------+---------+---------------+-----------------+
 * </pre>
 *
 * <p>页尾最后 8 字节是 FIL trailer（旧页校验和 + LSN 低位），再往前是页目录
 * （每槽 2 字节，记录条数由 {@code PAGE_N_DIR_SLOTS} 给出）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdConstants {

    /**
     * 工具类，禁止实例化。
     */
    private IbdConstants() {
    }

    // ==================== FIL 页头（页首 38 字节） ====================

    /**
     * FIL 页头长度，也是 FSP header 的起始偏移。
     */
    public static final int FIL_HEADER_SIZE = 38;

    /**
     * 页校验和（4 字节）。
     */
    public static final int OFF_FIL_CHECKSUM = 0;

    /**
     * 页号（4 字节），应与文件内下标一致。
     */
    public static final int OFF_FIL_PAGE_NO = 4;

    /**
     * 同层上一页页号（4 字节）。
     */
    public static final int OFF_FIL_PREV = 8;

    /**
     * 同层下一页页号（4 字节）。
     */
    public static final int OFF_FIL_NEXT = 12;

    /**
     * 页类型（2 字节），取值见本类的 {@code FIL_PAGE_*} 常量。
     */
    public static final int OFF_FIL_PAGE_TYPE = 24;

    /**
     * 表空间 id（4 字节）。
     */
    public static final int OFF_FIL_SPACE_ID = 34;

    // ==================== 页类型 ====================

    /**
     * 未分配页。
     */
    public static final int FIL_PAGE_TYPE_ALLOCATED = 0;

    /**
     * FSP header 页（固定为第 0 页）。
     */
    public static final int FIL_PAGE_TYPE_FSP_HDR = 8;

    /**
     * 索引页（B+ 树节点）。
     */
    public static final int FIL_PAGE_INDEX = 17855;

    /**
     * SDI（Serialized Dictionary Information）页，承载表结构 JSON。
     */
    public static final int FIL_PAGE_SDI = 17853;

    /**
     * 溢出页（BLOB/TEXT 内容）。
     */
    public static final int FIL_PAGE_TYPE_LOB_DATA = 24;

    // ==================== FSP header（第 0 页，偏移 38 起） ====================

    /**
     * 表空间 id（4 字节），与 {@link #OFF_FIL_SPACE_ID} 重复但独立存放。
     */
    public static final int OFF_FSP_SPACE_ID = 38;

    /**
     * 表空间总页数（4 字节）。
     */
    public static final int OFF_FSP_SIZE = 46;

    /**
     * 已初始化页数上限（4 字节）。
     */
    public static final int OFF_FSP_FREE_LIMIT = 50;

    /**
     * 表空间标志位（4 字节）：bit6-9 是页大小档位，bit14 表示带 SDI。
     */
    public static final int OFF_FSP_SPACE_FLAGS = 54;

    /**
     * 已用碎片页数（4 字节）。
     */
    public static final int OFF_FSP_FRAG_N_USED = 58;

    /**
     * 段 id 计数器（8 字节）。
     */
    public static final int OFF_FSP_SEG_ID = 110;

    // ==================== 索引页头（相对页首 38 起，共 56 字节） ====================

    /**
     * 页目录槽数（2 字节）。
     */
    public static final int OFF_PAGE_N_DIR_SLOTS = 38;

    /**
     * 堆顶偏移（2 字节）。
     */
    public static final int OFF_PAGE_HEAP_TOP = 40;

    /**
     * 堆中记录数（2 字节，最高位表示 COMPACT 格式）。
     */
    public static final int OFF_PAGE_N_HEAP = 42;

    /**
     * 空闲链表头（2 字节）。
     */
    public static final int OFF_PAGE_FREE = 44;

    /**
     * 可回收字节数（2 字节）。
     */
    public static final int OFF_PAGE_GARBAGE = 46;

    /**
     * 最后插入位置（2 字节）。
     */
    public static final int OFF_PAGE_LAST_INSERT = 48;

    /**
     * 插入方向（2 字节）。
     */
    public static final int OFF_PAGE_DIRECTION = 50;

    /**
     * 同方向连续插入次数（2 字节）。
     */
    public static final int OFF_PAGE_N_DIRECTION = 52;

    /**
     * 本页用户记录数（2 字节）。
     */
    public static final int OFF_PAGE_N_RECS = 54;

    /**
     * 本页最大事务 id（8 字节）。
     */
    public static final int OFF_PAGE_MAX_TRX_ID = 56;

    /**
     * B+ 树层高（2 字节），0 表示叶子页。
     */
    public static final int OFF_PAGE_LEVEL = 64;

    /**
     * 索引 id（8 字节），与 SDI 里 {@code se_private_data} 的 {@code id=} 对应。
     */
    public static final int OFF_PAGE_INDEX_ID = 66;

    /**
     * 页内系统记录（infimum）的起点：记录头占 94..98，记录数据从 99 开始。
     */
    public static final int PAGE_DATA = 94;

    /**
     * infimum 记录的 origin（= 记录数据起点）。遍历从它开始。
     */
    public static final int PAGE_NEW_INFIMUM = 99;

    /**
     * COMPACT 记录头长度。
     */
    public static final int REC_HEADER_SIZE = 5;

    // ==================== 记录状态 ====================

    /**
     * 普通记录（叶子页的用户记录）。
     */
    public static final int REC_STATUS_ORDINARY = 0;

    /**
     * 节点指针记录（非叶子页）。
     */
    public static final int REC_STATUS_NODE_PTR = 1;

    /**
     * infimum 记录。
     */
    public static final int REC_STATUS_INFIMUM = 2;

    /**
     * supremum 记录。
     */
    public static final int REC_STATUS_SUPREMUM = 3;

    // ==================== 变长字段与溢出 ====================

    /**
     * 变长字段长度前缀单字节能表达的最大值；超过则用两字节前缀。
     */
    public static final int REC_N_FIELDS_ONE_BYTE_MAX = 127;

    /**
     * 记录里字段长度等于该值时，表示字段内容被放到了溢出页（DYNAMIC 行格式）。
     */
    public static final int REC_OFF_PAGE_FLAG_DYNAMIC = 16404;

    /**
     * 记录里字段长度等于该值时，表示字段内容被放到了溢出页（COMPACT 行格式，行内保留 768 字节前缀）。
     */
    public static final int REC_OFF_PAGE_FLAG_COMPACT = 17172;

    /**
     * 溢出页引用结构（space id + page no + offset + length）的大小。
     */
    public static final int BTR_EXTERN_FIELD_REF_SIZE = 20;

    /**
     * 单页最多记录数，用于防御性上限，避免坏页导致死循环。
     */
    public static final int MAX_RECORDS_PER_PAGE = 1 << 16;

    /**
     * 由 {@code FSP_SPACE_FLAGS} 推出页大小。
     *
     * <p>bit6-9 是页大小档位 {@code ssize}：{@code 0} 表示默认的 16K，
     * 其余情况为 {@code 512 << ssize}（4K/8K/32K/64K）。<b>不能</b>照搬
     * 某些文档里「位 6-7」的旧口径，那会把 16K 误判成 2K。</p>
     *
     * @param spaceFlags {@code FSP_SPACE_FLAGS} 的值
     * @return 页大小（字节）
     */
    public static int pageSizeOf(long spaceFlags) {
        int ssize = (int) ((spaceFlags & 960L) >> 6);
        return ssize == 0 ? 16384 : 512 << ssize;
    }

    /**
     * 判断表空间是否带 SDI（MySQL 8.0 起恒为真）。
     *
     * @param spaceFlags {@code FSP_SPACE_FLAGS} 的值
     * @return 带 SDI 返回 true
     */
    public static boolean hasSdi(long spaceFlags) {
        return ((spaceFlags & 16384L) >> 14) != 0;
    }
}
