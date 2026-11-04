package com.chua.common.support.apm.model;

import com.chua.common.support.apm.OtnlTag;

import java.util.List;

/**
 * 告警。
 *
 * @param id        告警 ID
 * @param name      告警规则名
 * @param message   告警内容
 * @param startTime 触发毫秒戳
 * @param scope     作用域（Service / Endpoint / ServiceInstance …）
 * @param serviceName 相关服务（可空）
 * @param ruleName  规则名
 * @param events    事件明细（状态变化轨迹）
 * @param tags      标签
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlAlarm(String id,
                        String name,
                        String message,
                        long startTime,
                        String scope,
                        String serviceName,
                        String ruleName,
                        List<String> events,
                        List<OtnlTag> tags) {
}
