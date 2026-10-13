package com.chua.image.support.compression.engine;

import com.chua.common.support.image.compression.DeflateEngine;
import com.chua.common.support.image.compression.PaletteQuantizer;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * deflate 引擎与调色板量化器的服务定位器。
 *
 * <p>两个 native 钩子都通过 SPI 解析，并<b>始终带有内置兜底</b>：
 * 无论一个外部实现都没注册，本类都能返回纯 Java 版本，保证压缩功能在任何运行时环境都可用。
 *
 * <h3>优先级规则</h3>
 * <ol>
 *   <li>注册了 {@code available() == true} 且不是纯 Java 兜底实现的扩展 → 优先使用</li>
 *   <li>否则 → 使用内置的 {@link JdkDeflateEngine} / {@link MedianCutQuantizer}</li>
 * </ol>
 *
 * <p>注意优先级看的是 {@code @Spi(order = ...)}，数值越大越优先。
 * 纯 Java 兜底实现注册的 order 为 -100，因此任何 native 实现只要正常注册就会自动顶替它。
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public final class CompressionEngines {

    /**
     * 纯 Java 兜底 deflate 引擎名称
     */
    private static final String JDK_DEFLATE = "jdk";

    /**
     * 纯 Java 兜底量化器名称
     */
    private static final String MEDIAN_CUT = "median-cut";

    /**
     * 工具类，禁止实例化
     */
    private CompressionEngines() {
    }

    /**
     * 解析 deflate 引擎
     *
     * @return deflate 引擎，不会返回 {@code null}
     */
    public static DeflateEngine deflate() {
        try {
            DeflateEngine candidate = ServiceProvider.of(DeflateEngine.class).getPriority();
            if (candidate != null && candidate.available() && !JDK_DEFLATE.equals(candidate.name())) {
                log.debug("[图片压缩] 使用扩展 deflate 引擎: {}", candidate.name());
                return candidate;
            }
        } catch (Exception e) {
            log.warn("[图片压缩] 解析 deflate 引擎失败，回退 JDK 实现: {}", e.getMessage());
        }
        return new JdkDeflateEngine();
    }

    /**
     * 解析调色板量化器
     *
     * @return 量化器，不会返回 {@code null}
     */
    public static PaletteQuantizer quantizer() {
        try {
            PaletteQuantizer candidate = ServiceProvider.of(PaletteQuantizer.class).getPriority();
            if (candidate != null && candidate.available() && !MEDIAN_CUT.equals(candidate.name())) {
                log.debug("[图片压缩] 使用扩展调色板量化器: {}", candidate.name());
                return candidate;
            }
        } catch (Exception e) {
            log.warn("[图片压缩] 解析调色板量化器失败，回退纯 Java 实现: {}", e.getMessage());
        }
        return new MedianCutQuantizer();
    }
}