package com.chua.common.support.apm.model;

import com.chua.common.support.apm.OtnlTag;

import java.util.List;

/**
 * 服务实例（SkyWalking ServiceInstance 层实体）。
 *
 * @param id            实例 ID
 * @param serviceName   所属服务
 * @param name          实例名（通常是 hostname@pid 或 uuid）
 * @param language      语言（JAVA / GO …）
 * @param heartbeatTime 最后心跳毫秒戳，未知填 0
 * @param attributes    实例属性（ip / os / process 等）
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlInstance(String id,
                           String serviceName,
                           String name,
                           String language,
                           long heartbeatTime,
                           List<OtnlTag> attributes) {
}
