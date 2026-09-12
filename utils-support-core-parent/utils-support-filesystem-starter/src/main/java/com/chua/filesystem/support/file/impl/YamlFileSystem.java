package com.chua.filesystem.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;

/**
 * YAML（.yml / .yaml）文件系统 SPI 实现。
 *
 * <p>基于 snakeyaml 库实现 YAML 格式的序列化与反序列化，
 * 支持将 YAML 文件读取为 {@code Map<String, Object>} 或将对象写入 YAML 文件。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * FileSystem fs = FileSystem.create("yaml");
 *
 * // 读取
 * Map<String, Object> data = fs.read(new File("config.yml")).toMap();
 *
 * // 写入
 * YamlWriteBuilder wb = (YamlWriteBuilder) fs.write(new File("output.yml"));
 * wb.write(Map.of("name", "张三", "age", 25));
 * }</pre> wb.write(Map.of("name", "张三", "age", 25));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"yaml", "yml"})
public class YamlFileSystem implements FileSystem {

    @Override
    /** 获取类型 */
    public String getType() {
        
        return "yaml";
    
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        
        return new YamlReadBuilder(file);
    
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        
        return new YamlWriteBuilder(file);
    
    }
}

