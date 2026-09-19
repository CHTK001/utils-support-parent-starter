package com.chua.common.support.task.restore;

import lombok.Builder;
import lombok.Data;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据还原配置。
 *
 * <p>控制数据还原的输出行为，包括输出格式、输出目录、目标库表、字符集及扩展参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class DataRestoreConfig {

    /**
     * 输出格式，默认 CSV
     */
    @Builder.Default
    private ExportFormat format = ExportFormat.CSV;

    /**
     * 输出目录，默认取源文件所在目录
     */
    private File outputDir;

    /**
     * 目标库名（SQL 导出时的建库语句）
     */
    private String targetSchema;

    /**
     * 目标表名（SQL 导出时的建表语句）
     */
    private String targetTable;

    /**
     * 是否包含表结构（SQL 导出 DDL 语句），默认包含
     */
    @Builder.Default
    private boolean includeStructure = true;

    /**
     * 输出字符集，默认 UTF-8
     */
    @Builder.Default
    private String charset = "UTF-8";

    /**
     * 扩展参数，供具体实现读取（如微信数据库密钥、IBD 页大小等）
     */
    @Builder.Default
    private Map<String, Object> options = new HashMap<>(8);
}
