package com.chua.captcha.support;

import java.util.Optional;

/**
 * 验证码任务持久化存储接口
 * <p>
 * 提供验证码解析任务的缓存能力，避免重复请求。
   * 内置文件存储和 sqlite 两种实现，通过 任务id 进行
 * 任务的保存、查询和删除。
 * </p>
 *
 * @author CH
 * @since 2026-03-16
 */
public interface TaskPersistence {

    /**
     * 保存任务结果
     *
     * @param taskId 任务 标识
     * @param response 验证码解析结果
     */
    void save(String taskId, CaptchaResponse response);

    /**
      * 根据任务 标识 查询缓存的结果
     *
     * @param taskId 任务 标识
     * @return 缓存的结果，不存在时返回 期权.空()
     */
    Optional<CaptchaResponse> query(String taskId);

    /**
     * 删除指定任务
     *
     * @param taskId 任务 标识
     */
    void delete(String taskId);

    /**
     * 创建基于文件存储的持久化实现
     *
     * @param filePath 持久化文件路径
     * @return TaskPersistence 实例
     */
    static TaskPersistence file(String filePath) {
        return new FileTaskPersistence(filePath);
    }

}
