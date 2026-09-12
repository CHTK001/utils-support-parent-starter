package com.chua.springboot.support.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
* 请求折叠全局默认配置。
*
* <p>前缀 {@code collapse.executor}。全局默认值仅对注解属性未显式指定的场景生效
* （注解 {@code waitThreshold=-1}/{@code collectingWaitTime=-2} 表示未指定）。</p>
*
* @author CH
* @since 2026/09/04
 */
@ConfigurationProperties(prefix = "collapse.executor")
public class CollapseProperties {

    /**
    * 批量收集阈值默认值
     */
    private int waitThreshold = 10;

    /**
    * 补收等待时间默认值（毫秒）
     */
    private long collectingWaitTime = 0;

    /**
    * 获取批量收集阈值默认值。
    *
    * @return 批量收集阈值默认值
     */
    public int getWaitThreshold() {
        return waitThreshold;
    }

    /**
    * 设置批量收集阈值默认值。
    *
    * @param waitThreshold 批量收集阈值默认值
     */
    public void setWaitThreshold(int waitThreshold) {
        this.waitThreshold = waitThreshold;
    }

    /**
    * 获取补收等待时间默认值（毫秒）。
    *
    * @return 补收等待时间默认值
     */
    public long getCollectingWaitTime() {
        return collectingWaitTime;
    }

    /**
    * 设置补收等待时间默认值（毫秒）。
    *
    * @param collectingWaitTime 补收等待时间默认值
     */
    public void setCollectingWaitTime(long collectingWaitTime) {
        this.collectingWaitTime = collectingWaitTime;
    }
}
