package com.chua.common.support.io.file.stream;


/**
* 归档条目接口。
*
* <p>定义归档文件中单个条目的基本属性，对应 commons-compress 的 ArchiveEntry。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface ArchiveEntry {

    /**
    * 获取条目名称。
    *
    * @return 条目名称
    */
    String getName();

    /**
    * 判断是否为目录。
    *
    * @return true 表示为目录
    */
    boolean isDirectory();

    /**
    * 获取条目大小（字节）。
    *
    * @return 条目大小，-1 表示未知
    */
    long getSize();
}
