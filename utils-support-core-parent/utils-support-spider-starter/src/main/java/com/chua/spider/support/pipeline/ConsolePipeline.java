package com.chua.spider.support.pipeline;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.spider.support.SpiderPipeline;
import com.chua.spider.support.model.SpiderResult;

/**
 * 控制台输出管道。
 *
 * <p>将爬取结果格式化后输出到控制台，主要用于调试和演示场景。
 * 展示 URL、标题、AI 总结和结构化字段等关键信息。
 *
 * <p>SPI 名称：{@code pipeline:console}
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("console")
public class ConsolePipeline implements SpiderPipeline {

    @Override
    public void process(SpiderResult result) {
        if (result == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n═══════════════════════════════════════════\n");
        sb.append("  URL:  ").append(nullToEmpty(result.getUrl())).append("\n");
        sb.append("  标题: ").append(nullToEmpty(result.getTitle())).append("\n");
        sb.append("  类型: ").append(nullToEmpty(result.getContentType())).append("\n");

        if (StringUtils.isNotEmpty(result.getAiSummary())) {
            sb.append("  AI 总结: ").append(result.getAiSummary()).append("\n");
        }

        if (StringUtils.isNotEmpty(result.getAiCategory())) {
            sb.append("  分类: ").append(result.getAiCategory()).append("\n");
        }

        if (CollectionUtils.isNotEmpty(result.getStructured())) {
            sb.append("  结构化字段:\n");
            result.getStructured().forEach((key, value) ->
                    sb.append("    - ").append(key).append(": ").append(value).append("\n"));
        }

        if (StringUtils.isNotEmpty(result.getText())) {
            String preview = result.getText().length() > 200 ?
                    result.getText().substring(0, 200) + "..." :
                    result.getText();
            sb.append("  内容预览: ").append(preview).append("\n");
        }

        sb.append("═══════════════════════════════════════════\n");
        System.out.println(sb);
    }

    /**
     * 将 null 转换为空字符串。
     */
    private static String nullToEmpty(String str) {
        return str != null ? str : "";
    }
}
