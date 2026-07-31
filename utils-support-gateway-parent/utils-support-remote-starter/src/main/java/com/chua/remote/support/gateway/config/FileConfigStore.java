package com.chua.remote.support.gateway.config;

import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.remote.support.spi.GatewayConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于文件（JSON）的配置持久化实现
 *
 * <p>{@code SpiDefault} 回退实现，使用JSON 文件存储配置。
 * 默认路径 {@code ~/.remote-gateway/config.json}。
 *
 * @since 4.0.0.41

 * @author CH
 */@Slf4j
@SpiDefault
public class FileConfigStore implements GatewayConfigStore {

    /** JSON 配置文件路径（默。 ~/.remote-gateway/config.json。*/
    private final Path configPath;
    /** Jackson JSON 解析。*/
    private final ObjectMapper mapper = new ObjectMapper();

    public FileConfigStore() {
        this(Path.of(System.getProperty("user.home"), ".remote-gateway", "config.json"));
    }

    public FileConfigStore(Path configPath) {
        this.configPath = configPath;
    }

    @Override
    public Map<String, Object> load() {
        if (!Files.exists(configPath)) {
            log.debug("[FileConfigStore] 配置文件不存。 {}", configPath);
            return new LinkedHashMap<>();
        }
        try {
            String json = Files.readString(configPath);
            @SuppressWarnings("unchecked")
            Map<String, Object> config = mapper.readValue(json, Map.class);
            return config;
        }
 catch (IOException e) {
            log.warn("[FileConfigStore] 读取配置文件失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @Override
    public void save(Map<String, Object> config) {
        try {
            Files.createDirectories(configPath.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configPath, json);
            log.debug("[FileConfigStore] 配置已保。 {} ({} 。", configPath, config.size());
        }
 catch (IOException e) {
            log.warn("[FileConfigStore] 保存配置失败: {}", e.getMessage());
        }
    }
}
