package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.List;

/**
 * 恢复结果
 *
 * <p>封装一次恢复操作的执行结果。
 *
 * @author CH
 * @since 2026/07/16
 */
@Data
@Builder
public class RestoreResult {

    /** 是否成功 */
    /**
     * 是否成功
     */
    private boolean success;

    /** 恢复目标目录 */
    /** 目标目录 */
    private Path targetDir;

    /** 恢复的文件列表 */
    @Builder.Default
    /** Files */
    private List<Path> files = List.of();

    /** 恢复的文件总数 */
    @Builder.Default
    /** 文件数量 */
    private int fileCount = 0;

    /** 恢复的总大小（字节） */
    @Builder.Default
    /** 总数尺寸 */
    private long totalSize = 0;

    /** 耗时（毫秒） */
    @Builder.Default
    /** 持续时间毫秒 */
    private long durationMillis = 0;

    /** 错误信息 */
    /** 错误消息 */
    private String errorMessage;

    public static RestoreResult success(Path target, List<Path> files, long size, long duration) {
        return RestoreResult.builder()
                .success(true)
                .targetDir(target)
                .files(files)
                .fileCount(files.size())
                .totalSize(size)
                .durationMillis(duration)
                .build();
    }

    public static RestoreResult failure(String errorMessage) {
        return RestoreResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
