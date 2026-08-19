package com.chua.common.support.datasearch.music.spi.support;

import com.chua.common.support.datasearch.music.model.MusicOverview;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSummary;
import com.chua.common.support.datasearch.music.model.MusicSearchResult;
import com.chua.common.support.datasearch.music.model.MusicTrackSummary;
import com.chua.common.support.datasearch.music.spi.MusicSourceProvider;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * HTTP音乐源提供者抽象基类
 * 封装HTTP请求、JSON解析、超时配置等通用能力，各平台实现类通过继承此类减少样板代码
 * 
 * @since 4.0.0.42
*/
@Slf4j
public abstract class AbstractHttpMusicSourceProvider implements MusicSourceProvider {

    /** Mapper */
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    /** Desktop_ua */
    protected static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    /** Mobile_ua */
    protected static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    /** Default_connect_timeout_millis */
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;
    /** Default_read_timeout_millis */
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 20_000;
    /** Connect_timeout_property */
    private static final String CONNECT_TIMEOUT_PROPERTY = "music.http.connect-timeout-millis";
    /** Connect_timeout_env */
    private static final String CONNECT_TIMEOUT_ENV = "MUSIC_HTTP_CONNECT_TIMEOUT_MILLIS";
    /** Read_timeout_property */
    private static final String READ_TIMEOUT_PROPERTY = "music.http.read-timeout-millis";
    /** Read_timeout_env */
    private static final String READ_TIMEOUT_ENV = "MUSIC_HTTP_READ_TIMEOUT_MILLIS";

    protected JsonNode getJson(String url) {
        return getJson(url, null);
    }

    protected JsonNode getJson(String url, Consumer<HttpClientBuilder> customizer) {
        try {
            HttpClientBuilder builder = HttpClientFactory.of(url)
                    .header("User-Agent", DESKTOP_UA)
                    .connectTimeout(connectTimeoutMillis())
                    .readTimeout(readTimeoutMillis());
            if (customizer != null) {
                customizer.accept(builder);
            }
            ClientResponse response = builder.get();
            assertOk(response, url);
            return MAPPER.readTree(response.getBodyString());
        } catch (Exception e) {
            throw new IllegalStateException("请求音乐接口失败: " + url, e);
        }
    }

    protected String getText(String url) {
        return getText(url, null);
    }

    protected String getText(String url, Consumer<HttpClientBuilder> customizer) {
        try {
            HttpClientBuilder builder = HttpClientFactory.of(url)
                    .header("User-Agent", DESKTOP_UA)
                    .connectTimeout(connectTimeoutMillis())
                    .readTimeout(readTimeoutMillis());
            if (customizer != null) {
                customizer.accept(builder);
            }
            ClientResponse response = builder.get();
            assertOk(response, url);
            return response.getBodyString();
        } catch (Exception e) {
            throw new IllegalStateException("请求音乐接口失败: " + url, e);
        }
    }

    protected JsonNode postJson(String url, Object body, Consumer<HttpClientBuilder> customizer) {
        String jsonBody;
        try {
            jsonBody = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("序列化请求体失败: " + url, e);
        }
        try {
            HttpClientBuilder builder = HttpClientFactory.of(url)
                    .json()
                    .header("User-Agent", DESKTOP_UA)
                    .body(jsonBody)
                    .connectTimeout(connectTimeoutMillis())
                    .readTimeout(readTimeoutMillis());
            if (customizer != null) {
                customizer.accept(builder);
            }
            ClientResponse response = builder.post();
            assertOk(response, url);
            return MAPPER.readTree(response.getBodyString());
        } catch (Exception e) {
            throw new IllegalStateException("请求音乐接口失败: " + url, e);
        }
    }

