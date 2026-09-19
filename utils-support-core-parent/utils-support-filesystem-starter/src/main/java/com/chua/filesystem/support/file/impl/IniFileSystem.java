package com.chua.filesystem.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;

/**
 * INI 文件系统 SPI 实现。
 *
 * <p>基于 {@link com.chua.filesystem.support.ini.IniParser IniParser} 工具类实现
 * INI 格式的读取与写入，支持 Section / 键-值 结构。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * FileSystem fs = FileSystem.create("ini");
 *
 * // 读取所有 Section
 * Map<String, Map<String, String>> data = fs.read(new File("config.ini")).toMap();
 *
 * // 写入
 * fs.write(new File("output.ini")).write(Map.of("server", Map.of("port", "8080")));
 * }</pre>("server", Map.of("port", "8080")));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ini")
public class IniFileSystem implements FileSystem {

    @Override
    /**
     * 获取类型
    */
    public String getType() {
        return "ini";
    }

    @Override
    /**
     * 读取
    */
    public ReadBuilder read(File file) {
        return new IniReadBuilder(file);
    }

    @Override
    /**
     * 写入
    */
    public WriteBuilder write(File file) {
        return new IniWriteBuilder(file);
    }
}
