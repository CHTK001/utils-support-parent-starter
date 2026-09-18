package com.chua.ibd.support.innodb;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * InnoDB 列定义，来自 SDI JSON 的 {@code dd_object.columns[]}。
 *
 * <p>本类只负责回答解析记录时需要的三个问题：</p>
 * <ol>
 *   <li><b>占多少字节</b> —— {@link #fixedSize()}；</li>
 *   <li><b>长度前缀是几字节</b> —— {@link #isBig()}（最大字节长度 &gt; 255 才可能是 2 字节）；</li>
 *   <li><b>字节怎么变成文本</b> —— {@link #charset()}。</li>
 * </ol>
 *
 * <p><b>系统列的坑</b>：{@code DB_TRX_ID} / {@code DB_ROLL_PTR} 在 SDI 里分别被标成
 * {@code INT24} 与 {@code LONGLONG}，但实际占 <b>6</b> 与 <b>7</b> 字节。若按类型自然长度
 * 去算（3 / 8 字节），后面所有字段都会错位。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdColumn {

    /**
    * 事务 id 系统列名。
    */
    public static final String COL_DB_TRX_ID = "DB_TRX_ID";

    /**
    * 回滚指针系统列名。
    */
    public static final String COL_DB_ROLL_PTR = "DB_ROLL_PTR";

    /**
    * 隐藏行 id 系统列名。
    */
    public static final String COL_DB_ROW_ID = "DB_ROW_ID";

    /**
    * {@code DB_TRX_ID} 的实际存储宽度。
    */
    private static final int SIZE_TRX_ID = 6;

    /**
    * {@code DB_ROLL_PTR} 的实际存储宽度。
    */
    private static final int SIZE_ROLL_PTR = 7;

    /**
    * {@code DB_ROW_ID} 的实际存储宽度。
    */
    private static final int SIZE_ROW_ID = 6;

    /**
    * 十进制「每 9 位一组」对应的字节数表。
    */
    private static final int[] DIG2BYTES = {0, 1, 1, 2, 2, 3, 3, 4, 4, 4};

    /**
    * 列名。
    */
    private final String name;

    /**
    * 列类型。
    */
    private final IbdColumnType type;

    /**
    * 是否允许 NULL。
    */
    private final boolean nullable;

    /**
    * 是否无符号。
    */
    private final boolean unsigned;

    /**
    * 最大字节长度（{@code VARCHAR(45)} utf8mb4 为 180）。
    */
    private final long charLength;

    /**
    * 排序规则 id。
    */
    private final long collationId;

    /**
    * 小数秒精度（{@code DATETIME(3)} 为 3）。
    */
    private final int datetimePrecision;

    /**
    * 数值精度（{@code DECIMAL(4,2)} 为 4）。
    */
    private final int numericPrecision;

    /**
    * 数值标度（{@code DECIMAL(4,2)} 为 2）。
    */
    private final int numericScale;

    /**
    * ENUM / SET 的候选值（已从 SDI 的 base64 还原）。
    */
    private final List<String> elements;

    /**
    * 原始类型文本，如 {@code smallint unsigned}。
    */
    private final String typeText;

    /**
    * 是否自增。
    */
    private final boolean autoIncrement;

    /**
    * 是否为隐藏列（系统列或不可见列）。
    */
    private final boolean hidden;

    /**
    * 默认值文本（可能为空）。
    */
    private final String defaultValue;

    /**
    * 是否有默认值。
    */
    private final boolean hasDefault;

    /**
    * 列注释。
    */
    private final String comment;

    /**
    * 构造列定义。
    *
    * @param name              列名
    * @param type              列类型
    * @param nullable          是否允许 NULL
    * @param unsigned          是否无符号
    * @param charLength        最大字节长度
    * @param collationId       排序规则 id
    * @param datetimePrecision 小数秒精度
    * @param numericPrecision  数值精度
    * @param numericScale      数值标度
    * @param elements          ENUM / SET 候选值
    * @param typeText          原始类型文本
    * @param autoIncrement     是否自增
    * @param hidden            是否隐藏列
    * @param defaultValue      默认值文本
    * @param hasDefault        是否有默认值
    * @param comment           列注释
    */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IbdColumn(String name, IbdColumnType type, boolean nullable, boolean unsigned,
                     long charLength, long collationId, int datetimePrecision,
                     int numericPrecision, int numericScale, List<String> elements,
                     String typeText, boolean autoIncrement, boolean hidden,
                     String defaultValue, boolean hasDefault, String comment) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.unsigned = unsigned;
        this.charLength = charLength;
        this.collationId = collationId;
        this.datetimePrecision = datetimePrecision;
        this.numericPrecision = numericPrecision;
        this.numericScale = numericScale;
        this.elements = elements == null ? List.of() : List.copyOf(elements);
        this.typeText = typeText;
        this.autoIncrement = autoIncrement;
        this.hidden = hidden;
        this.defaultValue = defaultValue;
        this.hasDefault = hasDefault;
        this.comment = comment;
    }

    /**
    * 列名。
    *
    * @return 列名
    */
    public String name() {
        return name;
    }

    /**
    * 列类型。
    *
    * @return 列类型
    */
    public IbdColumnType type() {
        return type;
    }

    /**
    * 是否允许 NULL。
    *
    * @return 允许返回 true
    */
    public boolean nullable() {
        return nullable;
    }

    /**
    * 是否无符号。
    *
    * @return 无符号返回 true
    */
    public boolean unsigned() {
        return unsigned;
    }

    /**
    * 最大字节长度。
    *
    * @return 最大字节长度
    */
    public long charLength() {
        return charLength;
    }

    /**
    * 排序规则 id。
    *
    * @return 排序规则 id
    */
    public long collationId() {
        return collationId;
    }

    /**
    * 小数秒精度。
    *
    * @return 小数秒精度
    */
    public int datetimePrecision() {
        return datetimePrecision;
    }

    /**
    * 数值精度。
    *
    * @return 数值精度
    */
    public int numericPrecision() {
        return numericPrecision;
    }

    /**
    * 数值标度。
    *
    * @return 数值标度
    */
    public int numericScale() {
        return numericScale;
    }

    /**
    * ENUM / SET 候选值。
    *
    * @return 候选值列表
    */
    public List<String> elements() {
        return elements;
    }

    /**
    * 原始类型文本。
    *
    * @return 如 {@code smallint unsigned}
    */
    public String typeText() {
        return typeText;
    }

    /**
    * 是否自增。
    *
    * @return 自增返回 true
    */
    public boolean autoIncrement() {
        return autoIncrement;
    }

    /**
    * 是否为隐藏列。
    *
    * @return 隐藏返回 true
    */
    public boolean hidden() {
        return hidden;
    }

    /**
    * 默认值文本。
    *
    * @return 默认值；无则返回 {@code null}
    */
    public String defaultValue() {
        return defaultValue;
    }

    /**
    * 是否有默认值。
    *
    * @return 有返回 true
    */
    public boolean hasDefault() {
        return hasDefault;
    }

    /**
    * 列注释。
    *
    * @return 注释
    */
    public String comment() {
        return comment;
    }

    /**
    * 是否为 InnoDB 系统列（{@code DB_TRX_ID} / {@code DB_ROLL_PTR} / {@code DB_ROW_ID}）。
    *
    * @return 是系统列返回 true
    */
    public boolean systemColumn() {
        return COL_DB_TRX_ID.equals(name) || COL_DB_ROLL_PTR.equals(name) || COL_DB_ROW_ID.equals(name);
    }

    /**
    * 是否变长存储（记录里带长度前缀）。
    *
    * @return 变长返回 true
    */
    public boolean variableLength() {
        return type.isVariableLength();
    }

    /**
    * 长度前缀是否<b>可能</b>是 2 字节。
    *
    * <p>只有最大字节长度 &gt; 255 的列才有这个资格；实际是 1 还是 2 字节还要看
    * 具体值（见 {@link IbdRecordCursor}）。</p>
    *
    * @return 可能为 2 字节返回 true
    */
    public boolean big() {
        return charLength > 255;
    }

    /**
    * 定长列的字节宽度。
    *
    * @return 定长宽度；变长或未知类型返回 {@code -1}
    */
    public int fixedSize() {
        switch (name) {
            case COL_DB_TRX_ID:
                return SIZE_TRX_ID;
            case COL_DB_ROLL_PTR:
                return SIZE_ROLL_PTR;
            case COL_DB_ROW_ID:
                return SIZE_ROW_ID;
            default:
                break;
        }
        switch (type) {
            case TINY:
                return 1;
            case SHORT:
                return 2;
            case LONG:
                return 4;
            case LONGLONG:
                return 8;
            case INT24:
                return 3;
            case FLOAT:
                return 4;
            case DOUBLE:
                return 8;
            case YEAR:
                return 1;
            case DATE:
            case NEWDATE:
            case TIME:
                return 3;
            case DATETIME:
            case TIMESTAMP:
                return 8;
            case TIMESTAMP2:
                return 4 + fractionalBytes();
            case DATETIME2:
                return 5 + fractionalBytes();
            case TIME2:
                return 3 + fractionalBytes();
            case NEWDECIMAL:
                return decimalBinarySize(numericPrecision, numericScale);
            case ENUM:
                return elements.size() > 255 ? 2 : 1;
            case SET:
                return setBinarySize(elements.size());
            case BIT:
                return (numericPrecision + 7) / 8;
            case TYPE_NULL:
                return 0;
            default:
                return -1;
        }
    }

    /**
    * 小数秒占用的字节数。
    *
    * @return 小数秒字节数（精度 0 时为 0）
    */
    public int fractionalBytes() {
        return datetimePrecision <= 0 ? 0 : (datetimePrecision + 1) / 2;
    }

    /**
    * 该列文本使用的字符集。
    *
    * @return 字符集；二进制列返回 {@code null}（调用方应输出十六进制）
    */
    public Charset charset() {
        return charsetOf(collationId);
    }

    /**
    * 该列文本使用的 MySQL 字符集名。
    *
    * @return 如 {@code utf8mb4}；二进制列为 {@code binary}
    */
    public String charsetName() {
        return charsetNameOf(collationId);
    }

    /**
    * 按 MySQL 的紧凑二进制规则计算 {@code DECIMAL(p,s)} 的字节数。
    *
    * @param precision 精度
    * @param scale     标度
    * @return 字节数
    */
    public static int decimalBinarySize(int precision, int scale) {
        int intg = precision - scale;
        return (intg / 9) * 4 + DIG2BYTES[intg % 9] + (scale / 9) * 4 + DIG2BYTES[scale % 9];
    }

    /**
    * 计算 {@code SET} 的字节数。
    *
    * @param elementCount 元素个数
    * @return 1、2、3、4 或 8
    */
    public static int setBinarySize(int elementCount) {
        for (int size : new int[]{1, 2, 3, 4, 8}) {
            if (elementCount <= size * 8) {
                return size;
            }
        }
        return 8;
    }

    /**
    * 由排序规则 id 推出 MySQL 字符集名。
    *
    * <p>只覆盖常见字符集；其余一律按 {@code utf8mb4} 处理。</p>
    *
    * @param collationId 排序规则 id
    * @return 字符集名；{@code 63}（binary）返回 {@code binary}
    */
    public static String charsetNameOf(long collationId) {
        switch ((int) collationId) {
            case 63:
                return "binary";
            case 11:
            case 65:
                return "ascii";
            case 5:
            case 8:
            case 15:
            case 31:
            case 47:
            case 48:
            case 49:
            case 94:
                return "latin1";
            case 24:
                return "gb2312";
            case 28:
                return "gbk";
            case 248:
            case 249:
            case 250:
                return "gb18030";
            case 1:
                return "big5";
            case 12:
                return "ujis";
            case 13:
            case 95:
                return "sjis";
            case 19:
                return "euckr";
            case 2:
            case 9:
            case 21:
            case 27:
            case 69:
                return "latin2";
            case 30:
            case 70:
                return "latin5";
            case 20:
            case 41:
            case 42:
            case 71:
                return "latin7";
            case 26:
            case 34:
            case 44:
            case 66:
            case 99:
                return "cp1250";
            case 14:
            case 23:
            case 50:
            case 51:
            case 52:
                return "cp1251";
            case 29:
            case 58:
            case 59:
                return "cp1257";
            case 57:
            case 67:
                return "cp1256";
            case 16:
            case 64:
                return "hebrew";
            case 25:
                return "greek";
            case 4:
            case 80:
                return "cp850";
            case 36:
            case 68:
                return "cp866";
            case 33:
            case 83:
                return "utf8mb3";
            default:
                return "utf8mb4";
        }
    }

    /**
    * 由排序规则 id 推出 Java 字符集。
    *
    * <p>只覆盖常见字符集；其余一律按 UTF-8 处理（解码失败会以替换字符兜底，
    * 不会因为一个坏字节丢掉整行数据）。</p>
    *
    * @param collationId 排序规则 id
    * @return 字符集；{@code 63}（binary）返回 {@code null}
    */
    public static Charset charsetOf(long collationId) {
        String name = charsetNameOf(collationId);
        switch (name) {
            case "binary":
                return null;
            case "ascii":
                return StandardCharsets.US_ASCII;
            case "latin1":
                return StandardCharsets.ISO_8859_1;
            case "utf8mb4":
            case "utf8mb3":
                return StandardCharsets.UTF_8;
            default:
                return charsetOrUtf8(javaCharsetName(name));
        }
    }

    /**
    * 把 MySQL 字符集名映射成 Java 字符集名（差异较大的才需要映射）。
    *
    * @param mysqlName MySQL 字符集名
    * @return Java 字符集名
    */
    private static String javaCharsetName(String mysqlName) {
        switch (mysqlName) {
            case "ujis":
                return "EUC-JP";
            case "sjis":
                return "Shift_JIS";
            case "euckr":
                return "EUC-KR";
            case "hebrew":
                return "ISO-8859-8";
            case "greek":
                return "ISO-8859-7";
            case "latin2":
                return "ISO-8859-2";
            case "latin5":
                return "ISO-8859-9";
            case "latin7":
                return "ISO-8859-13";
            case "cp1250":
                return "windows-1250";
            case "cp1251":
                return "windows-1251";
            case "cp1256":
                return "windows-1256";
            case "cp1257":
                return "windows-1257";
            case "cp850":
                return "Cp850";
            case "cp866":
                return "Cp866";
            case "big5":
                return "Big5";
            case "gb2312":
            case "gbk":
            case "gb18030":
                return "GBK";
            default:
                return mysqlName;
        }
    }

    /**
    * 按名字取字符集，取不到时退回 UTF-8。
    *
    * @param name 字符集名
    * @return 字符集
    */
    private static Charset charsetOrUtf8(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    @Override
    public String toString() {
        return name + " " + typeText + (nullable ? "" : " NOT NULL");
    }
}
