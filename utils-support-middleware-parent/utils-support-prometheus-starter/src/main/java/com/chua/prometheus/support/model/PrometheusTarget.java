package com.chua.prometheus.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prometheus 抓取目标
 * <p>
 * 对应 {@code /api/v1/targets} 返回的 activeTargets 元素。
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
     * 所属 job
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
     * 最近抓取时间(epoch 毫秒), 0 表示远端未返回
     */
    private long lastScrape;

    /**
     * 抓取耗时(秒, 对应远端 lastScrapeDuration)
     */
    private double scrapeDuration;

    /**
     * 目标是否健康
     *
     * @return true 表示 health=up
     */
    public boolean isUp() {
        return "up".equalsIgnoreCase(health);
    }
}
