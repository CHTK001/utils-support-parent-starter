package com.chua.aspose.support.converter;

import com.aspose.words.Document;
import com.aspose.words.SaveFormat;
import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Aspose.Words 文档格式转换器。
 *
 * <p>支持 Word 全系列格式（doc/docx/dot/dotx/docm/dotm/wps）的相互转换，
   * 以及转换为 pdf/HTML/txt/rtf/epub/odt/xps 等格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("aspose-word")
public class AsposeWordFileConvertSystem implements FileConvertSystem {

    /**
     * 支持的源文件格式列表
     */
    private static final List<String> SOURCES = List.of("doc", "docx", "dot", "dotx", "docm", "dotm", "wps");

    /**
     * 支持的目标文件格式列表
     */
    private static final List<String> TARGETS = List.of("pdf", "doc", "docx", "dot", "dotx", "html", "htm", "txt", "rtf", "epub", "odt", "xps");

    /**
      * 格式与 Aspose.Words 保存格式化 常量映射表
     */
    private static final Map<String, Integer> FORMAT_MAP = Map.ofEntries(
        Map.entry("doc", SaveFormat.DOC), Map.entry("docx", SaveFormat.DOCX),
        Map.entry("dot", SaveFormat.DOT), Map.entry("dotx", SaveFormat.DOTX),
        Map.entry("pdf", SaveFormat.PDF), Map.entry("html", SaveFormat.HTML),
        Map.entry("htm", SaveFormat.HTML), Map.entry("txt", SaveFormat.TEXT),
        Map.entry("rtf", SaveFormat.RTF), Map.entry("epub", SaveFormat.EPUB),
        Map.entry("odt", SaveFormat.ODT), Map.entry("xps", SaveFormat.XPS)
    );

    @Override
    /** 是否支持 */
    public boolean isSupported(String source, String target) {
        if (!SOURCES.contains(source)) {
            return false;
        }
        return TARGETS.contains(target);
    }

    @Override
    /** 转换 */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        try (InputStream in = source.isInputStream() ? source.getInputStream() : new FileInputStream(source.getPath());
             OutputStream out = target.isOutputStream() ? target.getOutputStream() : new FileOutputStream(target.getPath())) {
            Document doc = new Document(in);
            String ext = target.isPath() ? target.getPath().replaceAll(".*\\.", "") : target.getType();
            Integer fmt = FORMAT_MAP.get(ext);
            if (fmt == null) {
                throw new UnsupportedOperationException("不支持的目标格式: " + ext);
            }
            doc.save(out, fmt);
        } catch (Exception e) {
            throw new RuntimeException("Aspose Word 转换失败", e);
        }
    }
}
