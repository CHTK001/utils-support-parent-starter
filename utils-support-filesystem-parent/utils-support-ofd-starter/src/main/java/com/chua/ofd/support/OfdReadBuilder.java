package com.chua.ofd.support;

import com.chua.common.support.file.builder.ReadBuilder;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
* OFD 文件读取构建器。
*
* <p>OFD 文件本质上是 ZIP 包，内含 XML 描述的版式内容。
* 本实现提取其中文档内容 XML 的纯文本部分。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class OfdReadBuilder extends ReadBuilder {

    /**
    * 创建 ofd读取构建器 实例
    * @param file 文件
     */
    public OfdReadBuilder(File file) { super(file); }

    /**
    * 提取 OFD 文档的纯文本内容。
    * @return 文本的结果
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".xml")) {
                    String xml = new String(zis.readAllBytes(), charset);
                    sb.append(xml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                }
            }
        } catch (IOException e) { return ""; }
        return sb.toString().trim();
    }
}
