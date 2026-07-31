package com.chua.starter.datasync.scanner;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.config.DirectoryConfigDefinition;
import com.chua.starter.datasync.config.FileConfigDefinition;
import com.chua.starter.datasync.config.TextConfigDefinition;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Properties 配置文件解析器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PropertiesConfigFileParser implements ConfigFileParser {

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".properties");
    }

    @Override
    public DataSyncConfigDefinition parse(Path file) throws Exception {
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(file)) {
            props.load(is);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            map.put(key, props.getProperty(key));
        }
        return new YamlConfigFileParser().mapToConfig(map);
    }
}
