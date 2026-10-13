package com.chua.bilibili.support;

import com.chua.common.support.ai.translate.TranslateClientSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.openai.support.OpenAiTranslateClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 哔哩哔哩 Index-Translate 翻译客户端
 *
 * <p>Bilibili 的翻译服务（{@code index-translate.bilibili.com}）采用
 * OpenAI 兼容的 Chat Completions 协议，因此本类直接继承
 * {@link OpenAiTranslateClient} 复用 openai-java SDK 的完整实现，
 * 仅覆盖默认地址与默认模型：
 * <ul>
 *   <li>默认地址：{@code https://index-translate.bilibili.com/v1}</li>
 *   <li>默认模型：{@code Index-Translate-35B-A3B}</li>
 * </ul>
 *
 * <p>SPI 名称：{@code bilibili} / {@code index-translate}，
 * appKey 为 API Key。
 *
 * <p>使用示例：
 * <pre>{@code
 *   String text = TranslateClient.create("bilibili", "sk-xxx")
 *       .targetLang("英语")
 *       .translateSync("你好，世界。");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"bilibili", "index-translate"})
public class BilibiliTranslateClient extends OpenAiTranslateClient {

    /**
     * Bilibili Index-Translate 默认 API 地址
     */
    protected static final String DEFAULT_URL = "https://index-translate.bilibili.com/v1";

    /**
     * Bilibili 默认翻译模型
     */
    protected static final String DEFAULT_MODEL = "Index-Translate-35B-A3B";

    /**
     * 构造 Bilibili 翻译客户端
     *
     * @param setting 客户端配置
     */
    public BilibiliTranslateClient(TranslateClientSetting setting) {
        super(setting);
    }

    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_URL;
    }

    @Override
    protected String defaultModel() {
        return DEFAULT_MODEL;
    }
}
