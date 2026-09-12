package com.chua.html.support;

import com.aspose.html.HTMLDocument;
import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 文件转 HTML 转换器。
 *
 * <p>使用 Aspose.HTML 将 EPUB、SVG、MHTML、Markdown 等格式转换为 HTML，并自动删除水印。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("aspose-html")
public class HtmlConvertFileSystem implements FileConvertSystem {

    /**
     * 支持的源文件格式列表
     */
    private static final List<String> SOURCES = List.of("html", "htm", "xhtml", "svg", "epub", "mhtml", "md");

    /**
      * 目标文件格式（固定为 HTML）
     */
    private static final String TARGET = "html";

    /**
     * 源格式集合（用于快速判断是否支持某格式）
     */
    private static final Set<String> SOURCE_SET = Set.copyOf(SOURCES);

    @Override
    /** 是否支持 */
    public boolean isSupported(String sourceType, String targetType) {
        if (!TARGET.equalsIgnoreCase(targetType)) {
            return false;
        }
        if (sourceType == null) {
            return false;
        }
        return SOURCE_SET.contains(sourceType.toLowerCase());
    }

    @Override
    /** 转换 */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        Path tempInput = null;
        Path tempOutput = null;
        try {
            String sourceFormat = getSourceFormat(source);
            if (sourceFormat == null || !SOURCE_SET.contains(sourceFormat.toLowerCase())) {
                throw new IllegalArgumentException("不支持的文件格式: " + sourceFormat);
            }

            if (source.isPath()) {
                tempInput = new File(source.getPath()).toPath();
            } else {
                tempInput = Files.createTempFile("html_convert_", "." + sourceFormat);
                try (InputStream in = source.getInputStream()) {
                    Files.copy(in, tempInput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            tempOutput = Files.createTempFile("html_result_", ".html");

            HTMLDocument document = new HTMLDocument(tempInput.toAbsolutePath().toString(), ".");
            try {
                document.save(tempOutput.toAbsolutePath().toString(), new com.aspose.html.saving.HTMLSaveOptions());
            } finally {
                document.dispose();
            }

            byte[] htmlBytes = Files.readAllBytes(tempOutput);
            htmlBytes = HtmlWatermarkRemover.removeWatermark(htmlBytes);

            if (target.isOutputStream()) {
                try (OutputStream out = target.getOutputStream()) {
                    out.write(htmlBytes);
                    out.flush();
                }
            } else {
                Files.write(new File(target.getPath()).toPath(), htmlBytes);
            }
        } catch (Exception e) {
            throw new RuntimeException("文件转HTML转换失败", e);
        } finally {
            if (tempInput != null && source.isInputStream()) {
                try {
                    Files.deleteIfExists(tempInput);
                } catch (Exception e) {
                    log.warn("删除临时输入文件失败", e);
                }
            }
            if (tempOutput != null) {
                try {
                    Files.deleteIfExists(tempOutput);
                } catch (Exception e) {
                    log.warn("删除临时输出文件失败", e);
                }
            }
        }
    }

    /**
     * 获取源文件的格式后缀
     *
     * @param source 文件源
     * @return 格式后缀（如 "HTML"、"epub"），无法识别时返回 空
     */
    private String getSourceFormat(FileSource source) {
        if (source.isPath()) {
            String name = source.getPath();
            int dot = name.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            return name.substring(dot + 1);
        }
        if (source.isUrl()) {
            String path = source.getUrl().getPath();
            int dot = path.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            return path.substring(dot + 1);
        }
        return source.getType();
    }
}
