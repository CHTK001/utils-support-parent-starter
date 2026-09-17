package com.chua.common.support.task.restore;

import java.util.Locale;

/**
 * 数据还原输出格式枚举。
 *
 * <p>定义数据还原接口支持的输出文件格式，并预留扩展点：</p>
 * <ul>
 *   <li>CSV — 逗号分隔的文本表格</li>
 *   <li>SQL — 结构化查询语言脚本（建表 DDL + INSERT 数据）</li>
 *   <li>EXCEL — Excel 工作簿（xlsx）</li>
 *   <li>JSON — JSON 文档</li>
 * </ul>
 *
 * <p>新增格式时在此追加枚举项即可，具体写入逻辑由各 starter 的 SPI 实现完成。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
public enum ExportFormat {

    /**
    * 逗号分隔值文本格式
    */
    CSV("csv", ".csv"),

    /**
    * 结构化查询语言脚本格式
    */
    SQL("sql", ".sql"),

    /**
    * Excel 工作簿格式
    */
    EXCEL("excel", ".xlsx"),

    /**
    * JSON 文档格式
    */
    JSON("json", ".json");

    /**
    * 格式名称（SPI 注册名）
    */
    private final String value;

    /**
    * 默认文件后缀
    */
    private final String extension;

    /**
    * 构造器。
    *
    * @param value     格式名称
    * @param extension 默认文件后缀
    */
    ExportFormat(String value, String extension) {
        this.value = value;
        this.extension = extension;
    }

    /**
    * 解析格式名称。
    *
    * <p>忽略大小写与前后空白，兼容 ".csv" 形式的输入。</p>
    *
    * @param value 格式名称，如 "csv"、".SQL"、"Excel"
    * @return 匹配的格式枚举
    * @throws IllegalArgumentException 无法匹配时抛出
    */
    public static ExportFormat of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("输出格式不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        for (ExportFormat format : values()) {
            if (format.value.equals(normalized)) {
                return format;
            }
        }
        throw new IllegalArgumentException("不支持的输出格式: " + value);
    }

    /**
    * 获取格式名称。
    *
    * @return 格式名称
    */
    public String value() {
        return value;
    }

    /**
    * 获取默认文件后缀。
    *
    * @return 文件后缀（含前导点）
    */
    public String extension() {
        return extension;
    }
}
