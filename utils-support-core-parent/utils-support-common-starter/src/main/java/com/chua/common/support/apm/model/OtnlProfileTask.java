package com.chua.common.support.apm.model;

import java.util.List;

/**
 * 性能剖析任务（对齐 SkyWalking {@code ProfileTask}）。
 * <p>
 * 剖析是「按需采样」：先创建一个任务（指定服务、端点、持续时间、触发阈值），
 * agent 在命中阈值的请求上抓线程栈，采完再由 OAP 做树形聚合。
 * 因此任务对象同时承载「采样配置」与「已采样实例」，前端列表要能看出
 * 「这个任务开了多久、几个实例响应了」。
 * </p>
 *
 * @param id                   任务 ID
 * @param serviceId            服务 ID
 * @param serviceName          服务名
 * @param endpointName         被剖析的端点名
 * @param startTime            任务开始毫秒戳
 * @param durationMinutes      持续时间（分钟）
 * @param minDurationThreshold 触发剖析的最小耗时（毫秒），慢于它才采
 * @param dumpPeriod           线程栈转储间隔（毫秒），-1 表示每方法调用都转储
 * @param maxSamplingCount     最多采样次数
 * @param instanceCount        已响应实例数
 * @param instanceNames        已响应实例名
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlProfileTask(String id,
                              String serviceId,
                              String serviceName,
                              String endpointName,
                              long startTime,
                              int durationMinutes,
                              int minDurationThreshold,
                              int dumpPeriod,
                              int maxSamplingCount,
                              int instanceCount,
                              List<String> instanceNames) {

    /**
     * 任务是否仍在进行中
     *
     * @return 当前时间早于「开始 + 持续时长」时返回 true
     */
    public boolean running() {
        if (startTime <= 0L || durationMinutes <= 0) {
            return false;
        }
        return System.currentTimeMillis() < startTime + durationMinutes * 60_000L;
    }
}
