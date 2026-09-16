package com.chua.ibd.support.innodb;

import java.util.EnumSet;
import java.util.Set;

/**
 * InnoDB / MySQL 数据字典里的列类型（对应 {@code dd::enum_column_types}）。
 *
 * <p>SDI JSON 里 {@code columns[].type} 就是这个枚举的<b>序号</b>（从 1 开始），
 * 与 {@code information_schema} 里常见的 {@code MYSQL_TYPE_*} 枚举<b>不是</b>同一套，
 * 千万别混用 —— 例如这里的 {@code 16} 是 {@code VARCHAR}，
 * 而 {@code MYSQL_TYPE_*} 里 {@code 16} 是 {@code BIT}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum IbdColumnType {

    /**
     * 无法识别的类型。
     */
    UNKNOWN(0),

    /**
     * 旧版定点数（MySQL 5.0 之前）。
     */
    DECIMAL(1),

    /**
     * {@code TINYINT}，1 字节。
     */
    TINY(2),

    /**
     * {@code SMALLINT}，2 字节。
     */
    SHORT(3),

    /**
     * {@code INT}，4 字节。
     */
    LONG(4),

    /**
     * {@code FLOAT}，4 字节。
     */
    FLOAT(5),

    /**
     * {@code DOUBLE}，8 字节。
     */
    DOUBLE(6),

    /**
     * SQL {@code NULL} 类型。
     */
    TYPE_NULL(7),

    /**
     * 旧版 {@code TIMESTAMP}，4 字节。
     */
    TIMESTAMP(8),

    /**
     * {@code BIGINT}，8 字节。
     */
    LONGLONG(9),

    /**
     * 内部 3 字节整数；事务 id（{@code DB_TRX_ID}）也复用它，但占 6 字节。
     */
    INT24(10),

    /**
     * {@code DATE}，3 字节。
     */
    DATE(11),

    /**
     * 旧版 {@code TIME}，3 字节。
     */
    TIME(12),

    /**
     * 旧版 {@code DATETIME}，8 字节。
     */
    DATETIME(13),

    /**
     * {@code YEAR}，1 字节。
     */
    YEAR(14),

    /**
     * 旧版 {@code DATE}。
     */
    NEWDATE(15),

    /**
     * {@code VARCHAR}，变长。
     */
    VARCHAR(16),

    /**
     * {@code BIT(n)}，{@code ceil(n/8)} 字节。
     */
    BIT(17),

    /**
     * {@code TIMESTAMP(fsp)}，4 字节 + 小数秒。
     */
    TIMESTAMP2(18),

    /**
     * {@code DATETIME(fsp)}，5 字节 + 小数秒。
     */
    DATETIME2(19),

    /**
     * {@code TIME(fsp)}，3 字节 + 小数秒。
     */
    TIME2(20),

    /**
     * {@code DECIMAL}，紧凑二进制编码。
     */
    NEWDECIMAL(21),

    /**
     * {@code ENUM}，1 或 2 字节序号。
     */
    ENUM(22),

    /**
     * {@code SET}，1/2/3/4/8 字节位图。
     */
    SET(23),

    /**
     * {@code TINYTEXT} / {@code TINYBLOB}，变长。
     */
    TINY_BLOB(24),

    /**
     * {@code MEDIUMTEXT} / {@code MEDIUMBLOB}，变长。
     */
    MEDIUM_BLOB(25),

    /**
     * {@code LONGTEXT} / {@code LONGBLOB}，变长。
     */
    LONG_BLOB(26),

    /**
     * {@code TEXT} / {@code BLOB}，变长。
     */
    BLOB(27),

    /**
     * 内部变长字符串。
     */
    VAR_STRING(28),

    /**
     * 定长 {@code CHAR} 在记录里仍按变长存储。
     */
    STRING(29),

    /**
     * 空间类型，变长。
     */
    GEOMETRY(30),

    /**
     * {@code JSON}，变长。
     */
    JSON(31);

    /**
     * 在记录里以「长度前缀 + 内容」形式存储的类型。
     */
    private static final Set<IbdColumnType> VARIABLE = EnumSet.of(
            VARCHAR, VAR_STRING, STRING, TINY_BLOB, MEDIUM_BLOB, LONG_BLOB, BLOB, GEOMETRY, JSON);

    /**
     * SDI 里的类型序号。
     */
    private final int code;

    IbdColumnType(int code) {
        this.code = code;
    }

    /**
     * 取 SDI 里的类型序号。
     *
     * @return 类型序号
     */
    public int code() {
        return code;
    }

    /**
     * 判断该类型在记录里是否为变长字段。
     *
     * @return 变长返回 true
     */
    public boolean isVariableLength() {
        return VARIABLE.contains(this);
    }

    /**
     * 按序号查枚举。
     *
     * @param code SDI 里的类型序号
     * @return 对应枚举；未知返回 {@link #UNKNOWN}
     */
    public static IbdColumnType of(int code) {
        for (IbdColumnType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
