package com.chua.dbf.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;

/**
   * DBF（dbase）文件系统 SPI 实现。
 *
 * <p>基于 javadbf 库实现 .dbf 文件的读取与写入。
   * DBF 是 dbase、foxpro 等数据库系统使用的表格数据格式。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * FileSystem fs = FileSystem.create("dbf");
 *
 * // 读取
 * List<Map<String, Object>> rows = ((DbfReadBuilder) fs.read(new File("data.dbf"))).rows();
 *
 * // 写入
 * DbfWriteBuilder wb = (DbfWriteBuilder) fs.write(new File("out.dbf"));
 * wb.write(data);
 * }</pre>(new File("out.dbf"));
 * wb.write(data);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("dbf")
public class DbfFileSystem implements FileSystem {

    @Override
    /** 获取类型 */
    public String getType() {
        
        return "dbf";
    
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        
        return new DbfReadBuilder(file);
    
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        
        return new DbfWriteBuilder(file);
    
    }
}

