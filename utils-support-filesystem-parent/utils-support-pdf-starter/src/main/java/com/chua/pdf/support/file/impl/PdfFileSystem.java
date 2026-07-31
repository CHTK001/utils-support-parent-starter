package com.chua.pdf.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;

/**
 * PDF 文件系统 SPI 实现。
 *
 * <p>基于 Apache PDFBox 库实现 PDF 文件的文本提取与简单文本写入。
 * 支持从 PDF 中提取全部文本内容，以及将文本行写入新的 PDF 文件。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * FileSystem fs = FileSystem.create("pdf");
 *
 * // 提取文本
 * String text = ((PdfReadBuilder) fs.read(new File("doc.pdf"))).text();
 *
 * // 写入文本 PDF
 * PdfWriteBuilder wb = (PdfWriteBuilder) fs.write(new File("out.pdf"));
 * wb.writeText(List.of("第一行", "第二行"));
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("pdf")
public class PdfFileSystem implements FileSystem {

    @Override
    public String getType() {
        
        return "pdf";
    
    }

    @Override
    public ReadBuilder read(File file) {
        
        return new PdfReadBuilder(file);
    
    }

    @Override
    public WriteBuilder write(File file) {
        
        return new PdfWriteBuilder(file);
    
    }
}

