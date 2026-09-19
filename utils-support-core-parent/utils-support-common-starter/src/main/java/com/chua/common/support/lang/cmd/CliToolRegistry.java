package com.chua.common.support.lang.cmd;

import com.chua.common.support.spi.ServiceProvider;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI 工具注册表，汇总通过 SPI 注册的所有 {@link CliTool} 实例，并提供按名查找。
 *
 * <p>启动时通过 SPI 一次性加载所有实现，之后按工具名建立索引。
 * 运行期也可以用 {@link #register(CliTool)} 手动注册自定义工具，
 * 便于在代码里装配那些不适合做成 SPI 的场景（如路径需要动态计算）。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CliToolRegistry registry = CliToolRegistry.getInstance();
 *
 * registry.find("tshark")
 *         .filter(CliTool::isAvailable)
 *         .ifPresent(tool -> System.out.println(tool.execute("--version").getStdout()));
 *
 * // 查看所有当前可用的 CLI 工具
 * registry.available().forEach(tool -> System.out.println(tool.name() + " " + tool.version()));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CliToolRegistry {

    /** 单例实例 */
    private static final CliToolRegistry INSTANCE = new CliToolRegistry();

    /** 工具名到工具实例的索引 */
    private final Map<String, CliTool> tools = new ConcurrentHashMap<>();

    /**
     * 创建注册表并加载 SPI 实现
     */
    private CliToolRegistry() {
        loadFromSpi();
    }

    /**
     * 获取注册表单例。
     *
     * @return 注册表实例
     */
    @Nonnull
    public static CliToolRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 从 SPI 加载所有 CLI 工具实现。
     *
     * <p>加载失败的实现会被跳过，不影响其他工具的注册。</p>
     */
    private void loadFromSpi() {
        try {
            ServiceProvider<CliTool> provider = ServiceProvider.of(CliTool.class);
            provider.collect(tool -> {
                if (tool != null) {
                    tools.putIfAbsent(tool.name(), tool);
                }
            });
        } catch (Exception ignored) {
            // SPI 不可用时降级为空注册表，仍可通过 register 手动注册
        }
    }

    /**
     * 注册一个 CLI 工具，同名工具只保留先注册的那个。
     *
     * @param tool 工具实例
     * @return this，便于链式注册
     */
    @Nonnull
    public CliToolRegistry register(@Nonnull CliTool tool) {
        if (tool != null) {
            tools.putIfAbsent(tool.name(), tool);
        }
        return this;
    }

    /**
     * 按工具名查找。
     *
     * @param name 工具名，如 {@code tshark}
     * @return 工具实例，未注册时返回 {@link Optional#empty()}
     */
    @Nonnull
    public Optional<CliTool> find(@Nonnull String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 按工具名查找并转换为指定类型。
     *
     * @param name 工具名
     * @param type 期望的类型
     * @param <T>  工具类型
     * @return 工具实例，未注册或类型不匹配时返回 {@link Optional#empty()}
     */
    @Nonnull
    public <T extends CliTool> Optional<T> find(@Nonnull String name, @Nonnull Class<T> type) {
        return find(name).filter(type::isInstance).map(type::cast);
    }

    /**
     * 判断指定名称的工具是否已注册且在当前环境可用。
     *
     * @param name 工具名
     * @return 已注册且可执行文件存在时返回 true
     */
    public boolean isAvailable(@Nonnull String name) {
        return find(name).map(CliTool::isAvailable).orElse(false);
    }

    /**
     * 获取所有已注册的工具。
     *
     * @return 工具集合的不可变视图
     */
    @Nonnull
    public Collection<CliTool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 获取所有当前环境可用的工具。
     *
     * <p>会触发每个工具的定位与版本探测，未安装的工具不在结果中。</p>
     *
     * @return 可用工具列表
     */
    @Nonnull
    public List<CliTool> available() {
        List<CliTool> result = new ArrayList<>();
        for (CliTool tool : tools.values()) {
            if (tool.isAvailable()) {
                result.add(tool);
            }
        }
        return result;
    }

    /**
     * 获取所有已注册工具的名称。
     *
     * @return 工具名列表
     */
    @Nonnull
    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /**
     * 清空注册表，主要用于测试场景。
     */
    public void clear() {
        tools.clear();
    }
}
