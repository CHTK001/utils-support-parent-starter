package com.chua.pandoc.support.converter;

import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Pandoc 通用文档格式转换器。
 *
 * <p>通过命令行调用 Pandoc 实现多种文档格式的相互转换。
   * 支持 markdown、HTML、docx、epub、乳胶、rst、org、textile、mediawiki 等格式的交叉转换，
   * 并支持输出为 PDF（需 乳胶 引擎）和多种幻灯片格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("pandoc")
public class PandocFileConvertSystem implements FileConvertSystem {

    /**
     * 支持的源文件格式列表
     */
    private static final List<String> SOURCES = List.of(
        "md", "markdown", "html", "htm", "xhtml", "docx", "epub", "latex", "tex",
        "rst", "org", "textile", "mediawiki", "opml", "creole", "commonmark", "gfm",
        "tsv", "csv", "json", "doc", "odt", "ods", "odp", "pptx", "rtf", "t2t"
    );

    /**
     * 支持的目标文件格式列表
     */
    private static final List<String> TARGETS = List.of(
        "md", "markdown", "html", "htm", "xhtml", "html5", "docx", "epub", "epub3",
        "latex", "tex", "pdf", "rst", "org", "textile", "mediawiki", "opml", "creole",
        "commonmark", "gfm", "tsv", "json", "doc", "odt", "ods", "odp", "pptx", "rtf",
        "asciidoc", "man", "t2t"
    );

    /**
     * 源格式集合
     */
    private static final Set<String> SOURCE_SET = Set.copyOf(SOURCES);

    /**
     * 目标格式集合
     */
    private static final Set<String> TARGET_SET = Set.copyOf(TARGETS);

    /**
     * 文件扩展名到 Pandoc 格式名的映射
     */
    private static final List<FormatMapping> FORMAT_MAPPINGS = List.of(
        new FormatMapping("md", "markdown"),
        new FormatMapping("markdown", "markdown"),
        new FormatMapping("html", "html"),
        new FormatMapping("htm", "html"),
        new FormatMapping("xhtml", "html"),
        new FormatMapping("html5", "html5"),
        new FormatMapping("docx", "docx"),
        new FormatMapping("epub", "epub"),
        new FormatMapping("epub3", "epub3"),
        new FormatMapping("latex", "latex"),
        new FormatMapping("tex", "latex"),
        new FormatMapping("pdf", "pdf"),
        new FormatMapping("rst", "rst"),
        new FormatMapping("org", "org"),
        new FormatMapping("textile", "textile"),
        new FormatMapping("mediawiki", "mediawiki"),
        new FormatMapping("opml", "opml"),
        new FormatMapping("creole", "creole"),
        new FormatMapping("commonmark", "commonmark"),
        new FormatMapping("gfm", "gfm"),
        new FormatMapping("tsv", "tsv"),
        new FormatMapping("csv", "csv"),
        new FormatMapping("json", "json"),
        new FormatMapping("doc", "docx"),
        new FormatMapping("odt", "odt"),
        new FormatMapping("ods", "ods"),
        new FormatMapping("odp", "odp"),
        new FormatMapping("pptx", "pptx"),
        new FormatMapping("rtf", "rtf"),
        new FormatMapping("asciidoc", "asciidoc"),
        new FormatMapping("man", "man"),
        new FormatMapping("t2t", "t2t")
    );

    /**
     * 超时时间（秒）
     */
    private static final long COMMAND_TIMEOUT_SECONDS = 300L;

    @Override
    /** 是否支持 */
    public boolean isSupported(String source, String target) {
        if (source == null || target == null) {
            return false;
        }
        String src = source.toLowerCase();
        String tgt = target.toLowerCase();
        if (src.equals(tgt)) {
            return false;
        }
        return SOURCE_SET.contains(src) && TARGET_SET.contains(tgt);
    }

    @Override
    /** 转换 */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        Path tempInput = null;
        Path tempOutput = null;
        try {
            String pandoc = PandocEnvironment.getPandocPath();

            String sourceExt = getSourceFormat(source);
            if (sourceExt == null || !SOURCE_SET.contains(sourceExt.toLowerCase())) {
                throw new IllegalArgumentException("不支持的文件格式: " + sourceExt);
            }

            String targetExt = target.isPath()
                ? target.getPath().replaceAll(".*\\.", "")
                : target.getType();
            if (targetExt == null || !TARGET_SET.contains(targetExt.toLowerCase())) {
                throw new IllegalArgumentException("不支持的目标格式: " + targetExt);
            }

            String fromFormat = toPandocFormat(sourceExt);
            String toFormat = toPandocFormat(targetExt);

            if (source.isPath()) {
                tempInput = new File(source.getPath()).toPath();
            } else {
                tempInput = Files.createTempFile("pandoc_", "." + sourceExt);
                try (InputStream in = source.getInputStream()) {
                    Files.copy(in, tempInput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            tempOutput = Files.createTempFile("pandoc_result_", "." + targetExt);

            String command = pandoc + " -f " + fromFormat + " -t " + toFormat
                + " -o \"" + tempOutput.toAbsolutePath() + "\""
                + " \"" + tempInput.toAbsolutePath() + "\"";

            log.info("执行 Pandoc 转换: {}", command);
            CmdResult result = CmdExecutors.execute(command, COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!result.isSuccess()) {
                String error = result.getStderr();
                if (error == null || error.isEmpty()) {
                    error = result.getStdout();
                }
                throw new RuntimeException("Pandoc 转换失败, exit=" + result.getExitCode()
                    + ", error: " + (error != null ? error.trim() : "未知错误"));
            }

            byte[] outputBytes = Files.readAllBytes(tempOutput);
            if (outputBytes.length == 0) {
                throw new RuntimeException("Pandoc 转换结果为空");
            }

            if (target.isOutputStream()) {
                try (OutputStream out = target.getOutputStream()) {
                    out.write(outputBytes);
                    out.flush();
                }
            } else {
                Files.write(new File(target.getPath()).toPath(), outputBytes);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Pandoc 转换失败", e);
        } finally {
            if (tempInput != null && !source.isPath()) {
                try { Files.deleteIfExists(tempInput); } catch (Exception ignored) {}
            }
            if (tempOutput != null) {
                try { Files.deleteIfExists(tempOutput); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 将文件扩展名转换为 Pandoc 格式名
     *
     * @param extension 文件扩展名
     * @return Pandoc 格式名
     */
    private String toPandocFormat(String extension) {
        String ext = extension.toLowerCase();
        for (FormatMapping mapping : FORMAT_MAPPINGS) {
            if (mapping.extension.equals(ext)) {
                return mapping.format;
            }
        }
        return ext;
    }

    /**
     * 获取源文件的格式后缀
     *
     * @param source 文件源
     * @return 格式后缀
     */
    private String getSourceFormat(FileSource source) {
        if (source.isPath()) {
            String name = source.getPath();
            int dot = name.lastIndexOf('.');
            return dot < 0 ? null : name.substring(dot + 1);
        }
        return source.getType();
    }

    /**
     * 文件扩展名到 Pandoc 格式名的映射记录
     *
     * @param extension 文件扩展名
     * @param format    Pandoc 格式名
     * @return 格式化mapping的结果
     */
    private record FormatMapping(String extension, String format) {
    }
}
