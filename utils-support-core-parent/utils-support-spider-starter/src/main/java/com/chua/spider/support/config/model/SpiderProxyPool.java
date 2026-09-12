package com.chua.spider.support.config.model;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;


import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 代理池。
 *
 * <p>供爬虫按策略（轮询/随机）选择代理节点，避免单 IP 频次限制。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
public class SpiderProxyPool {

    /**
     * 代理池唯一标识
     */
    private Long poolId;

    /**
     * 代理池名称（业务唯一）
     */
    private String poolCode;

    /**
     * 代理池描述
     */
    private String poolName;

    /**
      * 代理池策略（ROUND 轮询 / 随机 随机）
     */
    private String poolStrategy = "ROUND";

    /**
     * 代理池状态（0 禁用 / 1 启用）
     */
    private Integer poolStatus = 1;

    /**
     * 代理节点列表
     */
    private List<SpiderProxy> proxies = new ArrayList<>();
}