package com.chua.filesystem.support.converter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.base.converter.StringToConfigConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;


/**
 * TOML字符串转配置
 *
 * @author CH
 * @since 4.0.0.42
 */
@SuppressWarnings("ALL")
@Spi({"toml"})
@Slf4j
public class TomlStringToConfigConverter implements StringToConfigConverter {

    @Override
    /**
     * 转换
    */
    public Map<String, Object> convert(String value) {
        try {
 // 使用 Jackson 的 toml工厂 解析 TOML
            ObjectMapper mapper = new ObjectMapper(new TomlFactory());
            return mapper.readValue(value, Map.class);
        } catch (Exception e) {
            log.error("[filesystem-converter] TOML 配置文件解析失败", e);
            throw new RuntimeException("TOML 配置文件解析失败: " + e.getMessage(), e);
        }
    }
}

