package com.chua.common.support.vector;

import java.util.List;
import java.util.Objects;

/**
 * 向量存储实现描述。
 *
 * <p>由 {@link VectorStorageProvider#descriptor()} 提供，回答两个问题：</p>
 * <ol>
 *   <li><b>怎么渲染</b> —— {@link #fields()} 列出该实现需要哪些配置项、每项什么类型、是否必填、
 *       哪些是密码；前端据此动态生成表单，无需为每种向量库硬编码一套界面。</li>
 *   <li><b>怎么初始化</b> —— {@link #requiresDataSource()} 标识该实现是否需要宿主注入
 *       {@code DataSource}（Spring 资源，前端填不出来，也不该让用户填）；
 *       其余实现所需的键值配置由 {@link VectorStorageProvider#toProperties(java.util.Map)} 转换。</li>
 * </ol>
 *
 * <p>{@link #fields()} 永不为 null：无需配置的实现返回空列表，这样前端可以直接遍历，
 * 不用判空。</p>
 *
 * @param name                SPI 名称
 * @param displayName         前端显示名
 * @param description         实现说明，可为 null
 * @param fields              配置项列表，不可为 null
 * @param requiresDataSource  是否需要宿主注入 DataSource
 * @author CH
 * @since 4.0.0.42
 */
public record VectorStorageDescriptor(
        String name,
        String displayName,
        String description,
        List<VectorStorageField> fields,
        boolean requiresDataSource
) {

    /**
     * 规范构造器：校验必填组件，并对配置项列表做防御性拷贝。
     *
     * @throws NullPointerException name / displayName / fields 为 null 时
     */
    public VectorStorageDescriptor {
        Objects.requireNonNull(name, "name 不能为 null");
        Objects.requireNonNull(displayName, "displayName 不能为 null");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields 不能为 null"));
    }

    /**
     * 便捷构造：无需配置的向量存储实现。
     *
     * @param name        SPI 名称
     * @param displayName 前端显示名
     * @param description 实现说明
     * @return 实现描述
     */
    public static VectorStorageDescriptor simple(String name, String displayName, String description) {
        return new VectorStorageDescriptor(name, displayName, description, List.of(), false);
    }
}
