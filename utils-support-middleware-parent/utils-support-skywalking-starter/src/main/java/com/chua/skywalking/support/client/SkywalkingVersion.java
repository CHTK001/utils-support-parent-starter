package com.chua.skywalking.support.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyWalking OAP 版本。
 * <p>
 * 为什么需要它：GraphQL 的字段校验是<b>整条查询全有或全无</b> —— 只要查询里有一个
 * 字段在目标 OAP 的 schema 里不存在，整条查询就报错，拿不到任何数据（不是「少个字段」）。
 * 而 {@code AlarmMessage.name}（10.2.0）、{@code Node.layers}（10.0.0）、
 * {@code queryAlarms} 入口（11.0.0）这些能力都是逐版本长出来的。
 * </p>
 * <p>
 * 靠「先发一次必然报错的请求，再 catch 回落」在可用性上没问题，但每个这类探测都要
 * 付一次往返，且 OAP 侧会把这些失败当成真实查询错误写进日志，把真正的故障淹没。
 * 因此这里把版本解析出来，按版本直接选对的那条语句。
 * </p>
 * <p>
 * 版本号形如 {@code 10.2.0}，也见过带后缀的发行版标记；解析不出时按<b>最低</b>能力
 * 处理（{@link #rank()} 为 0），宁可少发一个 11.x 专有查询，也不要在老 OAP 上
 * 引发一串校验错误。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class SkywalkingVersion {

    /**
     * 主版本号提取
     */
    private static final Pattern MAJOR = Pattern.compile("^\\s*v?(\\d+)");

    /**
     * 未知版本（能力取最低）
     */
    public static final SkywalkingVersion UNKNOWN = new SkywalkingVersion(0, 0, 0, false);

    private final int major;
    private final int minor;
    private final int build;
    private final boolean known;

    private SkywalkingVersion(int major, int minor, int build, boolean known) {
        this.major = major;
        this.minor = minor;
        this.build = build;
        this.known = known;
    }

    /**
     * 解析版本号。
     * <p>
     * 只认得出主版本就够用（下面所有能力判断都只按主次版本比较），但小版本也要留着：
     * {@code AlarmMessage.name} 是 10.2.0 才有的，10.0/10.1 没有。
     * </p>
     *
     * @param text OAP {@code query { version }} 的返回，可为 null
     * @return 版本对象，解析失败返回 {@link #UNKNOWN}
     */
    public static SkywalkingVersion parse(String text) {
        if (text == null || text.isBlank() || "unknown".equalsIgnoreCase(text.trim())) {
            return UNKNOWN;
        }
        Matcher matcher = MAJOR.matcher(text);
        if (!matcher.find()) {
            return UNKNOWN;
        }
        int[] parts = {0, 0, 0};
        String[] segments = text.trim().replaceFirst("^\\s*v?", "").split("\\.");
        for (int i = 0; i < parts.length && i < segments.length; i++) {
            String digits = segments[i].replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) {
                break;
            }
            try {
                parts[i] = Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                break;
            }
        }
        return new SkywalkingVersion(parts[0], parts[1], parts[2], parts[0] > 0);
    }

    /**
     * 是否解析出了有效版本
     *
     * @return 是否已知
     */
    public boolean known() {
        return known;
    }

    /**
     * 版本比较
     *
     * @param otherMajor 目标主版本
     * @param otherMinor 目标次版本
     * @return 当前版本是否「不低于」目标版本；版本未知时恒为 false
     */
    public boolean atLeast(int otherMajor, int otherMinor) {
        if (!known) {
            return false;
        }
        if (major != otherMajor) {
            return major > otherMajor;
        }
        return minor >= otherMinor;
    }

    /**
     * 是否支持 {@code getServiceTopologyByName} / {@code getServicesTopologyByNames}
     * （11.0.0 引入，9.x / 10.x 只能按 serviceId 查）
     *
     * @return 是否支持
     */
    public boolean supportsTopologyByName() {
        return atLeast(11, 0);
    }

    /**
     * 是否支持 {@code queryAlarms(condition)}
     * （11.0.0 引入；更早只有 {@code getAlarm}）
     *
     * @return 是否支持
     */
    public boolean supportsQueryAlarms() {
        return atLeast(11, 0);
    }

    /**
     * 拓扑节点是否带 {@code layers} 字段（10.0.0 引入）
     *
     * @return 是否支持
     */
    public boolean supportsNodeLayers() {
        return atLeast(10, 0);
    }

    /**
     * 告警消息是否直接带 {@code name}（实体名，10.2.0 引入）
     *
     * @return 是否支持
     */
    public boolean supportsAlarmName() {
        return atLeast(10, 2);
    }

    /**
     * 告警消息是否带 {@code recoveryTime}（11.0.0 引入）
     *
     * @return 是否支持
     */
    public boolean supportsAlarmRecoveryTime() {
        return atLeast(11, 0);
    }

    @Override
    public String toString() {
        return known ? major + "." + minor + "." + build : "unknown";
    }
}
