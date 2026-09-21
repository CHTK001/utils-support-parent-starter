package com.chua.common.support.datasearch.usage.spi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link QoderModelCatalog} 档位码还原测试。
 *
 * <p>转录里的 {@code message.model} 是内部档位码，落库前必须换成能与定价目录对齐的
 * 模型标识；还原不了时要原样返回，不能改写成猜测值。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
class QoderModelCatalogTest {

    /**
     * 重铺档位显示名夹具
     */
    @BeforeEach
    void setUp() {
        QoderFixture.reset();
    }

    /**
     * 档位码按显示名转模型标识，派生键不参与映射。
     */
    @Test
    @DisplayName("档位码还原为模型标识")
    void resolvesTierCodeToModelSlug() {
        assertEquals(QoderFixture.MODEL_QWEN_MAX, QoderModelCatalog.resolve(QoderFixture.TIER_QWEN_MAX));
        assertEquals(QoderFixture.MODEL_QWEN_FLASH, QoderModelCatalog.resolve(QoderFixture.TIER_QWEN_FLASH));
    }

    /**
     * 套餐档位与未收录档位保持原值。
     */
    @Test
    @DisplayName("计费档位与未收录档位原样返回")
    void keepsUnmappedCodesUnchanged() {
        assertEquals("lite", QoderModelCatalog.resolve("lite"), "lite 是计费档位，还原成显示名反而混淆模型");
        assertEquals("auto", QoderModelCatalog.resolve("auto"));
        assertEquals("tier-not-in-catalog", QoderModelCatalog.resolve("tier-not-in-catalog"));
    }

    /**
     * 空值不报错。
     */
    @Test
    @DisplayName("空档位码返回空")
    void toleratesBlankCode() {
        assertNull(QoderModelCatalog.resolve(null));
        assertEquals("", QoderModelCatalog.resolve(""));
    }
}
