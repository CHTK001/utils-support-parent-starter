package com.chua.common.support.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
* 同步位点
* <p>记录输入源读取数据的位置信息，用于断点续传和进度追踪。</p>
*
* <p>位点由三部分组成：</p>
* <ol>
*   <li>inputId：所属输入源标识</li>
*   <li>value：位点值（如自增主键、时间戳、binlog 偏移量等）</li>
*   <li>timestamp：位点产生时间</li>
* </ol>
*
* @author CH
* @since 2026/07/28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Position implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
    * 输入源标识
     */
    private String inputId;

    /**
    * 位点值（如自增主键、时间戳、binlog 偏移量等）
     */
    private String value;

    /**
    * 位点产生时间（毫秒时间戳）
     */
    private long timestamp;

    /**
    * 创建位点
    *
    * @param inputId 输入源标识
    * @param value   位点值
    * @return 位点实例
     */
    public static Position of(String inputId, String value) {
        return Position.builder()
                .inputId(inputId)
                .value(value)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
