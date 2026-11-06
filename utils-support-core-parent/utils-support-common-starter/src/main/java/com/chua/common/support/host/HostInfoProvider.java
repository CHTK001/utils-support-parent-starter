package com.chua.common.support.host;

import com.chua.common.support.spi.annotations.Spi;

/**
 * 主机不可变信息采集 SPI。
 *
 * <p>需要执行外部命令（PowerShell / WMI / sysfs 读取 / ioreg）的采集一律通过本接口扩展，
 * 不在 {@link SystemUtils} 里内联命令文本：不同平台有不同命令语法，写法一旦分散，
 * 新增平台就要改动调用方。{@link HostInfoRouter} 按 {@link #supports()} 与
 * {@link #priority()} 选出当前平台最合适的实现。</p>
 *
 * <p>实现约定：
 * <ul>
 *   <li>只读。本接口不做任何写操作，修改硬件标识不在能力范围内；</li>
 *   <li>取不到的锚点<b>留空并在 {@link HostFacts} 中缺席</b>，不要塞 {@code "unknown"} /
 *       {@code "N/A"} / {@code "To be filled by O.E.M."}——这些占位串会让不同机器判成同一台；</li>
 *   <li>任何采集异常都必须吞掉并返回已取到的部分。一条命令失败不该让整份快照作废；</li>
 *   <li>批量取数。一次命令取回多个锚点，避免逐项启动进程（Windows 上每次 PowerShell
 *       冷启动约 200~400ms）。</li>
 * </ul>
 *
 * <h3>实现示例</h3>
 * <pre>{@code
 * @Spi(value = "demo", order = 100)
 * public class DemoHostInfoProvider implements HostInfoProvider {
 *     @Override
 *     public boolean supports() { return true; }
 *     @Override
 *     public HostFacts capture() {
 *         Map<HostAnchor, String> values = new EnumMap<>(HostAnchor.class);
 *         values.put(HostAnchor.SYSTEM_UUID, readSomething());
 *         return new HostFacts(values, "demo");
 *     }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi
public interface HostInfoProvider {

    /**
     * 采集通道名称，与 {@code @Spi} 名称一致。
     *
     * @return 通道名称
     */
    String name();

    /**
     * 当前运行环境是否可由本实现采集。
     *
     * <p>必须只做轻量判断（系统属性 + 可执行文件/文件存在性），不要在这里启动进程。</p>
     *
     * @return 支持返回 true
     */
    boolean supports();

    /**
     * 采集主机不可变信息。
     *
     * <p>本方法可能被并发调用，实现须无状态；耗时应在百毫秒量级，
     * 由调用方负责缓存（{@link HostIdentity} 默认缓存一次采集结果）。</p>
     *
     * @return 快照，永不为 null
     */
    HostFacts capture();

    /**
     * 优先级，值越大越优先。
     *
     * <p>同平台存在多个可用实现时（例如 Windows 上 CIM 与注册表兜底），
     * 取分高者。</p>
     *
     * @return 优先级
     */
    default int priority() {
        return 0;
    }
}
