package com.chua.common.support.file.template;

import com.chua.common.support.spi.annotations.Spi;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* 简易文本模板引擎 — 将 {@code #key#} 替换为 data 中的对应值
* <p>支持 txt、xml、html 等纯文本模板。统一使用 {@code #key#} 作为占位符格式。</p>
*
* @author CH
* @since 2026-07-16
 */
@Spi({"txt", "xml", "html"})
public class TextTemplateFileSystem implements TemplateFileSystem {

    /** 占位符正则：匹配 #{单词.单词}# 格式 */
    private static final Pattern PLACEHOLDER = Pattern.compile("#([\\w.]+)#");

    @Override
    /** 解析 */
    public void resolve(InputStream inputStream, OutputStream outputStream, Map<String, Object> templateData) {
        try {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = PLACEHOLDER.matcher(content);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String key = matcher.group(1);
                Object value = templateData.get(key);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value.toString() : ""));
            }
            matcher.appendTail(sb);
            outputStream.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("模板解析失败", e);
        }
    }
}
