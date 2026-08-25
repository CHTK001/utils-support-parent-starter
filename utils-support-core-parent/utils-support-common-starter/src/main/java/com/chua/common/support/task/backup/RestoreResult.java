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
    private boolean success;

    /** 恢复目标目录 */
    private Path targetDir;

    /** 恢复的文件列表 */
    @Builder.Default
    private List<Path> files = List.of();

    /** 恢复的文件总数 */
    @Builder.Default
    private int fileCount = 0;

    /** 恢复的总大小（字节） */
    @Builder.Default
    private long totalSize = 0;

    /** 耗时（毫秒） */
    @Builder.Default
    private long durationMillis = 0;

    /** 错误信息 */
    private String errorMessage;

    /**
     * 创建成功结果。
     *
     * @param target   恢复目标目录
     * @param files    恢复的文件列表
     * @param size     恢复的总大小（字节）
     * @param duration 耗时毫秒
     * @return 成功结果
     */
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

    /**
     * 创建失败结果。
     *
     * @param errorMessage 失败原因
     * @return 失败结果
     */
    public static RestoreResult failure(String errorMessage) {
        return RestoreResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
