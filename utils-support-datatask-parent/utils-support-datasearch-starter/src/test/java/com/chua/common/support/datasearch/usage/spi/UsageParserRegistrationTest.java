package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link UsageParser} 注册面测试。
 *
 * <p>落库侧按 {@code parser.name()} 建索引再逐个取数，两处都会静默丢数据源：
 * 一是实现类加载失败时只留在登记面、进不了实例面；二是两个不同实现报了同一个名字，
 * 后一个被 {@code putIfAbsent} 覆盖。两种情况都没有异常，只能靠本用例拦住。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
class UsageParserRegistrationTest {

    /**
     * 本机已接入的数据源下限
     */
    private static final int MIN_PARSERS = 40;

    /**
     * 登记了的实现必须全部实例化成功。
     */
    @Test
    @DisplayName("登记面与实例面数量一致")
    void everyDeclaredParserInstantiates() {
        ServiceProvider<UsageParser> provider = ServiceProvider.of(UsageParser.class);
        Map<String, Class<UsageParser>> declared = provider.listType();
        Map<String, UsageParser> registered = provider.list();
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, Class<UsageParser>> entry : declared.entrySet()) {
            if (!registered.containsKey(entry.getKey())) {
                dropped.add(entry.getKey() + "=" + entry.getValue().getName());
            }
        }
        assertTrue(declared.size() >= MIN_PARSERS,
                "只登记到 " + declared.size() + " 个实现，服务发现可能被改坏");
        assertEquals(List.of(), dropped, "这些解析器被静默丢弃: " + dropped);
    }

    /**
     * 名字非空且不同实现之间不重名。
     */
    @Test
    @DisplayName("解析器标识非空且不与其他实现重名")
    void parserNamesAreUniquePerImplementation() {
        Map<String, UsageParser> registered = ServiceProvider.of(UsageParser.class).list();
        assertFalse(registered.isEmpty(), "一个解析器都没注册");
        Map<Class<?>, String> nameByClass = new HashMap<>();
        for (UsageParser parser : registered.values()) {
            assertTrue(parser.name() != null && !parser.name().isBlank(),
                    parser.getClass().getSimpleName() + " 的 name() 为空");
            nameByClass.put(parser.getClass(), parser.name());
        }
        Map<String, Class<?>> classByName = new HashMap<>();
        for (Map.Entry<Class<?>, String> entry : nameByClass.entrySet()) {
            Class<?> earlier = classByName.put(entry.getValue(), entry.getKey());
            if (earlier != null) {
                fail(earlier.getSimpleName() + " 与 " + entry.getKey().getSimpleName()
                        + " 共用标识 " + entry.getValue() + "，落库时会被 putIfAbsent 丢掉");
            }
        }
    }

    /**
     * 广播遍历按实现类去重：同一实现挂两个别名时只应执行一次。
     */
    @Test
    @DisplayName("collect 不重复执行同一实现")
    void collectDeduplicatesAliasedImplementations() {
        List<UsageParser> collected = ServiceProvider.of(UsageParser.class).collect();
        long instances = collected.stream().map(UsageParser::getClass).distinct().count();
        assertEquals(instances, collected.size(),
                "同一实现因别名被重复装载，广播取数会重复计费");
    }
}
