package com.chua.ueba.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
* IP 异常检测结果 DTO。
* <p>
* auto编码器 推理后输出的单条 IP 事件异常评分与标签。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IpAnomalyResult implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
    * 被分析的 IP 地址
     */
    private String ip;

    /**
    * 重建误差（reconstruction 错误），越大越异常
     */
    private double reconstructionError;

    /**
    * 是否判定为异常
     */
    private boolean anomalous;

    /**
    * 异常等级：LOW / MEDIUM / HIGH / CRITICAL
     */
    private AnomalyLevel level;

    /**
    * 异常描述
     */
    private String reason;

    /**
    * 用于判断的阈值（可配置）
     */
    private double threshold;

    /**
    * 异常等级枚举
    * @author CH
    * @since 4.0.0
     */
    public enum AnomalyLevel {
        NORMAL, LOW, MEDIUM, HIGH, CRITICAL
    }
}
