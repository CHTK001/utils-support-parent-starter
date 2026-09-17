package com.chua.common.support.config.loader;

import com.chua.common.support.utils.StringUtils;

import java.nio.charset.Charset;
import java.util.Objects;

/**
* 配置保存/加载器抽象基类。
*
* <p>提供配置持久化的通用骨架实现，包括 key 规范化、路径拼接、
* 成功/失败结果构建等基础能力。子类需实现具体的存储后端逻辑。</p>
*
* @author CH
* @since 2024/12/20
 */
public abstract class AbstractConfigSaveOrLoader implements ConfigSaveOrLoader {

    /** 配置对象 */
    protected final ConfigSaveLoadSetting setting;

    /**
    * 创建 AbstractConfigSaveOrLoader 实例
    * @param setting setting
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
    * 规范化配置键，将反斜杠替换为正斜杠，去除开头斜杠。
    *
    * @param key 原始配置键
    * @return 规范化后的相对路径
    */
    protected String normalizeKey(String key) {
        Objects.requireNonNull(key, "config key must not be null");
        String normalized = key.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (StringUtils.isBlank(normalized)) {
            throw new IllegalArgumentException("config key must not be blank");
        }
        return normalized;
    }

    /**
    * 构建成功结果。
    *
    * @param key      配置键
    * @param location 存储位置
    * @param size     数据大小
    * @return 成功结果
    */
    protected ConfigSaveResult success(String key, String location, long size) {
        return ConfigSaveResult.success(key, location, size);
    }

    /**
    * 构建失败结果。
    *
    * @param key     配置键
    * @param message 失败信息
    * @return 失败结果
    */
    protected ConfigSaveResult failure(String key, String message) {
        return ConfigSaveResult.failure(key, message);
    }

    /**
    * 拼接路径片段。
    *
    * @param parts 路径片段
    * @return 拼接后的路径
    */
    protected String joinPath(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (StringUtils.isBlank(part)) {
                continue;
            }
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
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
