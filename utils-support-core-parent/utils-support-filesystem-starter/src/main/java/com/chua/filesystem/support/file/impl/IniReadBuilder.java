package com.chua.filesystem.support.file.impl;

import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.filesystem.support.ini.IniParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
* INI 文件读取构建器。
*
* <p>支持将 INI 文件读取为：</p>
* <ul>
*   <li>{@link #toMap()} — 嵌套 Map（{@code Section -> {Key -> Value}}）</li>
*   <li>{@link #rows()} — 表格格式列表（每条记录为一个 Section 的属性）</li>
*   <li>{@link #sections()} — Section 名称集合</li>
*   <li>{@link #asString()} — 原始文件内容字符串</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public class IniReadBuilder extends ReadBuilder {

    /**
    * 创建 ini读取构建器 实例
    * @param file 文件
     */
    public IniReadBuilder(File file) {
        super(file);
    }

    @Override
    /** with字符集 */
    public IniReadBuilder withCharset(String charset) {
        super.withCharset(charset);
        return this;
    }

    /**
    * 读取 INI 文件原始内容字符串。
    *
    * @return 文件内容
     */
    @Override
    public String asString() {
        try {
            return new String(Files.readAllBytes(file.toPath()), charset);
        } catch (IOException e) {
            return "";
        }
    }

    /**
    * 读取 INI 文件为嵌套 映射 结构（Section → {键 → 值}）。
    *
    * @return Section 名称到属性 映射 的映射
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        String content = asString();
        if (content.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, String>> nested = IniParser.parseToNestedMap(content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(nested);
        return result;
    }

    /**
    * 以表格形式读取 INI 文件。
    * <p>每个 Section 展开为一行 Map，包含 {@code __section__} 字段标识 Section 名。</p>
    *
    * @return 行数据列表（每行一个 Section 的属性 + __section__）
     */
    public List<Map<String, Object>> rows() {
        String content = asString();
        if (content.isEmpty()) {
            return List.of();
        }

        List<Map<String, String>> list = IniParser.parseToList(content);
        List<Map<String, Object>> result = new ArrayList<>(list.size());

        for (Map<String, String> stringMap : list) {
            Map<String, Object> row = new LinkedHashMap<>(stringMap);
            result.add(row);
        }

        // 应用基类行过滤 + 行数据转换
        result = applyFilter(result);
        result = applyRowMapping(result);

        if (callback != null) {
            callback.onComplete(result.size());
        }
        return result;
    }

    /**
    * 获取 INI 文件中的所有 Section 名称。
    *
    * @return Section 名称集合
     */
    public Set<String> sections() {
        String content = asString();
        if (content.isEmpty()) {
            return Set.of();
        }
        return IniParser.getSections(content);
    }

    /**
    * 获取指定 Section 的所有属性。
    *
    * @param sectionName Section 名称
    * @return 属性 映射，Section 不存在时返回空 映射
     */
    public Map<String, String> section(String sectionName) {
        Map<String, Map<String, String>> nested = IniParser.parseToNestedMap(asString());
        return nested.getOrDefault(sectionName, Map.of());
    }

    @Override
    /** 读取 */
    public Object read() {
        return rows();
    }
}
