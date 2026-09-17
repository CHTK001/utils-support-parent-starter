package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.List;

/**
 * 备份结果
 *
 * <p>封装一次备份操作的执行结果，包括成功/失败、文件列表、耗时等。
 *
 * @author CH
 * @since 2026/07/16
*/
@Data
@Builder
public class BackupResult {

    /**
    * 是否成功
    */
    private boolean success;

    /**
    * 备份的目标目录
    */
    private Path backupPath;

    /**
    * 备份的文件列表
    */
    @Builder.Default
    private List<Path> files = List.of(); // 文件

    /**
    * 备份的文件总数
    */
    @Builder.Default
    private int fileCount = 0; // 文件数量

    /**
    * 备份的总大小（字节）
    */
    @Builder.Default
    private long totalSize = 0; // total大小

    /**
    * 耗时（毫秒）
    */
    @Builder.Default
    private long durationMillis = 0; // 持续时间millis

    /**
    * 错误信息
    */
    private String errorMessage;

    /**
    * 创建成功结果
    * @param path 路径
    * @param files 文件
    * @param size 大小
    * @param duration 持续时间
    * @return 成功的结果
    */
    public static BackupResult success(Path path, List<Path> files, long size, long duration) {
        return BackupResult.builder()
                .success(true)
                .backupPath(path)
                .files(files)
                .fileCount(files.size())
                .totalSize(size)
                .durationMillis(duration)
                .build();
    }

    /**
    * 创建失败结果
    * @param errorMessage 错误消息
    * @return 失败的结果
    */
    public static BackupResult failure(String errorMessage) {
        return BackupResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
