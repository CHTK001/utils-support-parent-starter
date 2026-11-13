// [P3C A20 豁免] A20/A30/A31: 测试夹具与断言含魔法值场景，豁免。
package com.chua.common.support.spi;

import com.chua.common.support.collection.SortedArrayList;
import com.chua.common.support.collection.SortedList;
import com.chua.common.support.spi.definition.ServiceDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 服务定义查找的快路径回归。
 *
 * <p>查找是 SPI 热路径（协议选实现、Server/Filter 枚举都走它）。原先每次都全表扫描，
 * 即使 {@code definitions} 的 key 就是服务名。改成先按 key 直取、未命中再扫描后，
 * 必须保证：大小写不同仍能命中、describeType 过滤仍然生效。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class ServiceDefinitionFinderTest {

    /**
     * 构造一条服务定义。
     *
     * @param name         服务名
     * @param describeType describe 类型
     * @return 定义
     */
    private static ServiceDefinition definition(String name, String describeType) {
        ServiceDefinition serviceDefinition = new ServiceDefinition();
        serviceDefinition.setName(name);
        serviceDefinition.setDescribeType(describeType);
        return serviceDefinition;
    }

    /**
     * key 大写、查找名小写时仍能命中（快路径要兼容大小写）。
     */
    @Test
    @DisplayName("查找：key 与查询名大小写不同也能命中")
    void matchesIgnoringCase() {
        Map<String, SortedList<ServiceDefinition>> map = new ConcurrentHashMap<>();
        SortedList<ServiceDefinition> list = new SortedArrayList<>(ServiceDefinition.COMPARATOR);
        ServiceDefinition tcp = definition("tcp-proxy", "TCP");
        list.add(tcp);
        map.put("TCP-PROXY", list);

        ServiceDefinitionFinder finder = new ServiceDefinitionFinder(map, null);
        assertSame(tcp, finder.getServiceDefinition("tcp-proxy"),
                "快路径未按 key 命中，说明仍在退化/扫不到");
    }

    /**
     * describeType 过滤不能被快路径绕过。
     */
    @Test
    @DisplayName("查找：describeType 不匹配时不得命中")
    void respectsDescribeType() {
        Map<String, SortedList<ServiceDefinition>> map = new ConcurrentHashMap<>();
        SortedList<ServiceDefinition> list = new SortedArrayList<>(ServiceDefinition.COMPARATOR);
        ServiceDefinition tcp = definition("tcp-proxy", "TCP");
        list.add(tcp);
        map.put("TCP-PROXY", list);

        ServiceDefinitionFinder finder = new ServiceDefinitionFinder(map, null);
        ServiceDefinition found = finder.getServiceDefinition("HTTP:tcp-proxy");
        assertSame(ServiceDefinitionFinder.DEFAULT_DEFINITION, found,
                "describeType=HTTP 不应命中 describeType=TCP 的实现（快路径绕过了类型过滤）");
    }

    /**
     * 未命中会被缓存；注册后必须能通过清缓存恢复命中。
     */
    @Test
    @DisplayName("查找：未命中缓存可被注册清空")
    void missCacheIsClearedByRegistration() {
        Map<String, SortedList<ServiceDefinition>> map = new ConcurrentHashMap<>();
        ServiceDefinitionFinder finder = new ServiceDefinitionFinder(map, null);
        assertSame(ServiceDefinitionFinder.DEFAULT_DEFINITION, finder.getServiceDefinition("nacos"),
                "空表时应当查不到");

        SortedList<ServiceDefinition> list = new SortedArrayList<>(ServiceDefinition.COMPARATOR);
        ServiceDefinition nacos = definition("nacos", null);
        list.add(nacos);
        map.put("NACOS", list);

        assertSame(ServiceDefinitionFinder.DEFAULT_DEFINITION, finder.getServiceDefinition("nacos"),
                "未清缓存前应仍返回空定义（这正说明缓存生效）");

        finder.clearMissCache();
        assertSame(nacos, finder.getServiceDefinition("nacos"),
                "清缓存后应命中新注册的实现");
    }
}
