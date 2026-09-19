package com.chua.ibd.support.innodb;

/**
 * 页头字段读取（无状态工具类）。
 *
 * <p>只碰页首那 94 个字节里的固定偏移，不做任何解析，因此对坏页也安全 ——
 * 页类型不对时调用方直接跳过即可，不会抛异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdPageHeader {

    /**
     * 工具类，禁止实例化。
     */
    private IbdPageHeader() {
    }

    /**
     * 读页类型（{@code FIL_PAGE_*}）。
     *
     * @param page 页内容
     * @return 页类型
     */
    public static int pageType(byte[] page) {
        return IbdTablespace.readUnsignedShort(page, IbdConstants.OFF_FIL_PAGE_TYPE);
    }

    /**
     * 读页号。
     *
     * @param page 页内容
     * @return 页号
     */
    public static long pageNo(byte[] page) {
        return IbdTablespace.readUnsignedInt(page, IbdConstants.OFF_FIL_PAGE_NO);
    }

    /**
     * 读页所属表空间 id。
     *
     * @param page 页内容
     * @return space id
     */
    public static long spaceId(byte[] page) {
        return IbdTablespace.readUnsignedInt(page, IbdConstants.OFF_FIL_SPACE_ID);
    }

    /**
     * 读 B+ 树层高，{@code 0} 表示叶子页。
     *
     * @param page 页内容
     * @return 层高
     */
    public static int level(byte[] page) {
        return IbdTablespace.readUnsignedShort(page, IbdConstants.OFF_PAGE_LEVEL);
    }

    /**
     * 读页所属索引 id。
     *
     * @param page 页内容
     * @return 索引 id
     */
    public static long indexId(byte[] page) {
        return IbdTablespace.readLong(page, IbdConstants.OFF_PAGE_INDEX_ID);
    }

    /**
     * 读本页用户记录数。
     *
     * @param page 页内容
     * @return 记录数
     */
    public static int recordCount(byte[] page) {
        return IbdTablespace.readUnsignedShort(page, IbdConstants.OFF_PAGE_N_RECS);
    }

    /**
     * 读页目录槽数。
     *
     * @param page 页内容
     * @return 槽数
     */
    public static int directorySlots(byte[] page) {
        return IbdTablespace.readUnsignedShort(page, IbdConstants.OFF_PAGE_N_DIR_SLOTS);
    }

    /**
     * 判断该页是否可作为记录解析对象（索引页或 SDI 页）。
     *
     * @param page 页内容
     * @return 可解析返回 true
     */
    public static boolean recordBearing(byte[] page) {
        int type = pageType(page);
        return type == IbdConstants.FIL_PAGE_INDEX || type == IbdConstants.FIL_PAGE_SDI;
    }
}
