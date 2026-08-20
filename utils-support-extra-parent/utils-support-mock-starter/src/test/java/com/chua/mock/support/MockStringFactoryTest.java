package com.chua.mock.support;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockString SPI 发现与生成测试
 *
 * @author CH
 * @since 4.0.0.42
 */
class MockStringFactoryTest {

    /**
     * 校验 SPI 索引自动生成且全部生成器可被按名发现。
     */
    @Test
    void shouldDiscoverAllMockStringImplementations() {
        Set<String> extensions = ServiceProvider.of(MockString.class).getExtensions();
        assertFalse(extensions.isEmpty(), "应能发现至少一个 MockString 实现");
        assertTrue(extensions.contains("random"), "应包含默认的 random 实现");
        assertTrue(extensions.contains("name"), "应包含 name 实现");
        assertTrue(extensions.contains("phone"), "应包含 phone 实现");
    }

    /**
     * 校验常用生成器均可产出非空字符串数据。
     */
    @Test
    void shouldGenerateAllKindsOfString() {
        String[] names = {"random", "name", "phone", "email", "address", "company",
                "username", "password", "chinese", "letter", "digit", "date",
                "datetime", "ip", "city", "uuid", "en-name"};
        for (String name : names) {
            String value = MockStringFactory.generate(name);
            assertNotNull(value, name + " 应能生成数据");
            assertFalse(value.isEmpty(), name + " 不应生成空字符串");
        }
    }

    /**
     * 校验固定种子环境下生成结果可复现。
     */
    @Test
    void shouldReproduceWithSeededEnvironment() {
        String first = MockStringFactory.generate("random", MockEnvironment.of(20240820L));
        String second = MockStringFactory.generate("random", MockEnvironment.of(20240820L));
        assertEquals(first, second, "相同种子应生成相同结果");
    }

    /**
     * 校验指定长度生效。
     */
    @Test
    void shouldHonorConfiguredLength() {
        String value = MockStringFactory.generate("random", 12);
        assertEquals(12, value.length(), "固定长度应精确生效");
    }

    /**
     * 校验批量生成。
     */
    @Test
    void shouldGenerateList() {
        List<String> values = MockStringFactory.generateList("phone", 5);
        assertEquals(5, values.size(), "批量数量应一致");
        for (String value : values) {
            assertNotNull(value);
            assertEquals(11, value.length(), "手机号应为 11 位");
        }
    }

    /**
     * 校验未注册名称返回空。
     */
    @Test
    void shouldReturnNullForUnknownName() {
        assertFalse(MockStringFactory.isSupport("not-exists"));
        assertEquals(null, MockStringFactory.generate("not-exists"));
    }
}