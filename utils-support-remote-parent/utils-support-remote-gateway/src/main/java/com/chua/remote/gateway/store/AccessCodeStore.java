package com.chua.remote.gateway.store;

import com.chua.remote.gateway.AccessCodeInfo;

import java.util.List;

/**
 * 接入码存储 SPI。
 *
 * <p>网关（纯 JDK）不直接依赖数据库——存储方案由 SPI 实现提供：</p>
 * <ul>
 *   <li>默认：内存实现（{@code InMemoryAccessCodeStore}——网关独立运行时的兜底）</li>
 *   <li>Spring 生态：spring-support-parent-starter 提供 MyBatis 实现（持久化到数据库）</li>
 * </ul>
 * 通过 {@code META-INF/services/com.chua.remote.gateway.store.AccessCodeStore} 注册。
 *
 * @author AtomCode
 */
public interface AccessCodeStore {

    /**
     * 查询全部接入码（含接入统计）。
     *
     * @return 接入码列表
     */
    List<AccessCodeInfo> findAll();

    /**
     * 按接入码查询。
     *
     * @param code 接入码
     * @return 接入码信息（不存在返回 null）
     */
    AccessCodeInfo findByCode(String code);

    /**
     * 新增或更新接入码（含过期时间/上限/状态）。
     *
     * @param info 接入码信息
     */
    void save(AccessCodeInfo info);

    /**
     * 删除接入码。
     *
     * @param code 接入码
     */
    void delete(String code);

    /**
     * 记录接入统计（agent 接入——按 agentId 去重）。
     *
     * @param code 接入码
     * @param stat 接入统计项（agentId/ip/time）
     */
    void recordAgent(String code, AccessCodeInfo.AgentAccessStat stat);

    /**
     * 移除接入统计。
     *
     * @param code    接入码
     * @param agentId 被控端 id
     */
    void removeAgent(String code, String agentId);
}
