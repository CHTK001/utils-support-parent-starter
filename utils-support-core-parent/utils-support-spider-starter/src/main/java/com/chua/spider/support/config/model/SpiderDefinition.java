package com.chua.spider.support.config.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 爬虫定义实体。
 *
 * <p>对应前端 SpiderDefinition 接口，包含爬虫的基本配置信息，
 * 如编码、名称、入口 URL、状态等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class SpiderDefinition {

    /**
     * 爬虫唯一标识
     */
    private Long spiderId;

    /**
     * 爬虫编码（业务唯一）
     */
    private String spiderCode;

    /**
     * 爬虫名称
     */
    private String spiderName;

    /**
     * 入口 URL
     */
    private String spiderEntryUrl;

    /**
     * 执行器类型（ONCE / CRON / MANUAL）
     */
    private String spiderExecutorType;

    /**
     * 执行轮次
     */
    private Integer spiderExecutorRounds;

    /**
     * 是否采集深层 URL（0 否 / 1 是）
     */
    private Integer spiderCollectDeepUrl;

    /**
     * 最大爬取深度
     */
    private Integer spiderMaxDepth;

    /**
     * 最大爬取页面数
     */
    private Integer spiderMaxPages;

    /**
     * 去重策略
     */
    private String spiderDedupStrategy;

    /**
     * URL 存储类型
     */
    private String spiderUrlStorageType;

    /**
     * 状态（0 禁用 / 1 启用）
     */
    private Integer spiderStatus;

    /**
     * 是否开启定时（0 否 / 1 是）
     */
    private Integer spiderScheduleEnable;

    /**
     * 定时 Cron 表达式
     */
    private String spiderScheduleCron;

/**
 * 描述
 */
private String spiderDescription;

/**
 * 是否启用代理池（0 否 / 1 是）
 */
private Integer spiderProxyPoolEnable;

/**
 * 绑定的代理池编码
 */
private String spiderProxyPoolCode;

/**
 * 公共 Cookie（JSON 数组字符串 [{"name":"sid","value":"abc","domain":".example.com"}]）
 */
private String spiderCookies;

/**
 * 公共请求头（JSON 字符串 {"User-Agent":"..."}）
 */
private String spiderHeaders;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}