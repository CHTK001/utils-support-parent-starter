package com.chua.common.support.file.converter;

import com.chua.common.support.spi.ServiceProvider;

import java.io.InputStream;
import java.io.OutputStream;

/**
* 文件格式转换统一入口，提供链式调用 API。
* <p>
* 遍历所有注册的 {@link FileConvertSystem} SPI 实现，
* 根据源/目标文件类型自动匹配合适的转换器。
* </p>
* <h2>使用示例</h2>
* <pre>{@code
* ConvertSupport.convert("docx", "pdf")
*     .from("input.docx")
*     .to("output.pdf")
*     .convert();
* }</pre>
*
* @author CH
* @since 1.0.0
 */
public class ConvertSupport {

    /**
    * 源文件格式类型标识。
     */
    private final String sourceType;

    /**
    * 目标文件格式类型标识。
     */
    private final String targetType;

    /**
    * 源文件数据源。
     */
    private FileSource source;

    /**
    * 目标文件数据源。
     */
    private FileSource target;

    /**
    * 转换设置参数。
     */
    private ConvertSetting setting = new ConvertSetting();

    /**
    * 创建 ConvertSupport 实例
    * @param sourceType sourceType
    * @param String String
     */
    private ConvertSupport(String sourceType, String targetType) {
        this.sourceType = sourceType;
        this.targetType = targetType;
    }

    /**
    * 创建一个新的格式转换构建器实例。
    *
    * @param sourceType  源文件格式类型
    * @param targetType  目标文件格式类型
    * @return 返回新的转换构建器实例
     */
    public static ConvertSupport convert(String sourceType, String targetType) {
        return new ConvertSupport(sourceType, targetType);
    }

    /**
    * 指定从本地文件路径进行转换。
    *
    * @param path 源文件路径
    * @return 返回当前构建器实例以支持链式调用
     */
    public ConvertSupport from(String path) {
        this.source = FileSource.of(path);
        return this;
    }

    /**
    * 指定从输入流进行转换。
    *
    * @param is   输入流对象
    * @param type 输入流的格式类型
    * @return 返回当前构建器实例以支持链式调用
     */
    public ConvertSupport from(InputStream is, String type) {
        this.source = FileSource.of(is, type);
        return this;
    }

    /**
    * 指定自定义的文件源对象。
    *
    * @param src 源文件源对象
    * @return 返回当前构建器实例以支持链式调用
     */
    public ConvertSupport from(FileSource src) {
        this.source = src;
        return this;
    }

    /**
    * 指定转换到的本地文件路径。
    *
    * @param path 目标文件路径
    * @return 返回当前构建器实例以支持链式调用
     */
    public ConvertSupport to(String path) {
        this.target = FileSource.of(path);
        return this;
    }

    /**
    * 指定转换到的输出流。
    *
    * @param os   输出流对象
    * @param type 输出流的格式类型
    * @return 返回当前构建器实例以支持链式调用
     */
    public ConvertSupport to(OutputStream os, String type) {
        this.target = FileSource.of(os, type);
        return this;
    }

    /**
    * 指定自定义的目标文件源对象。
    *
    * @param tgt 目标文件源对象
    * @return 返回当前构建器实例以支持链式调用
     */
    public ConvertSupport to(FileSource tgt) {
        this.target = tgt;
        return this;
    }

    /**
    * 设置转换过程中的具体配置参数。
    *
    * @param s 转换设置对象
    * @return 返回当前构建器实例以支持链式调用
     */
    public ConvertSupport setting(ConvertSetting s) {
        this.setting = s;
        return this;
    }

    /**
    * 执行实际的格式转换操作。
    * <p>
    * 该方法会遍历所有已注册的 {@link FileConvertSystem} SPI 实现，
    * 查找第一个能够处理从源类型到目标类型转换的转换器。
    * 如果找到匹配的转换器，则执行转换；否则抛出异常。
    * </p>
    *
    * @throws UnsupportedOperationException 当没有找到支持的转换器时抛出此异常
     */
    public void convert() {
        ServiceProvider<FileConvertSystem> sp = ServiceProvider.of(FileConvertSystem.class);
        for (FileConvertSystem converter : sp.getNewExtensions(null)) {
            if (converter.isSupported(sourceType, targetType)) {
                converter.convert(source, target, setting);
                return;
            }
        }
        throw new UnsupportedOperationException("No converter for " + sourceType + " -> " + targetType);
    }
}
