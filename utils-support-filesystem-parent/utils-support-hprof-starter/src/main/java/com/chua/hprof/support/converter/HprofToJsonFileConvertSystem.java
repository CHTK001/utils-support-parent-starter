package com.chua.hprof.support.converter;

import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.hprof.support.parser.HprofParser;
import com.chua.hprof.support.serializer.HprofToJsonSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HPROF 转 JSON 文件转换器。
 *
 * <p>解析二进制 hprof 堆转储，输出包含类直方图、保留量最大的对象以及
 * 内存泄漏嫌疑对象的 JSON 文档。该 JSON 布局稳定且机器可读，
 * 便于下游 AI Agent 直接摄取。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("hprof2json")
public class HprofToJsonFileConvertSystem implements FileConvertSystem {

    /**
    * 源文件格式标识。
    */
    private static final String SOURCE_TYPE = "hprof";

    /**
    * 目标文件格式标识。
    */
    private static final String TARGET_TYPE = "json";

    @Override
    /** 是否支持该源/目标格式组合 */
    public boolean isSupported(String sourceType, String targetType) {
        return SOURCE_TYPE.equalsIgnoreCase(sourceType)
                && TARGET_TYPE.equalsIgnoreCase(targetType);
    }

    @Override
    /** 执行转换 */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        try {
            HprofParser.Result result = HprofParser.parse(toInputStream(source), source.getPath());
            String json = HprofToJsonSerializer.serialize(result, source.getPath());
            write(target, json, setting);
            log.info("hprof to json converted: {} -> {}", source.getPath(), target.getPath());
        } catch (Exception e) {
            throw new RuntimeException("HPROF to JSON conversion failed", e);
        }
    }

    /**
    * 将源解析为输入流。
    *
    * @param source 源文件描述
    * @return 输入流
    * @throws IOException 源不可读时抛出
    */
    private static java.io.InputStream toInputStream(FileSource source) throws IOException {
        if (source.isPath()) {
            return Files.newInputStream(Path.of(source.getPath()));
        }
        if (source.isUrl()) {
            return source.getUrl().openStream();
        }
        if (source.isInputStream()) {
            return source.getInputStream();
        }
        throw new IOException("unsupported hprof source: " + source);
    }

    /**
    * 把生成的内容写入目标。
    *
    * @param target  目标文件描述
    * @param content 内容字节
    * @param setting 转换设置
    * @throws IOException 目标不可写时抛出
    */
    private static void write(FileSource target, String content, ConvertSetting setting) throws IOException {
        if (target.isPath()) {
            File file = new File(target.getPath());
            if (!setting.isOverwrite() && file.exists()) {
                throw new IOException("target exists and overwrite disabled: " + file);
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("failed to create target directory: {}", parent);
            }
            Files.writeString(file.toPath(), content);
            return;
        }
        if (target.isOutputStream()) {
            target.getOutputStream().write(content.getBytes());
            return;
        }
        throw new IOException("unsupported hprof target: " + target);
    }
}
