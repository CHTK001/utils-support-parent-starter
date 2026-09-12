package com.chua.libreoffice.support.converter;

import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
   * libre办公室 文档格式转换器。
 *
 * <p>通过命令行调用 LibreOffice 实现多种 Office 格式的相互转换。
 * 支持 doc/docx/xls/xlsx/ppt/pptx/odt/ods/odp 等格式的交叉转换。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("libreoffice")
public class LibreOfficeFileConvertSystem implements FileConvertSystem {

    /**
     * 支持的源文件格式列表
     */
    private static final List<String> SOURCES = List.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp");

    @Override
    /** 是否支持 */
    public boolean isSupported(String source, String target) {
        if (!SOURCES.contains(source)) {
            return false;
        }
        return !source.equals(target);
    }

    @Override
    /** 转换 */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        try {
            String src = source.isPath() ? source.getPath() : writeTemp(source.getInputStream(), "." + source.getType());
            String outDir = target.isPath()
                    ? new File(target.getPath()).getParent()
                    : System.getProperty("java.io.tmpdir");

            String targetExt = target.isPath() ? target.getPath().replaceAll(".*\\.", "") : target.getType();
            Process proc = new ProcessBuilder("soffice", "--headless", "--convert-to", targetExt, "--outdir", outDir, src)
                    .redirectErrorStream(true).start();
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new IOException("LibreOffice 转换失败, exit=" + exit);
            }

            if (target.isOutputStream()) {
                File result = new File(outDir, new File(src).getName().replaceAll("\\.[^.]+$", "." + targetExt));
                try (InputStream is = new FileInputStream(result)) {
                    is.transferTo(target.getOutputStream());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("LibreOffice 转换失败", e);
        }
    }

    /**
     * 将输入流写入临时文件
     *
     * @param in     输入流
     * @param suffix 文件后缀
     * @return 临时文件绝对路径
     */
    private static String writeTemp(InputStream in, String suffix) throws IOException {
        File f = File.createTempFile("lo_", suffix);
        try (OutputStream os = new FileOutputStream(f)) {
            in.transferTo(os);
        }
        return f.getAbsolutePath();
    }
}
