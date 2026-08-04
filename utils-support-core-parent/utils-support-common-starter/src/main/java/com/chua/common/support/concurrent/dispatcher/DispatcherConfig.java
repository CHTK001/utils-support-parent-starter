package com.chua.common.support.concurrent.dispatcher;

import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.NullUnmarked;

/**
 * 分发器配置工具类，封装消息分发器所需的连接参数、超时设置和消费行为。
 * <p>
 * 该类通过 Lombok 的 {@code @Builder} 注解生成构造器，便于在不同实现中快速创建配置信息。
 * </p>
 *
 * @author CH
 * @since 2025-11-26
 */
@NullUnmarked
@Data
@Builder
public class DispatcherConfig {

    /**
     * 中间件连接地址
     */
    private String url;

    /**
     * 数据存放路径（适用于文件型中间件）
     */
    private String dataPath;

    /**
     * 偏移量存放路径
     */
    private String offsetPath;

    /**
     * 连接超时时间，单位毫秒
     */
    @Builder.Default
    private long connectionTimeoutMillis = 5000;

    /**
     * 会话超时时间，单位毫秒
     */
    @Builder.Default
    private long sessionTimeoutMillis = 10000;

    /**
     * 最大重试次数
     */
    @Builder.Default
    /**
     * 最大重试次数
     */
    private int maxRetries = 3;

    /**
     * 消费者组标识
     */
    private String groupId;

    /**
     * 客户端标识
     */
    private String clientId;

    /**
     * 自动确认偏移量
     */
    @Builder.Default
    private boolean autoCommitOffset = true;

    /**
     * 批量消费最大条数
     */
    @Builder.Default
    private int maxBatchSize = 100;
}
