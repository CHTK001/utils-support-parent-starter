package com.chua.common.support.datasearch.usage.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qoder 档位码到模型标识的解析器。
 *
 * <p>Qoder 的会话转录在 {@code message.model} 与 {@code runtime-config.model} 上写的都是
 * 内部档位码（{@code qfmodel}、{@code qmodel_38max} 等），并非用户在选择器里看到的模型名。
 * 客户端把显示名下发在 {@code ~/.qoder/.auth/dynamic-texts.json} 的
 * {@code modelSelector.item.<档位码>} 中，本类据此把档位码还原为可与定价目录对齐的
 * 模型标识（{@code Qwen3.8-Flash -> qwen3.8-flash}）。</p>
 *
 * <p>映射不到时按原值返回，因此未收录的档位码、其他版本的命名空间都不会被改写。
 * 档位表在首个 JVM 内加载一次；文件缺失或格式变化时退化为恒等映射。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class QoderModelCatalog {

    /**
     * 日志
     */
    private static final Logger log = LoggerFactory.getLogger(QoderModelCatalog.class);

    /**
     * Qoder 下发显示名的文件
     */
    private static final Path TEXTS_FILE = Path.of(
            System.getProperty("user.home"), ".qoder", ".auth", "dynamic-texts.json");

    /**
     * 档位码条目：档位码不含点号，借此把 {@code .description} 等派生键排除在外
     */
    private static final Pattern ENTRY =
            Pattern.compile("\"modelSelector\\.item\\.([A-Za-z0-9_-]+)\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 路由与计费档位：显示名是套餐而非模型，还原后反而会与真实模型混淆，保持原值
     */
    private static final Set<String> PLAN_TIERS = Set.of(
            "auto", "lite", "ultimate", "performance", "efficient",
            "experts-auto", "experts-ultimate", "quest-auto", "quest-ultimate");

    /**
     * 档位码 -> 模型标识（加载失败时为空表）
     */
    private static volatile Map<String, String> mapping;

    private QoderModelCatalog() {
    }

    /**
     * 把 Qoder 档位码还原为模型标识。
     *
     * @param code 转录中记录的档位码，允许为 空
     * @return 模型标识；档位码为空、属计费档位或目录未收录时返回原值
     */
    public static String resolve(String code) {
        if (code == null || code.isEmpty() || PLAN_TIERS.contains(code)) {
            return code;
        }
        String model = table().get(code);
        return model == null ? code : model;
    }

    /**
     * 已加载的映射表，首次调用时读盘。
     *
     * @return 档位码 -> 模型标识
     */
    private static Map<String, String> table() {
        Map<String, String> cached = mapping;
        if (cached != null) {
            return cached;
        }
        synchronized (QoderModelCatalog.class) {
            if (mapping != null) {
                return mapping;
            }
            mapping = load();
            return mapping;
        }
    }

    /**
     * 读取并转换 Qoder 的档位显示名表。
     *
     * @return 档位码 -> 模型标识，读不到时返回空表
     */
    private static Map<String, String> load() {
        String json;
        try {
            if (!Files.isRegularFile(TEXTS_FILE)) {
                log.debug("[qoder] 档位显示名文件不存在: {}", TEXTS_FILE);
                return Collections.emptyMap();
            }
            json = Files.readString(TEXTS_FILE, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("[qoder] 读取档位显示名失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = ENTRY.matcher(json);
        while (matcher.find()) {
            String slug = slugify(matcher.group(2));
            if (!slug.isEmpty()) {
                result.putIfAbsent(matcher.group(1), slug);
            }
        }
        log.info("[qoder] 档位码映射加载完成: {} 项", result.size());
        return result;
    }

    /**
     * 显示名转模型标识：小写并把空白折成连字符。
     *
     * @param name 选择器显示名
     * @return 模型标识；无有效字符时返回空串
     */
    private static String slugify(String name) {
        String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        return slug.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
    }
}
