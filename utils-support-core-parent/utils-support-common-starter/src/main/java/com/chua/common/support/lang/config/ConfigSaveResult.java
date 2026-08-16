package com.chua.common.support.lang.config;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置保存结果对象
 * <p>
 * 用于封装配置保存操作的结果信息，包括是否成功、配置键、存储位置、文件大小、更新时间及消息等。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class ConfigSaveResult {

    /**
     * 操作是否成功
     */
    private boolean success;

    /**
     * 配置的键（Key）
     */
    private String key;

    /**
     * 配置文件或数据的存储位置
     * <p>
     * 例如：文件路径或数据库连接字符串等。
     * </p>
     */
    private String location;

    /**
     * 保存后的文件大小（字节）
     */
    private long size;

    /**
     * 配置最后更新的时间
     * <p>
     * 默认为当前时间。
     * </p>
     */
    @Builder.Default
    private LocalDateTime updateTime = LocalDateTime.now();

    /**
     * 操作返回的消息
     * <p>
     * 成功时通常为 {@code "success"}，失败时为具体的错误描述。
     * </p>
     */
    private String message;

    /**
     * 构建一个成功的配置保存结果
     *
     * @param key      配置的键
     * @param location 存储位置
     * @param size     文件大小
     * @return 成功的配置保存结果对象
     */
    public static ConfigSaveResult success(String key, String location, long size) {
        return ConfigSaveResult.builder()
                .success(true)
                .key(key)
                .location(location)
                .size(size)
                .message("success")
                .build();
    }

    /**
     * 构建一个失败的配置保存结果
     *
     * @param key     配置的键
     * @param message 失败原因描述
     * @return 失败的配置保存结果对象
     */
    public static ConfigSaveResult failure(String key, String message) {
        return ConfigSaveResult.builder()
                .success(false)
                .key(key)
                .message(message)
                .build();
    }
}
