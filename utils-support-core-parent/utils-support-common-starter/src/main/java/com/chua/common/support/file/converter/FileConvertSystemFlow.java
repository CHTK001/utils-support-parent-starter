package com.chua.common.support.file.converter;

import com.chua.common.support.spi.ServiceProvider;

import java.io.*;

/**
* 文件转换流式 API — {@code FileConvertSystemFlow.of(source, target).convert()}。
* <p>使用示例：</p>
* <pre>{@code
* FileConvertSystemFlow.of("input.xlsx", "output.pdf")
*     .convert();
*
* FileConvertSystemFlow.of(new File("input.png"), new File("output.jpg"))
*     .convert();
* }</pre>
*
* @author CH
* @since 2026-07-16
 */
public class FileConvertSystemFlow {

    /**
    * 数据源
     */
    private final Object source;

    /**
    * 目标
     */
    private final Object target;

    /**
    * 创建 FileConvertSystemFlow 实例
    * @param source source
    * @param Object Object
     */
    private FileConvertSystemFlow(Object source, Object target) {
        this.source = source;
        this.target = target;
    }

    /**
    * 创建转换流
    *
    * @param source 源文件路径或 File
    * @param target 目标文件路径或 File
    * @return 当前流
     */
    public static FileConvertSystemFlow of(Object source, Object target) {
        return new FileConvertSystemFlow(source, target);
    }

    /**
    * 执行转换（自动推断格式，查找对应的 ConvertFileSystem SPI）
    *
    * @throws Exception 转换失败
     */
    public void convert() throws Exception {
        File sourceFile = toFile(source);
        File targetFile = toFile(target);
        String sourceExt = extension(sourceFile);
        String targetExt = extension(targetFile);
        String spiName = sourceExt.toLowerCase() + "2" + targetExt.toLowerCase();

        ConvertFileSystem converter = ServiceProvider.of(ConvertFileSystem.class)
                .getExtension(spiName);
        if (converter == null) {
            throw new UnsupportedOperationException(
                    "未找到转换器: " + spiName + "（" + sourceExt + " → " + targetExt + "）");
        }
        converter.convert(sourceFile, targetFile);
    }

    /**
    * 使用指定 SPI 名称的转换器执行转换
    *
    * @param spiName SPI 名称，如 "png2jpg"
    * @throws Exception 转换失败
     */
    public void convert(String spiName) throws Exception {
        ConvertFileSystem converter = ServiceProvider.of(ConvertFileSystem.class)
                .getExtension(spiName);
        if (converter == null) {
            throw new UnsupportedOperationException("未找到转换器: " + spiName);
        }
        converter.convert(toFile(source), toFile(target));
    }

    /**
    * 将入参统一转为 File 对象
    *
    * @param obj 支持 String 路径或 File 对象
    * @return 转换后的 File
    * @throws IllegalArgumentException 参数类型不支持时抛出
     */
    private static File toFile(Object obj) {
        if (obj instanceof File) {
            return (File) obj;
        }
        if (obj instanceof String) {
            return new File((String) obj);
        }
        throw new IllegalArgumentException("仅支持 String 路径或 File 对象: " + obj);
    }

    /**
    * 提取文件后缀名
    *
    * @param file 目标文件
    * @return 后缀名（不含点），无后缀时返回空字符串
     */
    private static String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot + 1);
        }
        return "";
    }
}
