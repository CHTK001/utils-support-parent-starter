package com.chua.common.support.storage.setting;

import lombok.Builder;
import lombok.Data;

/**
 * 对象存储（Bucket）配置。
 *
 * <p>包含连接对象存储服务所需的全部配置信息：
 * 端点、访问密钥、密钥、bucket 名称、区域等。</p>
 *
 * @author CH
 * @since 1.0
 */
@Data
@Builder
public class BucketSetting {

    /**
     * Bucket 名称（存储空间名）。
     */
    private String bucket;

    /**
     * 区域（region），如 "cn-hangzhou"、"us-east-1" 等。
     */
    private String region;

    /**
     * Access 键 标识（访问密钥 标识）。
     */
    private String accessKeyId;

    /**
     * Access 键 Secret（访问密钥密钥）。
     */
    private String accessKeySecret;

    /**
     * 端点 地址，如 "https://oss-cn-hangzhou.aliyuncs.com"。
     */
    private String endpoint;

    /**
     * 连接超时时间（毫秒），默认 10 秒。
     */
    @Builder.Default
    /** Connection超时mills */
    private long connectionTimeoutMills = 10 * 1000;

    /**
    * 会话超时时间（毫秒），默认 10 秒。
    */
    @Builder.Default
    /** 会话超时mills */
    private long sessionTimeoutMills = 10 * 1000;

    /**
    * 附加配置属性，用于扩展 SDK 特定参数。
    */
    private java.util.Map<String, String> extraProperties;
}
