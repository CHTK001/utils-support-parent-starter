package com.chua.git.support.model;

/**
 * Git 标签信息记录。
 *
 * <p>由 {@link com.chua.git.support.operation.TagOperation} 产出。</p>
 *
 * @param name       标签名称
 * @param sha        标签指向的提交 SHA
 * @param message    标签消息（轻量标签无消息则为空串）
 * @param annotated  是否为签名/附注标签
 *
 * @author CH
 * @since 4.0.0.42
 */
public record TagInfo(String name, String sha, String message, boolean annotated) {
}
