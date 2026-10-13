package com.chua.common.support.image.compression;

/**
 * 图片压缩等级。
 *
 * <p>等级越高代表允许投入的计算量越大、压缩率越高，代价是耗时上升。
 * 各引擎在实现时按自身管线特性把等级映射到具体参数（量化色数上限、deflate 迭代次数等）。
 *
 * <h3>参考管线映射</h3>
 * <table border="1">
 *   <caption>等级到管线参数的参考映射</caption>
 *   <tr><th>等级</th><th>PNG 量化色数上限</th><th>抖动</th><th>deflate 迭代</th></tr>
 *   <tr><td>L0</td><td>不量化（保持原色彩类型）</td><td>关</td><td>1</td></tr>
 *   <tr><td>L1</td><td>256</td><td>关</td><td>1</td></tr>
 *   <tr><td>L2</td><td>256</td><td>开</td><td>3</td></tr>
 *   <tr><td>L3</td><td>256</td><td>开</td><td>8</td></tr>
 *   <tr><td>L4</td><td>128</td><td>开</td><td>20</td></tr>
 *   <tr><td>L5</td><td>64</td><td>开</td><td>50</td></tr>
 *   <tr><td>L6</td><td>32</td><td>开</td><td>150</td></tr>
 * </table>
 *
 * @author CH
 * @since 4.0.0.43
 */
public enum CompressLevel {

    /**
     * L0：仅做无损 deflate 重压缩，不做任何有损量化。最快，像素完全不变。
     */
    L0(0),

    /**
     * L1：轻量有损量化（256 色，不抖动）+ 单次 deflate。适合批量流水线。
     */
    L1(1),

    /**
     * L2：标准量化（256 色，开启抖动）+ 少量 deflate 迭代。TinyPNG 默认档的近似值。
     */
    L2(2),

    /**
     * L3：256 色 + 抖动 + deflate 迭代 8 次。质量/体积平衡点。
     */
    L3(3),

    /**
     * L4：降到 128 色，进入明显的调色板收敛阶段。
     */
    L4(4),

    /**
     * L5：降到 64 色，体积优先，适合 UI 素材与图标。
     */
    L5(5),

    /**
     * L6：降到 32 色并对 deflate 做深度迭代，极限压缩，耗时长。
     */
    L6(6);

    /**
     * 等级序号，取值 0~6
     */
    private final int index;

    /**
     * 构造压缩等级
     *
     * @param index 等级序号
     */
    CompressLevel(int index) {
        this.index = index;
    }

    /**
     * 获取等级序号
     *
     * @return 等级序号，取值 0~6
     */
    public int getIndex() {
        return index;
    }

    /**
     * 把任意整数收敛到合法等级。
     *
     * @param index 原始等级序号，允许超出 0~6
     * @return 收敛后的压缩等级，不会返回 {@code null}
     */
    public static CompressLevel of(int index) {
        if (index <= L0.index) {
            return L0;
        }
        for (CompressLevel level : values()) {
            if (level.index == index) {
                return level;
            }
        }
        return L6;
    }

    /**
     * 把任意字符串收敛到合法等级。
     *
     * <p>接受 {@code "L3"}、{@code "l3"}、{@code "3"}、{@code "level3"} 等写法；
     * 无法解析时返回 {@link #L2}。
     *
     * @param value 原始等级文本，允许为 {@code null}
     * @return 收敛后的压缩等级，不会返回 {@code null}
     */
    public static CompressLevel of(String value) {
        if (value == null || value.isEmpty()) {
            return L2;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("level")) {
            normalized = normalized.substring("level".length());
        } else if (normalized.startsWith("l") && normalized.length() > 1) {
            normalized = normalized.substring(1);
        }
        try {
            return of(Integer.parseInt(normalized));
        } catch (NumberFormatException e) {
            return L2;
        }
    }
}