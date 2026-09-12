package com.chua.prometheus.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prometheus 抓取目标
 * <p>
   * 对应 {@code /api/v1/targets} 返回的 活跃Target 元素。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrometheusTarget {

    /**
     * 抓取目标地址
     */
    private String scrapeUrl;

    /**
      * 所属 作业
     */
    private String job;

    /**
     * 目标实例
     */
    private String instance;

    /**
     * 健康状态: up / down / unknown
     */
    private String health;

    /**
     * 最近一次抓取错误
     */
    private String lastError;

    /**
     * 最近抓取时间(毫秒)
     */
    private long lastScrape;

    /**
     * 抓取时长(毫秒)
     */
    private double scrapeDuration;
}