package com.chua.filesystem.support.file.impl;

import com.chua.common.support.file.builder.WriteBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.*;

/**
 * YAML 文件写入构建器。
 *
 * <p>将 Java 对象序列化为 YAML 格式并写入文件，
 * 支持指定字符集编码。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class YamlWriteBuilder extends WriteBuilder {

    /**
     * 创建 yaml写入构建器 实例
     * @param file 文件
     */
    public YamlWriteBuilder(File file) {
        super(file);
    }

    @Override
    /**
     * with字符集
    */
    public YamlWriteBuilder withCharset(String charset) {
        super.withCharset(charset);
        return this;
    }

    /**
     * 将指定对象写入 YAML 文件。
     *
     * @param data 待写入的对象，可以是 映射、列表 或普通 Java Bean
     */
    @Override
    public YamlWriteBuilder write(Object data) {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), charset)) {
            new Yaml().dump(data, writer);
        } catch (IOException e) {
            throw new UncheckedIOException("YAML 写入失败", e);
        }
        return this;
    }
}
