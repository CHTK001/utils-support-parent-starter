package com.chua.html.support;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
* HTML 水印删除工具类。
*
* <p>用于删除 Aspose 生成的 HTML 中的评估水印信息，支持按正则匹配 CSS 样式、HTML 标签、注释和脚本中的水印内容，
* 以及按关键字删除包含指定水印文本的行。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class HtmlWatermarkRemover {

    /**
    * 水印相关 CSS 样式和元素的正则匹配模式数组
    *
    * <p>每个 Pattern 匹配一种水印注入方式：</p>
    * <ul>
    *   <li>包含 evaluation/trial/watermark/demo 关键字的 CSS 样式块</li>
    *   <li>包含水印关键字的 div 标签及其内容</li>
    *   <li>包含水印关键字的 HTML 注释</li>
    *   <li>包含水印关键字的 script 标签及其内容</li>
    * </ul>
    */
    private static final Pattern[] WATERMARK_PATTERNS = {
        Pattern.compile("(?i)(?:eval|trial|watermark|demo).*?\\{[^}]*\\}", Pattern.DOTALL),
        Pattern.compile("(?i)<div[^>]*(?:eval|trial|watermark|demo)[^>]*>.*?</div>", Pattern.DOTALL),
        Pattern.compile("(?i)<!--.*?(?:eval|trial|watermark|demo).*?-->", Pattern.DOTALL),
        Pattern.compile("(?i)<script[^>]*>.*?(?:eval|trial|watermark|demo).*?</script>", Pattern.DOTALL),
    };

    /**
    * 常见水印关键字数组
    *
    * <p>包含 Aspose 评估版可能注入到 HTML 中的各类水印文本：</p>
    * <ul>
    *   <li>Evaluation Only - Aspose 评估版提示</li>
    *   <li>Created with Aspose - Aspose 生成标识</li>
    *   <li>Aspose - 产品名简写</li>
    *   <li>Trial Version - 试用版标识</li>
    *   <li>Watermark - 通用水印标记</li>
    *   <li>Demo - 演示版本标识</li>
    *   <li>Evaluation - 评估版本标识</li>
    * </ul>
    */
    private static final String[] WATERMARK_TEXTS = {
        "Evaluation Only", "Created with Aspose", "Aspose",
        "Trial Version", "Watermark", "Demo", "Evaluation"
    };

    /**
    * 删除 HTML 中的水印内容
    *
    * <p>通过正则匹配和关键字过滤，移除 Aspose 评估版注入的水印信息。</p>
    *
    * @param htmlContent HTML 原始内容
    * @return 删除水印后的 HTML 内容
    */
    public static String removeWatermark(String htmlContent) {
        if (htmlContent == null || htmlContent.isEmpty()) {
            return htmlContent;
        }

        String result = htmlContent;

        for (Pattern pattern : WATERMARK_PATTERNS) {
            result = pattern.matcher(result).replaceAll("");
        }

        for (String watermarkText : WATERMARK_TEXTS) {
            result = removeWatermarkText(result, watermarkText);
        }

        result = result.replaceAll("(?m)^\\s*$", "");
        result = result.replaceAll("\\n{3,}", "\n\n");

        return result;
    }

    /**
    * 删除包含指定水印文本的 HTML 标签和行
    *
    * <p>先通过正则匹配删除包含水印文本的完整 HTML 标签，再逐行检查并删除包含水印文本的行。</p>
    *
    * @param htmlContent  HTML 内容
    * @param watermarkText 水印文本
    * @return 删除后的 HTML 内容
    */
    private static String removeWatermarkText(String htmlContent, String watermarkText) {
        if (htmlContent == null || !htmlContent.contains(watermarkText)) {
            return htmlContent;
        }

        Pattern pattern = Pattern.compile(
            "(?i)<[^>]*>.*?" + Pattern.quote(watermarkText) + ".*?</[^>]*>",
            Pattern.DOTALL
        );
        String result = pattern.matcher(htmlContent).replaceAll("");

        StringBuilder sb = new StringBuilder();
        for (String line : result.split("\n")) {
            if (!line.toLowerCase().contains(watermarkText.toLowerCase())) {
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    /**
    * 删除 HTML 字节数组中的水印
    *
    * <p>将字节数组按 UTF-8 编码转为字符串后调用 {@link #removeWatermark(String)} 进行处理。</p>
    *
    * @param htmlBytes HTML 字节数组
    * @return 删除水印后的 HTML 字节数组
    */
    public static byte[] removeWatermark(byte[] htmlBytes) {
        if (htmlBytes == null || htmlBytes.length == 0) {
            return htmlBytes;
        }

        String htmlContent = new String(htmlBytes, StandardCharsets.UTF_8);
        String cleanedContent = removeWatermark(htmlContent);
        return cleanedContent.getBytes(StandardCharsets.UTF_8);
    }
}
