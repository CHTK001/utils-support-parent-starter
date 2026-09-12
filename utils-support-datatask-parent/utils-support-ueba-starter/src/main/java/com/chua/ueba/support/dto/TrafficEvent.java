package com.chua.ueba.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
* 网络流量事件 DTO。
* <p>
* 承载一条 HTTP/访问日志的原始字段，供 UEBA 引擎进行分析。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficEvent implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
    * 请求来源 IP 地址
     */
    private String ip;

    /**
    * 请求路径（URI 路径）
     */
    private String path;

    /**
    * HTTP 方法（获取/POST/放入/删除 等）
     */
    private String method;

    /**
    * HTTP 状态码
     */
    private int statusCode;

    /**
    * 用户-智能体 字符串
     */
    private String userAgent;

    /**
    * 请求时间戳（毫秒）
     */
    private long timestamp;

    /**
    * 响应耗时（毫秒）
     */
    private int responseTimeMs;

    /**
    * 响应体大小（字节）
     */
    private long responseSizeBytes;

    /**
    * 会话 标识（可选，用于同一会话关联）
     */
    private String sessionId;

    /**
    * 构建特征向量输入用的简化描述，供 minimind 使用
    *
    * @return 行为摘要文本
     */
    public String toBehaviorSummary() {
        return String.format("IP=%s, PATH=%s, METHOD=%s, STATUS=%d, UA=%s, RESPONSE_TIME=%dms",
                ip, path, method, statusCode,
                userAgent != null ? userAgent.substring(0, Math.min(60, userAgent.length())) : "unknown",
                responseTimeMs);
    }
}
