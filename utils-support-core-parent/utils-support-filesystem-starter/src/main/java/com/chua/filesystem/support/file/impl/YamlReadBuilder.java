package com.chua.filesystem.support.file.impl;

import com.chua.common.support.file.builder.ReadBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.Map;

/**
* YAML 文件读取构建器。
*
* <p>将 YAML 文件反序列化为 {@code Map<String, Object>} 格式，
* 支持指定字符集编码。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class YamlReadBuilder extends ReadBuilder {

    /**
    * 创建 yaml读取构建器 实例
    * @param file 文件
     */
    public YamlReadBuilder(File file) {
        super(file);
    }

    @Override
    /** with字符集 */
    public YamlReadBuilder withCharset(String charset) {
        super.withCharset(charset);
        return this;
    }

    /**
    * 读取 YAML 文件并返回 映射 格式的数据。
    *
    * @return 解析后的 映射，空文件或读取失败时返回空 映射
     */
    public Map<String, Object> toMap() {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), charset)) {
            return new Yaml().load(reader);
        } catch (IOException e) {
            return Map.of();
        }
    }
}
