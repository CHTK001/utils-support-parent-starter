package com.chua.common.support.file.converter;

import lombok.Getter;

/**
* 文件转换参数设置，控制转换过程中的可选行为。
*
* <p>当调用 {@link FileConvertSystem#convert(FileSource, FileSource, ConvertSetting)} 时传入，
* 用于指定编码、覆盖策略、行范围等控制参数。如果不需要特殊配置，使用无参构造器即可。</p>
*
* <h2>默认值</h2>
* <table border="1">
*   <tr><th>参数</th><th>类型</th><th>默认值</th><th>说明</th></tr>
*   <tr><td>charset</td><td>String</td><td>null</td><td>文件编码，为空时使用系统默认编码</td></tr>
*   <tr><td>overwrite</td><td>boolean</td><td>true</td><td>目标文件存在时是否覆盖</td></tr>
*   <tr><td>startRow</td><td>int</td><td>0</td><td>从第几行开始读取（0 表示第一行）</td></tr>
*   <tr><td>limit</td><td>int</td><td>0</td><td>最多读取行数（0 表示不限制）</td></tr>
* </table>
*
* @author CH
* @since 1.0.0
 */
@Getter
public class ConvertSetting {

    /**
    * 文件编码，为空时使用系统默认编码
    */
    private String charset;

    /**
    * 目标文件存在时是否覆盖，默认覆盖
    */
    private boolean overwrite = true;

    /**
    * 从第几行开始读取（0 表示第一行）
    */
    private int startRow;

    /**
    * 最多读取行数（0 表示不限制）
    */
    private int limit;

    /**
    * 设置文件编码。
    *
    * @param charset 编码名称
    */
    public void setCharset(String charset) {
        this.charset = charset;
    }

    /**
    * 设置是否覆盖目标文件。
    *
    * @param overwrite 是否覆盖
    */
    public void setOverwrite(boolean overwrite) {
        this.overwrite = overwrite;
    }

    /**
    * 设置起始行号。
    *
    * @param startRow 起始行号
    */
    public void setStartRow(int startRow) {
        this.startRow = startRow;
    }

    /**
    * 设置最大读取行数。
    *
    * @param limit 最大行数
    */
    public void setLimit(int limit) {
        this.limit = limit;
    }
}
