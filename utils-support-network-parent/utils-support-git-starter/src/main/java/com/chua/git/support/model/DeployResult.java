package com.chua.git.support.model;

/**
* 仓库部署执行结果。
*
* <p>封装一次 "git pull → mvn compile → deploy" 完整流水线的结果。</p>
*
* @param success    是否整体成功
* @param message    结果描述
* @param artifacts  编译产物路径列表
* @param durationMs 总耗时（毫秒）
*
* @author CH
* @since 4.0.0.42
 */
public record DeployResult(
        boolean success,
        String message,
        java.util.List<String> artifacts,
        long durationMs
) {

    /**
    * 创建成功结果。
    * @param message 消息
    * @param artifacts artifacts
    * @param durationMs 持续时间ms
    * @return 成功的结果
     */
    public static DeployResult success(String message, java.util.List<String> artifacts, long durationMs) {
        return new DeployResult(true, message, artifacts, durationMs);
    }

    /**
    * 创建失败结果（耗时默认 0）。
    * @param message 消息
    * @return 失败的结果
     */
    public static DeployResult failure(String message) {
        return new DeployResult(false, message, java.util.List.of(), 0L);
    }

    /**
    * 创建失败结果（带耗时）。
    * @param message 消息
    * @param durationMs 持续时间ms
    * @return 失败的结果
     */
    public static DeployResult failure(String message, long durationMs) {
        return new DeployResult(false, message, java.util.List.of(), durationMs);
    }
}