package com.chua.common.support.vector;

import java.util.List;
import java.util.Objects;

/**
 * 向量存储配置项描述。
 *
 * <p>由 {@link VectorStorageProvider#descriptor()} 声明，描述某个向量存储实现
 * 「需要用户填什么」，供前端动态渲染配置表单、后端动态构造初始化参数。</p>
 *
 * <p>取值类型 {@link #type()} 使用下列常量之一：</p>
 * <ul>
 *   <li>{@link #TYPE_STRING} — 单行文本</li>
 *   <li>{@link #TYPE_PASSWORD} — 密码，回显与日志一律脱敏</li>
 *   <li>{@link #TYPE_NUMBER} — 数字</li>
 *   <li>{@link #TYPE_BOOLEAN} — 开关</li>
 *   <li>{@link #TYPE_SELECT} — 下拉，候选取 {@link #options()}</li>
 *   <li>{@link #TYPE_PATH} — 服务器目录 / 文件路径，由后端文件系统接口选取</li>
 * </ul>
 *
 * <p>{@link #options()} 允许为 null（表示自由输入），其余组件不允许为 null。</p>
 *
 * @param key           配置键，与初始化参数映射的键一一对应
 * @param label         前端标签
 * @param type          取值类型，见本类 TYPE_* 常量
 * @param required      是否必填
 * @param defaultValue  默认值，可为 null
 * @param placeholder   输入框占位提示，可为 null
 * @param options       下拉候选，仅 {@link #TYPE_SELECT} 使用，可为 null
 * @param secret        是否敏感字段（前端不回显、后端不写日志）
 * @param description   补充说明，可为 null
 * @author CH
 * @since 4.0.0.42
 */
public record VectorStorageField(
        String key,
        String label,
        String type,
        boolean required,
        String defaultValue,
        String placeholder,
        List<String> options,
        boolean secret,
        String description
) {

    /**
     * 单行文本类型。
     */
    public static final String TYPE_STRING = "STRING";

    /**
     * 密码类型。
     */
    public static final String TYPE_PASSWORD = "PASSWORD";

    /**
     * 数字类型。
     */
    public static final String TYPE_NUMBER = "NUMBER";

    /**
     * 开关类型。
     */
    public static final String TYPE_BOOLEAN = "BOOLEAN";

    /**
     * 下拉类型。
     */
    public static final String TYPE_SELECT = "SELECT";

    /**
     * 服务器路径类型（由后端文件系统接口选取，不允许前端自由拼路径）。
     */
    public static final String TYPE_PATH = "PATH";

    /**
     * 规范构造器：校验必填组件，并对候选列表做防御性拷贝。
     *
     * @throws NullPointerException key / label / type 为 null 时
     */
    public VectorStorageField {
        Objects.requireNonNull(key, "key 不能为 null");
        Objects.requireNonNull(label, "label 不能为 null");
        Objects.requireNonNull(type, "type 不能为 null");
        // 候选列表可能是 null（自由输入），语义要保留，故不能用 List.copyOf
        options = options == null ? null : List.copyOf(options);
    }

    /**
     * 便捷构造：必填单行文本，无默认值。
     *
     * @param key        配置键
     * @param label      前端标签
     * @param description 补充说明
     * @return 配置项描述
     */
    public static VectorStorageField text(String key, String label, String description) {
        return new VectorStorageField(key, label, TYPE_STRING, true, null, null, null, false, description);
    }

    /**
     * 便捷构造：可选项，带默认值。
     *
     * @param key          配置键
     * @param label        前端标签
     * @param defaultValue 默认值
     * @param description  补充说明
     * @return 配置项描述
     */
    public static VectorStorageField optional(String key, String label, String defaultValue, String description) {
        return new VectorStorageField(key, label, TYPE_STRING, false, defaultValue, null, null, false, description);
    }

    /**
     * 便捷构造：可选项，带默认值与占位提示。
     *
     * @param key          配置键
     * @param label        前端标签
     * @param defaultValue 默认值
     * @param placeholder  占位提示
     * @param description  补充说明
     * @return 配置项描述
     */
    public static VectorStorageField optional(String key, String label, String defaultValue,
                                              String placeholder, String description) {
        return new VectorStorageField(key, label, TYPE_STRING, false, defaultValue, placeholder, null, false,
                description);
    }

    /**
     * 便捷构造：数字项。
     *
     * @param key          配置键
     * @param label        前端标签
     * @param defaultValue 默认值
     * @param required     是否必填
     * @param description  补充说明
     * @return 配置项描述
     */
    public static VectorStorageField number(String key, String label, String defaultValue,
                                            boolean required, String description) {
        return new VectorStorageField(key, label, TYPE_NUMBER, required, defaultValue, null, null, false, description);
    }

    /**
     * 便捷构造：开关项。
     *
     * @param key          配置键
     * @param label        前端标签
     * @param defaultValue 默认值（"true" / "false"）
     * @param description  补充说明
     * @return 配置项描述
     */
    public static VectorStorageField bool(String key, String label, String defaultValue, String description) {
        return new VectorStorageField(key, label, TYPE_BOOLEAN, false, defaultValue, null, null, false, description);
    }

    /**
     * 便捷构造：下拉项。
     *
     * @param key          配置键
     * @param label        前端标签
     * @param defaultValue 默认值
     * @param options      候选值
     * @param required     是否必填
     * @param description  补充说明
     * @return 配置项描述
     */
    public static VectorStorageField select(String key, String label, String defaultValue,
                                            List<String> options, boolean required, String description) {
        return new VectorStorageField(key, label, TYPE_SELECT, required, defaultValue, null,
                options, false, description);
    }

    /**
     * 便捷构造：密码项（敏感，前端不回显）。
     *
     * @param key         配置键
     * @param label       前端标签
     * @param required    是否必填
     * @param description 补充说明
     * @return 配置项描述
     */
    public static VectorStorageField password(String key, String label, boolean required, String description) {
        return new VectorStorageField(key, label, TYPE_PASSWORD, required, null, null, null, true, description);
    }

    /**
     * 便捷构造：服务器路径项（由后端文件系统接口选取）。
     *
     * @param key          配置键
     * @param label        前端标签
     * @param defaultValue 默认值
     * @param required     是否必填
     * @param description  补充说明
     * @return 配置项描述
     */
    public static VectorStorageField path(String key, String label, String defaultValue,
                                          boolean required, String description) {
        return new VectorStorageField(key, label, TYPE_PATH, required, defaultValue, null, null, false, description);
    }
}
