package com.chua.common.support.file.resource;

import java.util.Set;
import org.jspecify.annotations.NullUnmarked;

/**
 * 资源查找器接口。
 *
 * <p>每种资源协议（如 {@code classpath:}、{@code classpath*:}）对应一个 {@link ResourceFinder} 实现，
 * 负责根据输入的路径模式（支持 Ant 风格通配符）解析并返回匹配的资源集合。</p>
 *
 * <p>查找器实例由 {@link ResourceFlow} 根据协议前缀分发调用，本身不缓存结果，
 * 缓存策略由 {@link ResourceFlow} 统一管理。</p>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public interface ResourceFinder {

    /**
     * 根据路径模式查找资源。
     *
     * <p>路径模式可包含 Ant 风格通配符（{@code *}、{@code **}、{@code ?}），
     * 实现负责剥离协议前缀后进行匹配。</p>
     *
     * @param name 资源路径模式
     * @return 匹配到的资源集合，无匹配时返回空集合
     */
    Set<Resource> find(String name);
}
