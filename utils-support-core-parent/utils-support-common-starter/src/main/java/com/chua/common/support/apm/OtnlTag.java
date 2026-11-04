package com.chua.common.support.apm;

/**
 * 键值对标签。
 * <p>Span 标签、日志标签、告警标签、实例属性统一复用这一个结构，
 * 避免每种实体各造一个 Map 造成序列化形状漂移。</p>
 *
 * @param key   键
 * @param value 值
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlTag(String key, String value) {

    /**
     * 创建标签
     *
     * @param key   键
     * @param value 值
     * @return 标签
     */
    public static OtnlTag of(String key, String value) {
        return new OtnlTag(key, value);
    }
}
