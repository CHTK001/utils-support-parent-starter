package com.chua.spider.support.config.model;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;


import lombok.Data;

import java.time.LocalDateTime;

/**
* 爬虫执行记录。
*
* @author CH
* @since 4.0.0.42
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
public class SpiderExecutionRecord {

    /**
    * 批次号（执行唯一标识）
    */
    private String executionNo;

    /**
    * 关联的爬虫编码
    */
    private String spiderCode;

    /**
    * 执行状态：RUNNING / 成功 / 失败
    */
    private String status;

    /**
    * 开始时间
    */
    private LocalDateTime startTime;

    /**
    * 结束时间
    */
    private LocalDateTime endTime;

    /**
    * 错误信息（失败时记录）
    */
    private String errorMessage;

    /**
    * 抓取到的条目数
    */
    private Integer resultCount;

    /**
    * 抓取结果 JSON 字符串（蜘蛛结果 列表序列化）
    */
    private String resultsJson;
}
