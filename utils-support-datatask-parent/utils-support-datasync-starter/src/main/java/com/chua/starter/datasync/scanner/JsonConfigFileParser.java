package com.chua.starter.datasync.scanner;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.config.DirectoryConfigDefinition;
import com.chua.starter.datasync.config.FileConfigDefinition;
import com.chua.starter.datasync.config.TextConfigDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * JSON 配置文件解析器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JsonConfigFileParser implements ConfigFileParser {

    /** JSON 对象映射器 */
    /** Mapper */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".json");
    }

    @Override
    public DataSyncConfigDefinition parse(Path file) throws Exception {
        try (InputStream is = Files.newInputStream(file)) {
            Map<String, Object> map = MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
            return new YamlConfigFileParser().mapToConfig(map);
        }
    }
}
