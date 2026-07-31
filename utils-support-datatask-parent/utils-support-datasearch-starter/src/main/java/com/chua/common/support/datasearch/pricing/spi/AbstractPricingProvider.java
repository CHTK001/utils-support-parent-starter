package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 定价提供者抽象基类。
 *
 * <p>提供本地缓存读写能力，子类只需实现 {@link #getBuiltinPricing()} 返回内置定价。</p>
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

    private static final Logger log = LoggerFactory.getLogger(AbstractPricingProvider.class);

    /**
     * 配置加载器
     */
    private ConfigSaveOrLoader configSaveOrLoader;

    /**
     * 是否已从本地加载过
     */
    private boolean localLoaded;

    /**
     * 本地缓存数据
     */
    private List<ModelDefinition> localCache;

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
            return getBuiltinPricing();
        }
        if (!localLoaded) {
            localLoaded = true;
            String key = PRICING_KEY_PREFIX + name() + PRICING_KEY_SUFFIX;
            try {
                java.util.Optional<byte[]> opt = configSaveOrLoader.loadBytes(key);
                if (opt.isPresent()) {
                    String json = new String(opt.get(), StandardCharsets.UTF_8);
                    List<ModelDefinition> parsed = Json.fromJson(json,
                            new com.fasterxml.jackson.core.type.TypeReference<List<ModelDefinition>>() {
                            });
                    if (parsed != null && !parsed.isEmpty()) {
                        localCache = parsed;
                        return localCache;
                    }
                }
            } catch (Exception e) {
                log.debug("[{}] 读取本地定价缓存失败: {}", name(), e.getMessage());
            }
            localCache = getBuiltinPricing();
        }
        return localCache != null ? localCache : Collections.emptyList();
    }

    @Override
    public void syncToLocal() {
        if (configSaveOrLoader == null) {
            log.warn("[{}] ConfigSaveOrLoader 为空，无法同步到本地", name());
            return;
        }
        List<ModelDefinition> pricing = getBuiltinPricing();
        if (pricing == null || pricing.isEmpty()) {
            return;
        }
        String key = PRICING_KEY_PREFIX + name() + PRICING_KEY_SUFFIX;
        String json = Json.toJson(pricing);
        configSaveOrLoader.saveBytes(key, json.getBytes(StandardCharsets.UTF_8));
        localCache = new ArrayList<>(pricing);
        localLoaded = true;
    }

    /**
     * 返回内置定价数据。
     *
     * <p>子类实现此方法提供硬编码的官方定价。</p>
     *
     * @return 模型定价列表
     */
    protected abstract List<ModelDefinition> getBuiltinPricing();

    /**
     * 设置配置加载器。
     *
     * @param configSaveOrLoader 配置加载器
     */
    public void setConfigSaveOrLoader(ConfigSaveOrLoader configSaveOrLoader) {
        this.configSaveOrLoader = configSaveOrLoader;
    }
}
