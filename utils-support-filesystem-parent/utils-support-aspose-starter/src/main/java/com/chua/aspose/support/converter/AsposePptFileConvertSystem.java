package com.chua.aspose.support.converter;

import com.aspose.slides.Presentation;
import com.aspose.slides.SaveFormat;
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
 * Aspose.Slides 演示文稿格式转换器。
 *
 * <p>支持 ppt/pptx/pptm/potx/potm 的相互转换，以及转换为 pdf/html/tiff/svg/xps
 * 和常见图片格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("aspose-ppt")
public class AsposePptFileConvertSystem implements FileConvertSystem {

    /**
     * 支持的源文件格式列表
     */
    private static final List<String> SOURCES = List.of("ppt", "pptx", "pptm", "potx", "potm");

    /**
     * 支持的目标文件格式列表
     */
    private static final List<String> TARGETS = List.of("pdf", "ppt", "pptx", "html", "htm", "tiff", "xps");

    /**
      * 格式与 Aspose.Slides 保存格式化 常量映射表
     */
    private static final Map<String, Integer> FORMAT_MAP = Map.ofEntries(
        Map.entry("pdf", SaveFormat.Pdf), Map.entry("ppt", SaveFormat.Ppt),
        Map.entry("pptx", SaveFormat.Pptx), Map.entry("html", SaveFormat.Html),
        Map.entry("htm", SaveFormat.Html), Map.entry("tiff", SaveFormat.Tiff),
        Map.entry("xps", SaveFormat.Xps)
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
            Presentation ppt = new Presentation(in);
            String ext = target.isPath() ? target.getPath().replaceAll(".*\\.", "") : target.getType();
            Integer fmt = FORMAT_MAP.get(ext);
            if (fmt == null) {
                throw new UnsupportedOperationException("不支持的目标格式: " + ext);
            }
            ppt.save(out, fmt);
        } catch (Exception e) {
            throw new RuntimeException("Aspose PPT 转换失败", e);
        }
    }
}
