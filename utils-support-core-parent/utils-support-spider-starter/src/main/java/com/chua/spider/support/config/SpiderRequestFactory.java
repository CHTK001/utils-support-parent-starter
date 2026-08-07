package com.chua.spider.support.config;

import com.chua.spider.support.config.model.SpiderDefinition;
import com.chua.spider.support.config.model.SpiderProxy;
import com.chua.spider.support.config.store.SpiderProxyPoolStore;
import com.chua.spider.support.model.SpiderCookie;
import com.chua.spider.support.model.SpiderProxyConfig;
import com.chua.spider.support.model.SpiderRequest;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 爬虫请求工厂。
 *
 * <p>根据 {@link SpiderDefinition}（含代理池开关/Cookie/Header）构建
 * 实际的 {@link SpiderRequest}：若启用代理池，从池中按策略挑选一个代理；
 * 公共 Cookie/Header 会自动合并到每次请求上。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpiderRequestFactory {

    /**
     * 代理池存储。
     */
    private final SpiderProxyPoolStore proxyPoolStore;

    public SpiderRequestFactory(SpiderProxyPoolStore proxyPoolStore) {
        this.proxyPoolStore = proxyPoolStore;
    }

    /**
     * 根据爬虫定义与 URL 构建请求。
     *
     * @param definition 爬虫定义
     * @param url        目标 URL
     * @return SpiderRequest 实例
     */
    public SpiderRequest build(SpiderDefinition definition, String url) {
        return build(definition, url, null);
    }

    /**
     * 根据爬虫定义 + URL + 请求体构建请求。
     *
     * @param definition 爬虫定义
     * @param url        目标 URL
     * @param body       请求体（POST/PUT）
     * @return SpiderRequest 实例
     */
    public SpiderRequest build(SpiderDefinition definition, String url, String body) {
        Map<String, String> headers = new HashMap<>();
        if (definition.getSpiderHeaders() != null && !definition.getSpiderHeaders().isEmpty()) {
            headers.putAll(deserializeHeaders(definition.getSpiderHeaders()));
        }

        List<SpiderCookie> cookies = new ArrayList<>();
        if (definition.getSpiderCookies() != null && !definition.getSpiderCookies().isEmpty()) {
            cookies.addAll(deserializeCookies(definition.getSpiderCookies()));
        }

        SpiderProxyConfig proxy = null;
        if (Integer.valueOf(1).equals(definition.getSpiderProxyPoolEnable())
                && definition.getSpiderProxyPoolCode() != null
                && !definition.getSpiderProxyPoolCode().isEmpty()) {
            SpiderProxy picked = proxyPoolStore.nextProxy(definition.getSpiderProxyPoolCode());
            if (picked != null) {
                proxy = SpiderProxyConfig.builder()
                        .proxyHost(picked.getProxyHost())
                        .proxyPort(picked.getProxyPort())
                        .proxyProtocol(picked.getProxyProtocol())
                        .proxyUsername(picked.getProxyUsername())
                        .proxyPassword(picked.getProxyPassword())
                        .build();
            }
        }

        return SpiderRequest.builder()
                .url(url)
                .method("GET")
                .headers(headers)
                .cookieList(cookies)
                .proxy(proxy)
                .body(body)
                .build();
    }

    /**
     * 反序列化 Headers JSON 字符串为 Map。
     *
     * @param json Headers JSON 字符串
     * @return 解析后的 Map；解析失败返回空 Map
     */
    private Map<String, String> deserializeHeaders(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, String> result = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /**
     * 反序列化 Cookies JSON 字符串为 List。
     *
     * @param json Cookies JSON 字符串
     * @return 解析后的 List；解析失败返回空 List
     */
    private List<SpiderCookie> deserializeCookies(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<SpiderCookie> result = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<SpiderCookie>>() {});
            return result != null ? result : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}