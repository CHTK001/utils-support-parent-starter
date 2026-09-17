package com.chua.common.support.config.loader;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
* 配置保存结果类
* <p>
* 用于封装配置保存操作的结果信息，包括是否成功、键值、存储位置、文件大小、更新时间和消息等。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
public class ConfigSaveResult {

    /**
    * 表示配置保存操作是否成功
    * true 表示成功，false 表示失败
    */
    private boolean success;

    /**
    * 配置的键名
    * 用于唯一标识该配置项
    */
    private String key;

    /**
    * 配置文件的存储位置
    * <p>
    * 例如：文件路径或数据库表名等
    * </p>
    */
    private String location;

    /**
    * 配置文件的大小（单位：字节）
    * 仅在保存成功时有效
    */
    private long size;

    /**
    * 配置最后更新的时间
    * <p>
    * 默认值为当前系统时间
    * </p>
    */
    @Builder.Default
    /** Update时间 */
    private LocalDateTime updateTime = LocalDateTime.now();

    /**
    * 操作返回的消息信息
    * <p>
    * 成功时通常为 "success"，失败时为具体的错误描述
    * </p>
    */
    private String message;

    /**
    * 构建一个成功的配置保存结果对象
    *
    * @param key      配置的键名
    * @param location 配置文件的存储位置
    * @param size     配置文件的大小（单位：字节）
    * @return 配置保存结果对象
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
    * 构建一个失败的配置保存结果对象
    *
    * @param key     配置的键名
    * @param message 失败原因的描述信息
    * @return 配置保存结果对象
    */
    public static ConfigSaveResult failure(String key, String message) {
        return ConfigSaveResult.builder()
                .success(false)
                .key(key)
                .message(message)
                .build();
    }
}
