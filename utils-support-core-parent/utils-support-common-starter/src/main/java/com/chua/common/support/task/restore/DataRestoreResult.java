package com.chua.common.support.task.restore;

import lombok.Builder;
import lombok.Data;

import java.io.File;
import java.util.List;

/**
 * 数据还原结果。
 *
 * <p>封装一次数据还原操作的执行结果，包括成功标识、输出文件、耗时与错误信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
@Data
@Builder
public class DataRestoreResult {

    /**
    * 是否成功
    */
    private boolean success;

    /**
    * 输出文件列表
    */
    @Builder.Default
    private List<File> outputFiles = List.of();

    /**
    * 输出文件总数
    */
    private int fileCount;

    /**
    * 输出总大小（字节）
    */
    private long totalSize;

    /**
    * 耗时（毫秒）
    */
    private long durationMillis;

    /**
    * 错误信息
    */
    private String errorMessage;

    /**
    * 创建成功结果。
    *
    * @param outputFiles 输出文件列表
    * @param totalSize   输出总大小（字节）
    * @param duration    耗时（毫秒）
    * @return 成功结果
    */
    public static DataRestoreResult success(List<File> outputFiles, long totalSize, long duration) {
        if (outputFiles == null) {
            throw new IllegalArgumentException("输出文件列表不能为空");
        }
        return DataRestoreResult.builder()
                .success(true)
                .outputFiles(outputFiles)
                .fileCount(outputFiles.size())
                .totalSize(totalSize)
                .durationMillis(duration)
                .build();
    }

    /**
    * 创建失败结果。
    *
    * @param errorMessage 失败原因
    * @return 失败结果
    */
    public static DataRestoreResult failure(String errorMessage) {
        return DataRestoreResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
