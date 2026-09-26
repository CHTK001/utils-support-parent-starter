package com.chua.network.support.tshark.capture;

import javax.annotation.Nonnull;

/**
 * 可采集的网卡。
 *
 * <p>由 {@code tshark -D} 枚举，字段与 tshark 的输出一一对应。
 * 刻意不使用 JDK 的 {@code NetworkInterface} 代替：Windows 下 tshark 的网卡编号
 * （如 {@code 3}）与 JDK 枚举出的名称（{@code \Device\NPF_{GUID}}）无法直接互换，
 * 跨平台映射不可靠，交给 tshark 自身枚举才不会出现"选了 A 网抓的是 B 网"。</p>
 *
 * @param index       tshark 网卡编号，抓包时用 {@code -i <index>}
 * @param name        tshark 输出的网卡名（仅作展示）
 * @param description 网卡描述，通常含型号与厂商
 * @author CH
 * @since 4.0.0.42
 */
public record CaptureInterface(int index, String name, String description) {

    /**
     * 解析 {@code tshark -D} 的一行输出。
     *
     * <p>输出形如：</p>
     * <pre>
     * 1. \Device\NPF_{GUID}                    (Microsoft Hyper-V Network Adapter)
     * 3. eth0                                  (Intel I219-LM)
     * </pre>
     *
     * @param line 输出行
     * @return 网卡，无法解析时返回 {@code null}
     */
    @javax.annotation.Nullable
    public static CaptureInterface parse(@Nonnull String line) {
        String trimmed = line.strip();
        int dot = trimmed.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        String indexPart = trimmed.substring(0, dot).trim();
        int index;
        try {
            index = Integer.parseInt(indexPart);
        } catch (NumberFormatException e) {
            return null;
        }
        String rest = trimmed.substring(dot + 1).strip();
        String name = rest;
        String description = null;
        int parenthesis = rest.indexOf('(');
        if (parenthesis > 0 && rest.endsWith(")")) {
            name = rest.substring(0, parenthesis).strip();
            description = rest.substring(parenthesis + 1, rest.length() - 1).strip();
        }
        return new CaptureInterface(index, name, description);
    }

    @Override
    public String toString() {
        return index + ". " + name + (description == null ? "" : " (" + description + ")");
    }
}
