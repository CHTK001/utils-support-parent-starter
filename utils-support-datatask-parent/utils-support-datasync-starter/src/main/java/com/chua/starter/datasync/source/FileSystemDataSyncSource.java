package com.chua.starter.datasync.source;

import com.chua.datasync.agent.support.DataSyncAgentSource;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 文件系统数据同步 源，从本地文件读取数据。
*
* <p>支持 JSON、YAML 格式文件，每行/每项解析为一个 Map 记录。
*
* @author CH
* @since 4.0.0.42
 */
public class FileSystemDataSyncSource implements DataSyncAgentSource {

    /** 数据源标识 */
    private final String sourceId;
    /** 输入标识 */
    private final String inputId;
    /** 文件路径 */
    private final String filePath;

    /**
    * 创建 文件系统数据同步源 实例
    *
    * @param sourceId 源标识
    * @param inputId  输入标识
    * @param filePath 文件路径
     */
    public FileSystemDataSyncSource(String sourceId, String inputId, String filePath) {
        this.sourceId = sourceId;
        this.inputId = inputId;
        this.filePath = filePath;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String inputId() {
        return inputId;
    }

    @Override
    public Flux<Map<String, Object>> read(Map<String, Object> params) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return Flux.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String fileName = path.getFileName().toString().toLowerCase();
            List<Map<String, Object>> rows = new ArrayList<>();

            if (fileName.endsWith(".json")) {
                rows.addAll(parseJson(content));
            } else if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
                rows.addAll(parseYaml(content));
            } else {
                rows.add(Map.of("raw", content));
            }
            return Flux.fromIterable(rows);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + filePath, e);
        }
    }

    /**
    * 解析 JSON 内容，支持数组或单对象。
    * @param content 内容
    * @return 解析json的结果
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJson(String content) {
        List<Map<String, Object>> rows = new ArrayList<>();
        content = content.trim();
        if (content.startsWith("[")) {
            Object obj;
            try {
                obj = new com.fasterxml.jackson.databind.ObjectMapper().readValue(content, Object.class);
            } catch (Exception e) {
                return rows;
            }
            if (obj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        map.forEach((k, v) -> row.put(String.valueOf(k), v));
                        rows.add(row);
                    }
                }
            }
        } else if (content.startsWith("{")) {
            Object obj;
            try {
                obj = new com.fasterxml.jackson.databind.ObjectMapper().readValue(content, Object.class);
            } catch (Exception e) {
                return rows;
            }
            if (obj instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((k, v) -> row.put(String.valueOf(k), v));
                rows.add(row);
            }
        }
        return rows;
    }

    /**
    * 解析 YAML 内容，支持文档列表或单对象。
    * @param content 内容
    * @return 解析yaml的结果
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseYaml(String content) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Yaml yaml = new Yaml();
        Object obj = yaml.load(content);
        if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((k, v) -> row.put(String.valueOf(k), v));
                    rows.add(row);
                }
            }
        } else if (obj instanceof Map<?, ?> map) {
            Map<String, Object> row = new LinkedHashMap<>();
            map.forEach((k, v) -> row.put(String.valueOf(k), v));
            rows.add(row);
        }
        return rows;
    }

    @Override
    public void close() {
    }
}
