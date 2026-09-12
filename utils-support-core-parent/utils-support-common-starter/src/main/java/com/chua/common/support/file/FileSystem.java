package com.chua.common.support.file;

import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.FileUtils;

import java.io.File;

/**
* 文件系统 SPI 接口，统一文件读写入口。
* <p>通过 SPI 机制根据文件类型自动选择对应的读写实现，支持 CSV、Excel、JSON、XML 等格式。</p>
* <p>每种格式在独立的 starter 模块中提供 SPI 扩展。</p>
*
* @author CH
* @since 1.0.0
 */
public interface FileSystem {

    /**
    * 根据 SPI 名称创建文件系统实例。
    *
    * @param type 文件类型 SPI 名称（csv, excel, json, xml, yaml 等）
    * @return FileSystem 实例
     */
    static FileSystem create(String type) {
        return ServiceProvider.of(FileSystem.class)
                .getExtension(type);
    }

    /**
    * 根据 SPI 名称创建指定类型的文件系统实例（带类型推断，避免转型）。
    *
    * <p>当需要使用子类特有的方法（如 {@code Zip4jFileSystem} 的密码支持）时使用此方法，
    * 可以避免从基类转型的繁琐操作。利用 Java 协变返回类型，子类的 {@code write()}/ {@code read()}
    * 返回对应的 Builder 子类。</p>
    *
    * <p>使用示例：</p>
    * <pre>{@code
    * FileSystem.create("zip4j", Zip4jFileSystem.class)
    *     .write(file)
    *     .password("secret")
    *     .addFile("a.txt", source)
    *     .finish();
    * }</pre>
    *
    * @param type     文件类型 SPI 名称
    * @param implType 期望的实现类型 Class 对象
    * @param <T>      实现类型泛型
    * @return 指定类型的 FileSystem 实例
     */
    static <T extends FileSystem> T create(String type, Class<T> implType) {
        return implType.cast(ServiceProvider.of(FileSystem.class).getExtension(type));
    }

    /**
    * 根据文件名后缀自动匹配文件系统。
    *
    * @param file 文件对象
    * @return FileSystem 实例
    * @throws UnsupportedOperationException 当文件类型不支持时抛出异常
     */
    static FileSystem auto(File file) {
        String ext = FileUtils.getExtension(file);
        if (ext.isEmpty()) {
            throw new UnsupportedOperationException("Unsupported file type: " + file.getName());
        }
        return switch (ext) {
            case "tsv" -> create("csv");
            case "json5" -> create("json");
            case "yaml", "yml" -> create("yaml");
            case "xls", "xlsx" -> create("excel");
            case "gz" -> create("archive");
            default -> create(ext);
        };
    }

    /**
    * 获取文件系统类型名称。
    *
    * @return 类型名称字符串
     */
    String getType();

    /**
    * 创建读取构建器。
    *
    * @param file 要读取的文件对象
    * @return ReadBuilder 构建器实例
     */
    ReadBuilder read(File file);

    /**
    * 创建写入构建器。
    *
    * @param file 要写入的文件对象
    * @return WriteBuilder 构建器实例
     */
    WriteBuilder write(File file);
}