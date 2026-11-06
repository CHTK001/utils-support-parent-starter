package com.chua.common.support.host;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 主机不可变信息快照：一次采集得到的全部一级 / 易变锚点取值。
 *
 * <p>本对象只承载数据，不含任何采集逻辑；采集由 {@link HostInfoProvider} 的
 * SPI 实现完成，判断「是否同一台机器」由 {@link HostIdentity} 完成。</p>
 *
 * <p>取值为空表示该平台拿不到这一项（例如容器内没有 DMI、精简内核无
 * {@code /sys/class/dmi}），而不是「取到了空字符串」——两者在判定时含义不同：
 * 前者不参与比对，后者参与比对且必然不相等。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class HostFacts {

    /**
     * 锚点取值表；缺失的锚点不出现在表中
     */
    private final Map<HostAnchor, String> values;

    /**
     * 采集该快照的通道名称（如 {@code windows-cim}、{@code linux-sysfs}）
     */
    private final String channel;

    /**
     * 创建快照。
     *
     * @param values  锚点取值表，可为 null
     * @param channel 采集通道名，可为 null
     */
    public HostFacts(Map<HostAnchor, String> values, String channel) {
        Map<HostAnchor, String> copy = new EnumMap<>(HostAnchor.class);
        if (values != null) {
            for (Map.Entry<HostAnchor, String> entry : values.entrySet()) {
                String normalized = HostIdentity.normalize(entry.getValue());
                if (entry.getKey() != null && !normalized.isEmpty()) {
                    copy.put(entry.getKey(), normalized);
                }
            }
        }
        this.values = Collections.unmodifiableMap(copy);
        this.channel = channel == null ? "" : channel;
    }

    /**
     * 创建空快照。
     *
     * @return 不含任何锚点的快照
     */
    public static HostFacts empty() {
        return new HostFacts(null, "");
    }

    /**
     * 取某个锚点的值。
     *
     * @param anchor 锚点
     * @return 归一后的取值；取不到时返回空串，不返回 null
     */
    public String value(HostAnchor anchor) {
        if (anchor == null) {
            return "";
        }
        String v = values.get(anchor);
        return v == null ? "" : v;
    }

    /**
     * 判断某个锚点是否有值。
     *
     * @param anchor 锚点
     * @return 有值返回 true
     */
    public boolean has(HostAnchor anchor) {
        return !value(anchor).isEmpty();
    }

    /**
     * 获取全部锚点取值（只读）。
     *
     * @return 不可变的锚点取值表
     */
    public Map<HostAnchor, String> values() {
        return values;
    }

    /**
     * 获取采集通道名。
     *
     * @return 通道名，可能为空串
     */
    public String channel() {
        return channel;
    }

    /**
     * 参与判定的一级锚点数量（有值的）。
     *
     * @return 数量
     */
    public int strongCount() {
        int count = 0;
        for (HostAnchor anchor : values.keySet()) {
            if (anchor.strong()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "HostFacts{channel=" + channel + ", strong=" + strongCount()
                + ", values=" + values + "}";
    }
}
