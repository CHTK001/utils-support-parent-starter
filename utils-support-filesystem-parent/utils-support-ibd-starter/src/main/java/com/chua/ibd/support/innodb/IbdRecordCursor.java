package com.chua.ibd.support.innodb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 页内记录游标：按 {@code REC_NEXT} 链遍历记录，并把一条 COMPACT 记录还原成一行数据。
 *
 * <h3>记录在页里长什么样（实测口径）</h3>
 * <p>一条记录在页里占据的字节区间，从低地址到高地址依次是：</p>
 * <pre>
 *   [变长长度数组][NULL 位图][5 字节记录头][字段值...]
 *        ↑ 这两块要"向前"读            ↑ 字段值要"向后"读
 * </pre>
 * <p>也就是说，{@code REC_NEXT} 指向的是<b>记录头之后、字段值的起点</b>（origin），
 * 而变长长度数组和 NULL 位图在记录头<b>之前</b>，必须从 {@code origin-5} 往低地址读。</p>
 *
 * <p>读的顺序也有讲究：</p>
 * <ol>
 *   <li>先读 NULL 位图：{@code ceil(可空列数/8)} 字节，按大端解释成一个整数，
 *       第 n 位对应第 n 个<b>可空</b>列（只有可空列才占位）；</li>
 *   <li>再逐个读变长列的长度：最大字节长度 ≤ 255 的列固定 1 字节；
 *       超过 255 的列是「1 或 2 字节」，判据是所读字节的最高位 ——
 *       置位表示还有第二个字节（低地址那个才是低位）。</li>
 * </ol>
 *
 * <p><b>踩过的坑</b>：一开始以为「最大长度 &gt; 255 就一定用 2 字节」，
 * 于是每行都多读 1 字节，整表错位。真实规则是<b>看具体值</b>，由最高位区分。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdRecordCursor {

    /**
    * 页内容。
    */
    private final byte[] page;

    /**
    * 页大小。
    */
    private final int pageSize;

    /**
    * 字段值的「向后」读游标（从 origin 递增）。
    */
    private int forward;

    /**
    * NULL 位图与变长长度的「向前」读游标（从 origin-5 递减）。
    */
    private int backward;

    /**
    * 当前记录头。
    */
    private Header header;

    /**
    * 记录头解析结果。
    *
    * @param origin     记录数据起点
    * @param infoBits   信息位（第 0 位为删除标记）
    * @param nOwned     该记录在页目录里拥有的槽数
    * @param heapNo     堆序号（0=infimum，1=supremum）
    * @param recordType 记录类型（0 普通 / 1 节点指针 / 2 infimum / 3 supremum）
    * @param nextOffset {@code REC_NEXT} 原始值（相对本记录 origin 的有符号偏移）
    * @param level      所在页的 B+ 树层高
    */
    public record Header(int origin, int infoBits, int nOwned, int heapNo,
                         int recordType, int nextOffset, int level) {

        /**
        * 是否为用户记录（普通记录且未被标记删除）。
        *
        * @return 是返回 true
        */
        public boolean userRecord() {
            return recordType == IbdConstants.REC_STATUS_ORDINARY && (infoBits & 0x1) == 0;
        }

        /**
        * 下一条记录的 origin。
        *
        * @return 下一条记录的 origin
        */
        public int nextOrigin() {
            return origin + nextOffset;
        }
    }

    /**
    * 构造游标。
    *
    * @param page     页内容
    * @param pageSize 页大小
    */
    public IbdRecordCursor(byte[] page, int pageSize) {
        this.page = page;
        this.pageSize = pageSize;
    }

    /**
    * 按 {@code REC_NEXT} 链把一页里的记录头全部读出来。
    *
    * <p>链从 infimum（origin 99）开始，到 supremum（{@code REC_NEXT == 0}）结束，
    * 因此结果里包含这两条哨兵记录；调用方用 {@link Header#userRecord()} 过滤即可。</p>
    *
    * @param page     页内容
    * @param pageSize 页大小
    * @return 记录头列表（含 infimum / supremum）
    */
    public static List<Header> chain(byte[] page, int pageSize) {
        List<Header> headers = new ArrayList<>();
        int origin = IbdConstants.PAGE_NEW_INFIMUM;
        IbdRecordCursor cursor = new IbdRecordCursor(page, pageSize);
        int guard = Math.min(IbdPageHeader.recordCount(page) + 2, IbdConstants.MAX_RECORDS_PER_PAGE);
        for (int i = 0; i < guard; i++) {
            if (origin < IbdConstants.REC_HEADER_SIZE || origin > pageSize) {
                break;
            }
            Header header = cursor.at(origin).header();
            headers.add(header);
            if (header.nextOffset() == 0) {
                break;
            }
            origin = header.nextOrigin();
        }
        return headers;
    }

    /**
    * 定位到某条记录的 origin 并解析其记录头。
    *
    * @param origin 记录数据起点
    * @return 本对象（便于链式调用）
    */
    public IbdRecordCursor at(int origin) {
        this.forward = origin;
        this.backward = origin - IbdConstants.REC_HEADER_SIZE;
        this.header = parseHeader(origin);
        return this;
    }

    /**
    * 取当前记录头。
    *
    * @return 记录头
    */
    public Header header() {
        return header;
    }

    /**
    * 解析 5 字节记录头。
    *
    * <p>前 <b>3</b> 字节拼成一个 24 位字（大端）：高 4 位是信息位、接着 4 位是页目录拥有数、
    * 再 13 位是堆序号、低 3 位是记录类型。第 3-4 字节是相对本记录 origin 的
    * <b>有符号</b>下一记录偏移。</p>
    *
    * <p><b>踩过的坑</b>：一开始只取了前 2 字节当 16 位字，结果类型位落到了堆序号中间 ——
    * 表现是 supremum 被认成普通记录，遍历时多读一条垃圾记录，SDI 解压直接报
    * 「unknown compression method」。</p>
    *
    * @param origin 记录数据起点
    * @return 记录头
    */
    private Header parseHeader(int origin) {
        int base = origin - IbdConstants.REC_HEADER_SIZE;
        if (base < 0 || origin > pageSize) {
            throw new IllegalStateException("记录头越界: origin=" + origin + ", 页大小=" + pageSize);
        }
        int word = ((page[base] & 0xFF) << 16) | ((page[base + 1] & 0xFF) << 8) | (page[base + 2] & 0xFF);
        int next = IbdTablespace.readShort(page, base + 3);
        return new Header(origin, (word >> 20) & 0xF, (word >> 16) & 0xF, (word >> 3) & 0x1FFF,
                word & 0x7, next, IbdPageHeader.level(page));
    }

    /**
    * 把当前记录还原成一行数据。
    *
    * @param columns 记录里的字段顺序（来自索引定义，<b>不是</b>建表顺序）
    * @param lob     溢出页读取器；为 {@code null} 时遇到溢出字段会抛异常
    * @return 列名 → 值（{@code null} 表示 SQL NULL）
    * @throws IOException 读溢出页失败
    */
    public Map<String, Object> readRow(List<IbdColumn> columns, IbdLobReader lob) throws IOException {
        int nullableCount = 0;
        for (IbdColumn column : columns) {
            if (column.nullable()) {
                nullableCount++;
            }
        }
        long nullBitmap = 0;
        if (nullableCount > 0) {
            int bytes = (nullableCount + 7) / 8;
            nullBitmap = readBackwardInt(bytes);
        }

        Map<String, Boolean> isNull = new LinkedHashMap<>();
        Map<String, Integer> sizes = new LinkedHashMap<>();
        int bit = 0;
        for (IbdColumn column : columns) {
            boolean nul = false;
            if (column.nullable()) {
                nul = ((nullBitmap >>> bit) & 1L) != 0;
                bit++;
            }
            isNull.put(column.name(), nul);
            if (nul) {
                sizes.put(column.name(), 0);
            } else if (column.variableLength()) {
                sizes.put(column.name(), readVariableLength(column));
            } else {
                int fixed = column.fixedSize();
                if (fixed < 0) {
                    throw new IllegalStateException("无法确定列 " + column.name()
                            + " 的定长宽度（类型 " + column.type() + "）");
                }
                sizes.put(column.name(), fixed);
            }
        }

        Map<String, Object> row = new LinkedHashMap<>();
        for (IbdColumn column : columns) {
            if (Boolean.TRUE.equals(isNull.get(column.name()))) {
                row.put(column.name(), null);
                continue;
            }
            int size = sizes.get(column.name());
            if (size == IbdConstants.REC_OFF_PAGE_FLAG_DYNAMIC
                    || size == IbdConstants.REC_OFF_PAGE_FLAG_COMPACT) {
                byte[] reference = readForward(IbdConstants.BTR_EXTERN_FIELD_REF_SIZE);
                if (lob == null) {
                    throw new IllegalStateException("字段 " + column.name()
                            + " 的内容在溢出页里，但当前没有可用的表空间读取器");
                }
                row.put(column.name(), IbdTypeDecoder.decode(column, lob.read(reference)));
                continue;
            }
            row.put(column.name(), IbdTypeDecoder.decode(column, readForward(size)));
        }
        return row;
    }

    /**
    * 向前（低地址方向）读一个字节。
    *
    * @return 字节值（0-255）
    */
    private int readBackwardByte() {
        backward--;
        if (backward < 0) {
            throw new IllegalStateException("读变长/NULL 位图时越过了页首: origin=" + forward);
        }
        return page[backward] & 0xFF;
    }

    /**
    * 向前（低地址方向）读若干字节，并按大端解释为整数。
    *
    * @param count 字节数
    * @return 无符号整数值
    */
    private long readBackwardInt(int count) {
        backward -= count;
        if (backward < 0) {
            throw new IllegalStateException("读 NULL 位图时越过了页首: origin=" + forward);
        }
        long value = 0;
        for (int i = 0; i < count; i++) {
            value = (value << 8) | (page[backward + i] & 0xFFL);
        }
        return value;
    }

    /**
    * 读一个变长字段的长度前缀。
    *
    * @param column 字段定义
    * @return 字段内容字节数
    */
    private int readVariableLength(IbdColumn column) {
        int first = readBackwardByte();
        if (!column.big()) {
            return first;
        }
        if ((first & 0x80) == 0) {
            return first;
        }
        int second = readBackwardByte();
        return second + ((first & 0x7F) << 8);
    }

    /**
    * 向后（高地址方向）读若干字节。
    *
    * @param count 字节数
    * @return 字节内容
    */
    private byte[] readForward(int count) {
        if (count < 0 || forward + count > pageSize) {
            throw new IllegalStateException("读字段内容越界: offset=" + forward
                    + ", size=" + count + ", 页大小=" + pageSize);
        }
        byte[] data = new byte[count];
        System.arraycopy(page, forward, data, 0, count);
        forward += count;
        return data;
    }
}
