package com.chua.common.support.datasearch.pricing;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.config.loader.ConfigSaveOrLoader;
import com.chua.common.support.datasearch.pricing.spi.ModelMetricsProvider;
import com.chua.common.support.datasearch.usage.spi.UsageFieldCompleter;
import com.chua.common.support.spi.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 定价同步工具。
 *
 * <p>负责触发各厂商从线上拉取最新定价并持久化到本地文件缓存，
 * 或直接从本地加载全部厂商定价。</p>
 *
 * <pre>{@code
 *   // 从线上同步全部厂商定价到本地文件缓存
 *   PricingSyncer.syncAllFromOnline(FileConfigSaveOrLoader.create());
 *
 *   // 同步指定厂商定价到本地
 *   PricingSyncer.syncFromOnline(loader, "openrouter");
 *
 *   // 从本地文件缓存加载全部定价
 *   List<ModelDefinition> all = PricingSyncer.loadAll(loader);
 * }</pre>
 *
 * <p>{@code loader} 仅作历史签名保留，传 空 亦可：各厂商实现会在字段未注入时
 * 回退到默认本地文件存储，同步与加载都能正常工作。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class PricingSyncer {

    /**
     * 日志
    */
    private static final Logger log = LoggerFactory.getLogger(PricingSyncer.class);

    /**
     * 创建 pricing同步 实例
    */
    private PricingSyncer() {
    }

    /**
     * 从线上同步全部厂商定价到本地文件缓存。
     *
     * @param loader 配置加载器，可为 空
     * @return 同步成功的厂商数量
     */
    public static int syncAllFromOnline(ConfigSaveOrLoader loader) {
        Map<String, ModelMetricsProvider> providers = ServiceProvider.of(ModelMetricsProvider.class).list();
        if (providers == null || providers.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Map.Entry<String, ModelMetricsProvider> entry : providers.entrySet()) {
            try {
                entry.getValue().syncFromOnline();
                total++;
            } catch (Exception e) {
                log.warn("[PricingSyncer] 同步厂商[{}]定价失败: {}", entry.getKey(), e.getMessage());
            }
        }
        if (total > 0) {
            UsageFieldCompleter.reset();
        }
        log.info("[PricingSyncer] 同步完成，共 {} 个厂商", total);
        return total;
    }

    /**
     * 从线上同步指定厂商定价到本地文件缓存。
     *
     * @param loader 配置加载器，可为 空
     * @param names   厂商名称（如 "openrouter", "artificialanalysis"）
     * @return 同步成功的厂商数量
     */
    public static int syncFromOnline(ConfigSaveOrLoader loader, String... names) {
        if (names == null || names.length == 0) {
            return 0;
        }
        int total = 0;
        for (String name : names) {
            try {
                ModelMetricsProvider provider = ServiceProvider.of(ModelMetricsProvider.class).getNewExtension(name);
                if (provider == null) {
                    provider = ServiceProvider.of(ModelMetricsProvider.class).getExtension(name);
                }
                if (provider != null) {
                    provider.syncFromOnline();
                    total++;
                }
            } catch (Exception e) {
                log.warn("[PricingSyncer] 同步厂商[{}]定价失败: {}", name, e.getMessage());
            }
        }
        if (total > 0) {
            UsageFieldCompleter.reset();
        }
        return total;
    }

    /**
     * 从本地缓存加载全部厂商定价。
     *
     * @param loader 配置加载器，可为 空
     * @return 全部定价列表
     */
    public static List<ModelDefinition> loadAll(ConfigSaveOrLoader loader) {
        Map<String, ModelMetricsProvider> providers = ServiceProvider.of(ModelMetricsProvider.class).list();
        if (providers == null || providers.isEmpty()) {
            return Collections.emptyList();
        }
        List<ModelDefinition> result = new ArrayList<>();
        for (Map.Entry<String, ModelMetricsProvider> entry : providers.entrySet()) {
            try {
                result.addAll(entry.getValue().getMetrics());
            } catch (Exception e) {
                log.warn("[PricingSyncer] 加载厂商[{}]定价失败: {}", entry.getKey(), e.getMessage());
            }
        }
        return result;
    }
}
