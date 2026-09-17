package com.chua.common.support.math.unit.size;


/**
* 数据大小单位枚举。
*
* <p>支持 B、KB、MB、GB、TB、PB 的表示与换算。</p>
*
* @author CH
* @since 4.0.0.42
 */
public enum SizeUnit {

    /**
    * 字节
    */
    BYTES("B", 1L),
    /**
    * 千字节
    */
    KILOBYTES("KB", 1024L),
    /**
    * 兆字节
    */
    MEGABYTES("MB", 1024L * 1024L),
    /**
    * 吉字节
    */
    GIGABYTES("GB", 1024L * 1024L * 1024L),
    /**
    * 太字节
    */
    TERABYTES("TB", 1024L * 1024L * 1024L * 1024L),
    /**
    * 拍字节
    */
    PETABYTES("PB", 1024L * 1024L * 1024L * 1024L * 1024L);

    /**
    * 单位名称数组，用于格式化输出
    */
    public static final String[] UNIT_NAMES = {"B", "KB", "MB", "GB", "TB", "PB"};

    /**
    * 单位后缀
    */
    private final String suffix;

    /**
    * 对应的字节数
    */
    private final long byteSize;

    SizeUnit(String suffix, long byteSize) {
        this.suffix = suffix;
        this.byteSize = byteSize;
    }

    /**
    * 获取单位后缀
    *
    * @return 单位后缀
    */
    public String suffix() {
        return suffix;
    }

    /**
    * 获取对应的字节数
    *
    * @return 字节数
    */
    public long toByteSize() {
        return byteSize;
    }

    /**
    * 根据后缀查找单位
    *
    * @param suffix 单位后缀
    * @return 匹配的 SizeUnit，未匹配返回 BYTES
    */
    public static SizeUnit fromSuffix(String suffix) {
        for (SizeUnit unit : values()) {
            if (unit.suffix.equalsIgnoreCase(suffix)) {
                return unit;
            }
        }
        return BYTES;
    }
}
