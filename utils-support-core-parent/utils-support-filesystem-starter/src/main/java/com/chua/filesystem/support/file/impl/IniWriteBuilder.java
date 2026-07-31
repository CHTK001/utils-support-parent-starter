package com.chua.filesystem.support.file.impl;

import com.chua.common.support.file.builder.WriteBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.*;

/**
 * INI 文件写入构建器。
 *
 * <p>支持将 {@code Map<String, Map<String, String>>}（嵌套 Section 结构）
 * 或 {@code List<Map<String, String>>}（含 __section__ 字段的表格格式）
 * 写入为 INI 格式文件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class IniWriteBuilder extends WriteBuilder {

    public IniWriteBuilder(File file) {
        super(file);
    }

    @Override
    public IniWriteBuilder withCharset(String charset) {
        super.withCharset(charset);
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public IniWriteBuilder write(Object data) {
        pending.add(data);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void finish() {
        callback.onStart();
        callback.onBeginWrite();

        StringBuilder sb = new StringBuilder();

        for (Object entry : pending) {
            if (entry instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) entry;
                // 判断是嵌套 Section 结构还是单层属性
                if (isNestedSectionMap(map)) {
                    writeNestedMap(sb, (Map<String, Map<String, String>>) (Map<?, ?>) map);
                } else {
                    // 单层属性 → 写入默认 Section
                    writeFlatMap(sb, map);
                }
            } else if (entry instanceof List) {
                // List<Map> 表格格式（含 __section__）
                List<Map<String, Object>> list = (List<Map<String, Object>>) entry;
                writeRowList(sb, list);
            }
        }

        String ini = sb.toString();
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), charset)) {
            writer.write(ini);
        } catch (IOException e) {
            callback.onComplete(false);
            throw new RuntimeException("INI 写入失败: " + file, e);
        }

        callback.onProgress(ini.getBytes(charset).length, ini.getBytes(charset).length);
        callback.onComplete(true);
    }

    /** 判断 Map 是否为嵌套 Section 结构（value 也是 Map） */
    private boolean isNestedSectionMap(Map<String, Object> map) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (e.getValue() instanceof Map) {
                return true;
            }
        }
        return false;
    }

    /** 写入嵌套 Section 结构（每 Section 做一次行过滤） */
    private void writeNestedMap(StringBuilder sb, Map<String, Map<String, String>> sections) {
        for (Map.Entry<String, Map<String, String>> section : sections.entrySet()) {
            String sectionName = section.getKey();
            // 将 Section 所有属性转为一行，供 testRow 过滤
            Map<String, Object> sectionRow = new LinkedHashMap<>(section.getValue());
            if (!testRow(sectionRow)) continue;

            if (sectionName != null && !sectionName.isEmpty()) {
                sb.append("[").append(sectionName).append("]").append("\n");
            }
            for (Map.Entry<String, String> prop : section.getValue().entrySet()) {
                sb.append(prop.getKey()).append("=").append(prop.getValue()).append("\n");
            }
            sb.append("\n");
        }
    }

    /** 写入单层属性（无 Section），每行独立过滤 */
    private void writeFlatMap(StringBuilder sb, Map<String, Object> data) {
        if (!testRow(data)) return;
        for (Map.Entry<String, Object> e : data.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
        }
        sb.append("\n");
    }



    /** 写入行列表格式（含 __section__） */
    private void writeRowList(StringBuilder sb, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            if (!testRow(row)) continue;

            String sectionName = row.containsKey("__section__")
                    ? String.valueOf(row.get("__section__")) : null;

            if (sectionName != null && !sectionName.isEmpty()) {
                sb.append("[").append(sectionName).append("]").append("\n");
            }
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if ("__section__".equals(e.getKey())) continue;
                sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
            }
            sb.append("\n");
        }
    }
}
