package com.chua.common.support.apm.model;

import com.chua.common.support.apm.OtnlTag;

import java.util.List;

/**
 * 日志记录（SkyWalking Logs 页的对齐结构）。
 *
 * @param timestamp    毫秒戳
 * @param serviceName  服务名
 * @param instanceName 实例名
 * @param endpointName 端点名
 * @param traceId      关联链路 ID（可空）
 * @param level        级别（INFO / WARN / ERROR …）
 * @param contentType  MIME 类型
 * @param content      日志正文
 * @param tags         标签
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlLogRecord(long timestamp,
                            String serviceName,
                            String instanceName,
                            String endpointName,
                            String traceId,
                            String level,
                            String contentType,
                            String content,
                            List<OtnlTag> tags) {
}
