package com.chua.common.support.network.protocol;

/**
 * MQTT 主题工具 —— 主题过滤器匹配与校验的唯一实现。
 *
 * <p>MQTT 3.1.1 / 5.0 主题层级规则：</p>
 * <ul>
 *   <li>{@code #} 匹配剩余所有层级，且必须独占一个层级并位于过滤器末尾；
 *   {@code sport/#} 同时匹配 {@code sport}（父级）与 {@code sport/a/b}</li>
 *   <li>{@code +} 只匹配一个层级，同样必须独占一个层级；{@code sport/+} 不匹配 {@code sport/a/b}</li>
 *   <li>层级按 {@code /} 切分且保留空层级，{@code order/} 与 {@code order} 是两个不同主题，
 *   {@code order/+} 匹配 {@code order/}（末级为空串）</li>
 *   <li>以 {@code $} 开头的系统主题不参与首层 {@code #}/{@code +} 匹配，
 *   但可以精确订阅</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MqttTopics {

    /**
     * 层级分隔符
     */
    private static final char SEPARATOR = '/';

    /**
     * 多层通配符
     */
    private static final String MULTI = "#";

    /**
     * 单层通配符
     */
    private static final String SINGLE = "+";

    /**
     * 系统主题前缀
     */
    private static final String SYSTEM_PREFIX = "$";

    /**
     * 禁止实例化
     */
    private MqttTopics() {
    }

    /**
     * 判断主题过滤器是否匹配具体主题。
     *
     * @param filter 主题过滤器（订阅侧，可含 {@code #}/{@code +}）
     * @param topic  实际主题（发布侧，不含通配符）
     * @return 匹配返回 true
     */
    public static boolean matches(String filter, String topic) {
        if (filter == null || topic == null || filter.isEmpty() || topic.isEmpty()) {
            return false;
        }
        if (filter.equals(topic)) {
            return true;
        }
        String[] fp = filter.split(String.valueOf(SEPARATOR), -1);
        String[] tp = topic.split(String.valueOf(SEPARATOR), -1);
        // 系统主题只允许精确订阅：首层为通配符的过滤器不得命中
        if (topic.startsWith(SYSTEM_PREFIX) && (MULTI.equals(fp[0]) || SINGLE.equals(fp[0]))) {
            return false;
        }
        for (int i = 0; i < fp.length; i++) {
            if (MULTI.equals(fp[i])) {
                return true;
            }
            if (i >= tp.length) {
                return false;
            }
            if (SINGLE.equals(fp[i])) {
                continue;
            }
            if (!fp[i].equals(tp[i])) {
                return false;
            }
        }
        return fp.length == tp.length;
    }

    /**
     * 校验主题过滤器是否合法 —— 通配符必须独占一个层级，且 {@code #} 只能出现在末尾。
     *
     * @param filter 主题过滤器
     * @return 合法返回 true
     */
    public static boolean isValidFilter(String filter) {
        if (filter == null || filter.isEmpty()) {
            return false;
        }
        String[] parts = filter.split(String.valueOf(SEPARATOR), -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.indexOf(MULTI.charAt(0)) >= 0 && !MULTI.equals(part)) {
                return false;
            }
            if (part.indexOf(SINGLE.charAt(0)) >= 0 && !SINGLE.equals(part)) {
                return false;
            }
            if (MULTI.equals(part) && i != parts.length - 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验发布主题是否合法 —— 发布主题不得包含通配符。
     *
     * @param topic 主题
     * @return 合法返回 true
     */
    public static boolean isValidTopic(String topic) {
        if (topic == null || topic.isEmpty()) {
            return false;
        }
        return topic.indexOf(MULTI.charAt(0)) < 0 && topic.indexOf(SINGLE.charAt(0)) < 0;
    }

    /**
     * 取有效投递 QoS —— 转发时按 {@code min(发布 QoS, 订阅授予 QoS)} 降级。
     *
     * @param publishQos    发布 QoS
     * @param grantedQos    订阅授予 QoS
     * @param maxSupportedQos 服务端支持的最大 QoS
     * @return 有效 QoS（0~2）
     */
    public static int effectiveQos(int publishQos, int grantedQos, int maxSupportedQos) {
        int pub = clamp(publishQos, 0, 2);
        int grant = clamp(grantedQos, 0, 2);
        int max = clamp(maxSupportedQos, 0, 2);
        int value = Math.min(pub, grant);
        return Math.min(value, max);
    }

    /**
     * 限制取值范围。
     *
     * @param value 值
     * @param min   下界
     * @param max   上界
     * @return 限制后的取值
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
