package com.chua.starter.datasync.app;

import com.chua.starter.datasync.mapping.DefaultDataSyncMappingManager;
import com.chua.starter.datasync.scanner.DirectoryDataSyncMappingScanner;
import com.chua.starter.datasync.scanner.JsonConfigFileParser;
import com.chua.starter.datasync.scanner.PropertiesConfigFileParser;
import com.chua.starter.datasync.scanner.YamlConfigFileParser;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * DataSync 核心模块真实启动测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DataSyncApp {

    public static void main(String[] args) throws Exception {
        Path mappingDir = Paths.get("src/test/resources/test-mappings");
        System.out.println("[APP] 映射目录=" + mappingDir.toAbsolutePath());

        DefaultDataSyncMappingManager mappingManager = new DefaultDataSyncMappingManager();

        DirectoryDataSyncMappingScanner scanner = new DirectoryDataSyncMappingScanner(
                mappingManager,
                new com.chua.starter.datasync.config.DirectoryConfigDefinition() {
                    @Override
                    public String directoryPath() { return mappingDir.toString(); }
                    @Override public String inputId() { return null; }
                    @Override public String sourceId() { return null; }
                    @Override public String outputId() { return null; }
                    @Override public String sinkId() { return null; }
                    @Override public java.util.List<com.chua.starter.datasync.mapping.DataSyncFieldMapping> mappings() { return java.util.List.of(); }
                    @Override public int batch() { return 100; }
                    @Override public String cronType() { return null; }
                    @Override public String cron() { return null; }
                    @Override public java.util.Map<String, Object> params() { return java.util.Map.of(); }
                },
                java.util.List.of(
                        new YamlConfigFileParser(),
                        new JsonConfigFileParser(),
                        new PropertiesConfigFileParser()
                )
        );

        scanner.start();
        Thread.sleep(1000);

        System.out.println("[APP] 扫描完成，映射数=" + mappingManager.getMappings().size());
        for (var mapping : mappingManager.getMappings()) {
            System.out.println("[APP] 映射: mappingId=" + mapping.mappingId()
                    + ", sourceId=" + mapping.sourceId()
                    + ", sinkId=" + mapping.sinkId()
                    + ", batch=" + mapping.batch()
                    + ", fieldMappings=" + mapping.mappings());
        }

        scanner.stop();
        System.out.println("[APP] 测试完成");
    }
}
