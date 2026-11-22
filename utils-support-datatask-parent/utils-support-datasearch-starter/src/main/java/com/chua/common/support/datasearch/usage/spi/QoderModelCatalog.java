package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.collection.wrap.Wraps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import com.chua.common.support.utils.StringUtils;

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
 * 档位表按显示名文件路径缓存（每个主目录一份）；文件缺失或格式变化时退化为恒等映射。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class QoderModelCatalog {

    /**
     * 按显示名文件路径缓存的档位码 -> 模型标识表（每个主目录一份，加载失败时为空表）。
     */
    private static final ConcurrentMap<Path, Map<String, String>> MAPPINGS = new ConcurrentHashMap<>();

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

    private QoderModelCatalog() {
    }

    /**
     * 把 Qoder 档位码还原为模型标识（按当前用户主目录解析）。
     *
     * @param code 转录中记录的档位码，允许为 空
     * @return 模型标识；档位码为空、属计费档位或目录未收录时返回原值
     */
    public static String resolve(String code) {
        return resolve(code, Paths.get(System.getProperty("user.home", ".")));
    }

    /**
     * 把 Qoder 档位码还原为模型标识。
     *
     * @param code 转录中记录的档位码，允许为 空
     * @param home 当前解析根目录（其下 {@code .qoder/.auth/dynamic-texts.json} 为显示名文件）
     * @return 模型标识；档位码为空、属计费档位或目录未收录时返回原值
     */
    public static String resolve(String code, Path home) {
        if (StringUtils.isEmpty(code) || PLAN_TIERS.contains(code)) {
            return code;
        }
        Path textsFile = home.resolve(".qoder").resolve(".auth").resolve("dynamic-texts.json");
        String model = MAPPINGS.computeIfAbsent(textsFile, QoderModelCatalog::load).get(code);
        return model == null ? code : model;
    }

    /**
     * 读取并转换 Qoder 的档位显示名表。
     *
     * @param textsFile 显示名文件路径
     * @return 档位码 -> 模型标识，读不到时返回空表
     */
    private static Map<String, String> load(Path textsFile) {
        String json;
        try {
            if (!Files.isRegularFile(textsFile)) {
                log.debug("[qoder] 档位显示名文件不存在: {}", textsFile);
                return Collections.emptyMap();
            }
            json = Files.readString(textsFile, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("[qoder] 读取档位显示名失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
        Map<String, String> result = Wraps.linkedMap();
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
