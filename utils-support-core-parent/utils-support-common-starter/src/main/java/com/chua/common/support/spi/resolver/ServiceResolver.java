package com.chua.common.support.spi.resolver;

import com.chua.common.support.spi.definition.ServiceDefinition;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 服务解析器接口，负责从不同来源发现和解析 SPI 服务实现。
 *
 * <p>每种解析器对应一种服务发现策略：
 * <ul>
 *   <li>{@link SamePackageServiceResolver} — 同包扫描，搜索接口所在包及其子包</li>
 *   <li>{@link CustomServiceResolver} — 自定义配置文件，读取 {@code META-INF/extensions/} 配置</li>
 *   <li>{@code OsgiServiceResolver} — OSGI 框架，从 Felix 服务注册表中查找</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface ServiceResolver {

    /**
    * 解析指定 SPI 接口的所有服务实现。
    *
    * @param type        SPI 接口类型
    * @param classLoader 类加载器
    * @return 服务定义列表，未找到时返回空列表
    */
    List<ServiceDefinition> resolve(@Nonnull Class<?> type, @Nullable ClassLoader classLoader);

    /**
    * 判断当前解析器是否支持动态服务。
    * <p>
    * 动态服务表示服务实例可以在运行时动态注册和注销（如 OSGI 服务），
    * 静态服务则是通过类路径扫描或配置文件一次性加载的。
    * </p>
    *
    * @return 动态返回 {@code true}，静态返回 {@code false}（默认）
    */
    default boolean isDynamic() {
        return false;
    }
}
