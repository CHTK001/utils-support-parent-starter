package com.chua.common.support.task.restore;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * 数据还原抽象基类。
 *
 * <p>提供还原流程的通用骨架：参数校验、输出目录兜底、耗时统计与异常包装，
 * 子类只需实现 {@link #doRestore(File, DataRestoreConfig)} 完成具体还原逻辑。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractDataRestore implements DataRestore {

    /**
     * 数据源类型名称
     */
    private final String type;

    /**
     * 默认还原配置
     */
    private final DataRestoreConfig defaultConfig;

    /**
     * 构造器。
     *
     * @param type 数据源类型名称
     */
    protected AbstractDataRestore(String type) {
        this(type, DataRestoreConfig.builder().build());
    }

    /**
     * 构造器。
     *
     * @param type   数据源类型名称
     * @param config 默认还原配置
     */
    protected AbstractDataRestore(String type, DataRestoreConfig config) {
        this.type = type;
        this.defaultConfig = config != null ? config : DataRestoreConfig.builder().build();
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public DataRestoreResult restore(File source) throws Exception {
        return restore(source, defaultConfig);
    }

    @Override
    public DataRestoreResult restore(File source, DataRestoreConfig config) throws Exception {
        // 校验源文件
        if (source == null) {
            throw new IllegalArgumentException("还原源文件不能为空");
        }
        if (!source.exists() || !source.isFile()) {
            throw new IllegalArgumentException("还原源文件不存在或不是文件: " + source.getAbsolutePath());
        }

        // 输出目录缺省为源文件所在目录
        File outputDir = config.getOutputDir();
        if (outputDir == null) {
            outputDir = source.getParentFile();
        }
        if (outputDir != null && !outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("创建输出目录失败: " + outputDir.getAbsolutePath());
        }

        long startMillis = System.currentTimeMillis();
        try {
            DataRestoreResult result = doRestore(source, config);
            if (result == null) {
                return DataRestoreResult.failure("还原实现返回空结果: " + type);
            }
            result.setDurationMillis(System.currentTimeMillis() - startMillis);
            return result;
        } catch (Exception e) {
            log.error("数据还原失败, 类型: {}, 源文件: {}", type, source.getAbsolutePath(), e);
            DataRestoreResult result = DataRestoreResult.failure(e.getMessage());
            result.setDurationMillis(System.currentTimeMillis() - startMillis);
            return result;
        }
    }

    /**
     * 执行具体的数据还原逻辑。
     *
     * @param source 数据源文件
     * @param config 还原配置
     * @return 还原结果，不能返回 null
     * @throws Exception 还原过程中可能抛出的异常
     */
    protected abstract DataRestoreResult doRestore(File source, DataRestoreConfig config) throws Exception;
}