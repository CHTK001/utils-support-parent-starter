package com.chua.ofd.support;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.*;

/**
 * OFD 版式文件系统 SPI 实现。
 *
 * <p>OFD (Open Format Document) 是国家版式文档标准，
 * 支持 .ofd 文件的文本提取与基本读取。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ofd")
public class OfdFileSystem implements FileSystem {

    @Override
    public String getType() {
         return "ofd"; 
    }

    @Override
    public ReadBuilder read(File file) {
         return new OfdReadBuilder(file); 
    }

    @Override
    public WriteBuilder write(File file) { throw new UnsupportedOperationException("OFD write not yet supported"); }
}

