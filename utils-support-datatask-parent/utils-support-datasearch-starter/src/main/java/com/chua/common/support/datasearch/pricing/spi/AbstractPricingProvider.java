package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 定价提供者抽象基类。
 *
 * <p>{@link #getPricing()} 只读本地文件，没有就返回空列表。本地文件需要通过 {@link #syncFromOnline()} 同步填入。</p>
 * <p>{@link #syncFromOnline()} 调用子类 {@link #fetchOnlinePricing()} 获取数据并写入本地文件。</p>
 *
 * <p>兜底机制：若子类需要，可通过 {@link #readClasspathPricing()} 读取 classpath 内置 JSON 作为兜底。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractPricingProvider implements PricingProvider {

    /**
     * 本地缓存键前缀
     */
    protected static final String PRICING_KEY_PREFIX = "pricing/";

    /**
     * 本地缓存键后缀
     */
    protected static final String PRICING_KEY_SUFFIX = ".json";

    /**
     * classpath 资源根路径
     */
    private static final String CLASSPATH_ROOT = "pricing/";

    protected static final Logger log = LoggerFactory.getLogger(AbstractPricingProvider.class);

    private ConfigSaveOrLoader configSaveOrLoader;

    protected AbstractPricingProvider() {
    }

    protected AbstractPricingProvider(ConfigSaveOrLoader configSaveOrLoader) {
        this.configSaveOrLoader = configSaveOrLoader;
    }

    @Override
    public String name() {
        Spi spi = this.getClass().getAnnotation(Spi.class);
        if (spi != null && spi.value().length > 0) {
            return spi.value()[0];
        }
        return this.getClass().getSimpleName().toLowerCase();
    }

    @Override
    public List<ModelDefinition> getPricing() {
        if (configSaveOrLoader == null) {
            return Collections.emptyList();
        }
        String key = PRICING_KEY_PREFIX + name() + PRICING_KEY_SUFFIX;
        try {
            java.util.Optional<byte[]> opt = configSaveOrLoader.loadBytes(key);
            if (opt.isPresent()) {
                String json = new String(opt.get(), StandardCharsets.UTF_8);
                List<ModelDefinition> parsed = Json.fromJson(json,
                        new com.fasterxml.jackson.core.type.TypeReference<List<ModelDefinition>>() {
                        });
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.debug("[{}] 读取本地定价缓存失败: {}", name(), e.getMessage());
        }
        return Collections.emptyList();
    }

    @Override
    public void syncFromOnline() {
        List<ModelDefinition> pricing = fetchOnlinePricing();
        if (pricing == null || pricing.isEmpty()) {
            return;
        }
        if (configSaveOrLoader != null) {
            String key = PRICING_KEY_PREFIX + name() + PRICING_KEY_SUFFIX;
            String json = Json.toJson(pricing);
            configSaveOrLoader.saveBytes(key, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 从线上 API 拉取定价数据。
     *
     * <p>默认行为：读取 classpath 内置 JSON 文件作为兜底数据。
     * 若厂商有公开定价 API，子类可覆写此方法直接调用线上接口。</p>
     *
     * @return 模型定价列表
     */
    public List<ModelDefinition> fetchOnlinePricing() {
        return readClasspathPricing();
    }

    /**
     * 从 classpath 内置 JSON 文件读取定价列表（兜底）。
     *
     * @return classpath 中的定价列表
     */
    protected List<ModelDefinition> readClasspathPricing() {
        String path = CLASSPATH_ROOT + name() + PRICING_KEY_SUFFIX;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                return Collections.emptyList();
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Json.fromJson(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ModelDefinition>>() {
                    });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ======================== HTTP 工具方法 ========================

    /**
     * 获取 HTTP 页面内容。
     *
     * @param url 页面地址
     * @return HTML 字符串，请求失败返回 null
     */
    protected String fetchUrl(String url) {
        try {
            return HttpClientFactory.of(url).get().getBodyString();
        } catch (Exception e) {
            log.debug("[{}] 请求页面失败: url={}, msg={}", name(), url, e.getMessage());
            return null;
        }
    }

    /**
     * 将 JSON 字符串解析为 ModelDefinition 列表。
     *
     * @param json JSON 数组字符串
     * @return 模型定价列表
     */
    protected List<ModelDefinition> parseJsonPricing(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<ModelDefinition> parsed = Json.fromJson(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ModelDefinition>>() {
                    });
            if (parsed != null && !parsed.isEmpty()) {
                return parsed;
            }
        } catch (Exception e) {
            log.debug("[{}] 解析 JSON 定价失败: {}", name(), e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * 设置配置加载器。
     *
     * @param configSaveOrLoader 配置加载器
     */
    public void setConfigSaveOrLoader(ConfigSaveOrLoader configSaveOrLoader) {
        this.configSaveOrLoader = configSaveOrLoader;
    }
}
