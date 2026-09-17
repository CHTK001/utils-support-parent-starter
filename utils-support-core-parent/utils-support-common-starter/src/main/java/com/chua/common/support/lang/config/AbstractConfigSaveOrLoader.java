package com.chua.common.support.lang.config;

import com.chua.common.support.utils.StringUtils;

import java.nio.charset.Charset;
import java.util.Objects;

/**
* 配置保存或加载的抽象基类。
* 提供通用的配置键标准化、路径拼接以及结果构建逻辑。
* @author CH
* @since 4.0.0.42
 */
public abstract class AbstractConfigSaveOrLoader implements ConfigSaveOrLoader {

    /**
    * 配置保存和加载的设置对象，包含字符集等配置信息。
    */
    protected final ConfigSaveLoadSetting setting;

    /**
    * 构造函数。
    *
    * @param setting 配置设置对象，如果为 null 则使用默认设置。
    */
    protected AbstractConfigSaveOrLoader(ConfigSaveLoadSetting setting) {
        this.setting = setting == null ? ConfigSaveLoadSetting.builder().build() : setting;
    }

    @Override
    /** Charset */
    public Charset charset() {
        return setting.getCharset();
    }

    /**
    * 将配置键标准化为基于斜杠的相对路径。
    * 处理步骤：
    * 1. 将反斜杠转换为正斜杠以统一路径分隔符。
    * 2. 移除路径开头的多余斜杠。
    * 3. 验证结果是否为空，若为空则抛出异常。
    *
    * @param key 原始配置键。
    * @return 标准化后的路径字符串。
    * @throws IllegalArgumentException 当 key 为空白时抛出。
    */
    protected String normalizeKey(String key) {
        Objects.requireNonNull(key, "config key must not be null");
        // 统一路径分隔符为正斜杠
        String normalized = key.replace('\\', '/');
        // 移除前导斜杠
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (StringUtils.isBlank(normalized)) {
            throw new IllegalArgumentException("config key must not be blank");
        }
        return normalized;
    }

    /**
    * 构建成功的配置保存结果。
    *
    * @param key      配置键。
    * @param location 保存位置。
    * @param size     文件大小（字节）。
    * @return 成功的结果对象。
    */
    protected ConfigSaveResult success(String key, String location, long size) {
        return ConfigSaveResult.success(key, location, size);
    }

    /**
    * 构建失败的配置保存结果。
    *
    * @param key     配置键。
    * @param message 失败原因描述。
    * @return 失败的结果对象。
    */
    protected ConfigSaveResult failure(String key, String message) {
        return ConfigSaveResult.failure(key, message);
    }

    /**
    * 拼接多个路径片段，生成最终的规范路径。
    * 处理逻辑：
    * 1. 忽略空白的路径片段。
    * 2. 将片段中的反斜杠替换为正斜杠。
    * 3. 移除片段首尾的多余斜杠。
    * 4. 使用单个斜杠连接有效片段。
    *
    * @param parts 路径片段数组。
    * @return 拼接后的路径字符串。
    */
    protected String joinPath(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (StringUtils.isBlank(part)) {
                continue;
            }
            // 统一分隔符并清理首尾斜杠
            String value = part.replace('\\', '/');
            while (value.startsWith("/")) {
                value = value.substring(1);
            }
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            if (StringUtils.isBlank(value)) {
                continue;
            }
            // 如果不是第一个有效片段，添加分隔符
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