    protected JsonNode postForm(String url, Map<String, ?> form, Consumer<HttpClientBuilder> customizer) {
        try {
            HttpClientBuilder builder = HttpClientFactory.of(url)
                    .form()
                    .header("User-Agent", DESKTOP_UA)
                    .connectTimeout(connectTimeoutMillis())
                    .readTimeout(readTimeoutMillis());
            if (form != null) {
                for (Map.Entry<String, ?> entry : form.entrySet()) {
                    Object value = entry.getValue();
                    builder.body(entry.getKey(), value != null ? value.toString() : "");
                }
            }
            if (customizer != null) {
                customizer.accept(builder);
            }
            ClientResponse response = builder.post();
            assertOk(response, url);
            return MAPPER.readTree(response.getBodyString());
        } catch (Exception e) {
            throw new IllegalStateException("请求音乐接口失败: " + url, e);
        }
    }

    protected ClientResponse getResponse(String url, Consumer<HttpClientBuilder> customizer) {
        try {
            HttpClientBuilder builder = HttpClientFactory.of(url)
                    .header("User-Agent", DESKTOP_UA)
                    .connectTimeout(connectTimeoutMillis())
                    .readTimeout(readTimeoutMillis());
            if (customizer != null) {
                customizer.accept(builder);
            }
            ClientResponse response = builder.get();
            assertOk(response, url);
            return response;
        } catch (Exception e) {
            throw new IllegalStateException("请求音乐接口失败: " + url, e);
        }
    }

    protected MusicOverview overviewOf(List<String> hotKeywords, List<MusicPlaylistSummary> featuredPlaylists) {
        return MusicOverview.builder()
                .hotKeywords(hotKeywords == null ? List.of() : hotKeywords)
                .featuredPlaylists(featuredPlaylists == null ? List.of() : featuredPlaylists)
                .build();
    }

    protected MusicSearchResult searchResult(String keyword, int page, int pageSize, long total, List<MusicTrackSummary> tracks) {
        return MusicSearchResult.builder()
                .source(getSource().getCode())
                .keyword(keyword)
                .page(page)
                .pageSize(pageSize)
                .total(total)
                .tracks(tracks == null ? List.of() : tracks)
                .build();
    }

    protected String text(JsonNode node, String... fields) {
        JsonNode current = path(node, fields);
        return current == null || current.isMissingNode() || current.isNull() ? "" : current.asText("");
    }

    protected int integer(JsonNode node, String... fields) {
        JsonNode current = path(node, fields);
        return current == null || current.isMissingNode() || current.isNull() ? 0 : current.asInt(0);
    }

    protected long longValue(JsonNode node, String... fields) {
        JsonNode current = path(node, fields);
        return current == null || current.isMissingNode() || current.isNull() ? 0L : current.asLong(0L);
    }

    protected JsonNode path(JsonNode node, String... fields) {
        JsonNode current = node;
        for (String field : fields) {
            if (current == null) {
                return null;
            }
            current = current.path(field);
        }
        return current;
    }

    protected List<JsonNode> elements(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        node.forEach(result::add);
        return result;
    }

    protected String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    protected String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    protected void assertOk(ClientResponse response, String url) {
        if (response == null || !response.isSuccess()) {
            throw new IllegalStateException("音乐接口请求失败: " + url);
        }
    }

    protected int connectTimeoutMillis() {
        return resolveTimeoutMillis(CONNECT_TIMEOUT_PROPERTY, CONNECT_TIMEOUT_ENV, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    protected int readTimeoutMillis() {
        return resolveTimeoutMillis(READ_TIMEOUT_PROPERTY, READ_TIMEOUT_ENV, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    private int resolveTimeoutMillis(String propertyName, String envName, int defaultValue) {
        String configured = System.getProperty(propertyName);
        if (!StringUtils.hasText(configured)) {
            configured = System.getenv(envName);
        }
        if (!StringUtils.hasText(configured)) {
            return defaultValue;
        }
        try {
            int timeoutMillis = Integer.parseInt(configured.trim());
            return timeoutMillis > 0 ? timeoutMillis : defaultValue;
        } catch (NumberFormatException ex) {
            log.warn("Ignore invalid music timeout configuration, property={}, env={}, value={}",
                    propertyName, envName, configured);
            return defaultValue;
        }
    }
}