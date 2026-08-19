package com.chua.word.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;

/**
 * Word（.docx）文件系统 SPI 实现。
 *
 * <p>基于 Apache POI 库实现 Word 文档的文本提取与文本写入。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * FileSystem fs = FileSystem.create("docx");
 *
 * // 读取文本
 * String text = ((WordReadBuilder) fs.read(new File("doc.docx"))).text();
 *
 * // 写入文本
 * WordWriteBuilder wb = (WordWriteBuilder) fs.write(new File("out.docx"));
 * wb.writeText(List.of("标题", "正文内容"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"docx", "doc"})
public class WordFileSystem implements FileSystem {

    @Override
    /** 获取Type */
    public String getType() {
        
        return "docx";
    
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        
        return new WordReadBuilder(file);
    
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        
        return new WordWriteBuilder(file);
    
    }
}

