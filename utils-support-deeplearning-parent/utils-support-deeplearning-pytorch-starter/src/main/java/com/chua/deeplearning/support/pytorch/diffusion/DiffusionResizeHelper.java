package com.chua.deeplearning.support.pytorch.diffusion;

/**
 * Diffusion 条件图通用尺寸工具。
 * <p>将短边对齐到指定分辨率，再四舍五入到 64 的倍数。</p>
 *
 * @since 4.0.0.42
 */
public final class DiffusionResizeHelper {

    private DiffusionResizeHelper() {
    }

    /**
     * 计算对齐到 64 的高宽。
     *
     * @param h          原高
     * @param w          原宽
     * @param resolution 目标短边分辨率
     * @return [height, width]
     */
    public static int[] resize64(double h, double w, double resolution) {
        double k = resolution / Math.min(h, w);
        h *= k;
        w *= k;
        int height = (int) (Math.round(h / 64.0)) * 64;
        int width = (int) (Math.round(w / 64.0)) * 64;
        height = Math.max(64, height);
        width = Math.max(64, width);
        return new int[]{height, width};
    }
}
