package com.chua.spider.support;

import com.chua.spider.support.model.SpiderResult;

import java.util.Map;

/**
 * 爬虫 AI 智能解析器 SPI 接口。
 *
 * <p>通过 {@link com.chua.common.support.ai.chat.ChatClient} 对爬取结果进行
 * AI 增强处理。提供对页面内容的智能分析和结构化提取能力。
 *
 * <p>核心能力：
 * <ul>
 *   <li><b>内容总结</b> — 对页面文本进行 AI 自动摘要</li>
 *   <li><b>结构化提取</b> — 按 Schema 从非结构化内容中提取字段</li>
 *   <li><b>页面分类</b> — 对页面内容进行自动分类打标签</li>
 *   <li><b>内容翻译</b> — 将非目标语言的内容翻译为指定语言</li>
 * </ul>
 *
 * <p>实现类通过 {@code @Spi("ai")} 注册，使用 ChatClient 进行 AI 调用。
 *
 * @author CH
 * @since 2026/07/17
 */
public interface SpiderAiParser {

    /**
     * 对爬取结果进行 AI 总结。
     *
     * <p>使用 ChatClient 对页面纯文本进行智能摘要，生成简洁的总结内容。
     * 总结结果将被写入 {@link SpiderResult#getAiSummary()}。
     *
     * @param result 爬取解析结果，需包含 text 字段
     * @return AI 总结文本，失败时返回空字符串
     */
    String summarize(SpiderResult result);

    /**
     * 按 Schema 从内容中提取结构化数据。
     *
     * <p>给定一个 Schema 描述（如 JSON 模板），通过 AI 从页面文本中
     * 提取对应的字段值。例如从新闻页面提取：标题、作者、发布时间、正文。
     *
     * @param result 爬取解析结果
     * @param schema Schema 描述，如 "{\"title\": \"\", \"author\": \"\", \"date\": \"\"}"
     * @return 提取的字段键值对
     */
    Map<String, Object> extract(SpiderResult result, String schema);

    /**
     * 对页面内容进行分类。
     *
     * @param result     爬取解析结果
     * @param categories 候选分类列表，如 ["科技", "财经", "体育", "娱乐"]
     * @return 最佳匹配的分类名称
     */
    String classify(SpiderResult result, String[] categories);

    /**
     * 对页面内容进行翻译。
     *
     * @param result      爬取解析结果
     * @param targetLang  目标语言代码，如 "zh-CN"、"en"、"ja"
     * @return 翻译后的文本
     */
    String translate(SpiderResult result, String targetLang);
}
