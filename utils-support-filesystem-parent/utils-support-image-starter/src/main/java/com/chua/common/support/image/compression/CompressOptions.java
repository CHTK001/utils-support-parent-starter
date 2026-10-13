package com.chua.common.support.image.compression;

/**
 * 图片压缩参数。
 *
 * <p>不可变记录类。构造时会把未显式指定的字段按 {@link CompressLevel} 推导为具体值，
 * 因此实例一经创建，各字段均为确定的实际参数，压缩引擎内部无需再做等级映射。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code level} — 压缩等级，所有派生字段的推导依据</li>
 *   <li>{@code maxColors} — 调色板色数上限，{@link CompressLevel#L0} 下强制为 0（不量化）</li>
 *   <li>{@code dithering} — Floyd-Steinberg 抖动开关</li>
 *   <li>{@code iterations} — deflate 迭代次数，越大压缩率越高、耗时越长</li>
 *   <li>{@code quality} — 有损格式（JPEG 等）的质量，取值 1~100</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 用默认等级压缩 PNG，保留元数据
 * CompressOptions png = CompressOptions.of("png").keepMetadata(true);
 *
 * // 极限压缩并转成 JPEG
 * CompressOptions toJpeg = CompressOptions.of("jpeg")
 *         .level(CompressLevel.L5)
 *         .quality(82);
 * }</pre>
 *
 * @param level           压缩等级
 * @param maxColors       调色板色数上限，0 表示不量化
 * @param dithering       是否开启 Floyd-Steinberg 抖动
 * @param iterations      deflate 迭代次数
 * @param quality         有损格式质量，取值 1~100
 * @param targetFormat    目标输出格式，{@code null} 表示与输入格式一致
 * @param keepMetadata    是否保留元数据块（PNG 的 tEXt/iTXt 等、JPEG 的 APP 段）
 * @param discardWhenLarger 压缩后体积不小于原始体积时是否丢弃结果、返回原始字节
 * @param width           目标宽度，小于等于 0 表示不缩放
 * @param height          目标高度，小于等于 0 表示不缩放
 * @author CH
 * @since 4.0.0.43
 */
public record CompressOptions(
        CompressLevel level,
        int maxColors,
        boolean dithering,
        int iterations,
        int quality,
        String targetFormat,
        boolean keepMetadata,
        boolean discardWhenLarger,
        int width,
        int height) {

    /**
     * 默认压缩等级
     */
    public static final CompressLevel DEFAULT_LEVEL = CompressLevel.L2;

    /**
     * 构造压缩参数并补全派生字段。
     *
     * <p>派生规则（等级 → 具体参数）：
     * <pre>
     * L0 → maxColors=0,  dithering=false, iterations=1,  quality=100
     * L1 → maxColors=256,dithering=false, iterations=1,  quality=92
     * L2 → maxColors=256,dithering=true, iterations=3,  quality=85
     * L3 → maxColors=256,dithering=true, iterations=8,  quality=78
     * L4 → maxColors=128,dithering=true, iterations=20, quality=72
     * L5 → maxColors=64, dithering=true, iterations=50, quality=65
     * L6 → maxColors=32, dithering=true, iterations=150,quality=60
     * </pre>
     *
     * @throws IllegalArgumentException 当显式指定的质量不在 1~100 范围内时
     */
    public CompressOptions {
        if (level == null) {
            level = DEFAULT_LEVEL;
        }
        if (maxColors <= 0) {
            maxColors = deriveColors(level);
        }
        if (level == CompressLevel.L0) {
            // L0 语义是「不量化」，强制覆盖任何显式传入的色数
            maxColors = 0;
        } else if (maxColors > 256) {
            maxColors = 256;
        }
        if (iterations <= 0) {
            iterations = deriveIterations(level);
        }
        if (quality <= 0) {
            quality = deriveQuality(level);
        } else if (quality > 100) {
            throw new IllegalArgumentException("quality 必须在 1~100 之间，实际=" + quality);
        }
        if (targetFormat != null && targetFormat.isEmpty()) {
            targetFormat = null;
        }
    }

    /**
     * 按等级推导调色板色数上限
     *
     * @param level 压缩等级
     * @return 建议的色数上限
     */
    private static int deriveColors(CompressLevel level) {
        return switch (level) {
            case L0 -> 0;
            case L1, L2, L3 -> 256;
            case L4 -> 128;
            case L5 -> 64;
            case L6 -> 32;
        };
    }

    /**
     * 按等级推导 deflate 迭代次数
     *
     * @param level 压缩等级
     * @return 建议的迭代次数
     */
    private static int deriveIterations(CompressLevel level) {
        return switch (level) {
            case L0, L1 -> 1;
            case L2 -> 3;
            case L3 -> 8;
            case L4 -> 20;
            case L5 -> 50;
            case L6 -> 150;
        };
    }

    /**
     * 按等级推导有损格式质量
     *
     * @param level 压缩等级
     * @return 建议质量，取值 1~100
     */
    private static int deriveQuality(CompressLevel level) {
        return switch (level) {
            case L0 -> 100;
            case L1 -> 92;
            case L2 -> 85;
            case L3 -> 78;
            case L4 -> 72;
            case L5 -> 65;
            case L6 -> 60;
        };
    }

    /**
     * 创建压缩参数
     *
     * @param format 输入图片格式，如 {@code png}；允许为 {@code null}（由引擎自动探测）
     * @return 使用默认等级的压缩参数
     */
    public static CompressOptions of(String format) {
        return new CompressOptions(DEFAULT_LEVEL, 0, false, 0, 0, format, false, true, 0, 0);
    }

    /**
     * 创建压缩参数
     *
     * @param format 输入图片格式
     * @param level  压缩等级
     * @return 压缩参数
     */
    public static CompressOptions of(String format, CompressLevel level) {
        return new CompressOptions(level, 0, false, 0, 0, format, false, true, 0, 0);
    }

    /**
     * 复制并设置压缩等级
     *
     * @param newLevel 新的压缩等级
     * @return 新的压缩参数实例
     */
    public CompressOptions level(CompressLevel newLevel) {
        return new CompressOptions(newLevel, 0, false, 0, 0, targetFormat, keepMetadata,
                discardWhenLarger, width, height);
    }

    /**
     * 复制并设置调色板色数上限
     *
     * @param colors 色数上限，取值 2~256；小于等于 0 表示由等级推导
     * @return 新的压缩参数实例
     */
    public CompressOptions colors(int colors) {
        return new CompressOptions(level, colors, dithering, iterations, quality, targetFormat,
                keepMetadata, discardWhenLarger, width, height);
    }

    /**
     * 复制并设置抖动开关
     *
     * @param enable 是否开启 Floyd-Steinberg 抖动
     * @return 新的压缩参数实例
     */
    public CompressOptions dithering(boolean enable) {
        return new CompressOptions(level, maxColors, enable, iterations, quality, targetFormat,
                keepMetadata, discardWhenLarger, width, height);
    }

    /**
     * 复制并设置 deflate 迭代次数
     *
     * @param times 迭代次数，小于等于 0 表示由等级推导
     * @return 新的压缩参数实例
     */
    public CompressOptions iterations(int times) {
        return new CompressOptions(level, maxColors, dithering, times, quality, targetFormat,
                keepMetadata, discardWhenLarger, width, height);
    }

    /**
     * 复制并设置有损格式质量
     *
     * @param value 质量，取值 1~100；小于等于 0 表示由等级推导
     * @return 新的压缩参数实例
     * @throws IllegalArgumentException 当质量超出 1~100 时
     */
    public CompressOptions quality(int value) {
        return new CompressOptions(level, maxColors, dithering, iterations, value, targetFormat,
                keepMetadata, discardWhenLarger, width, height);
    }

    /**
     * 复制并设置目标输出格式
     *
     * @param format 目标格式，如 {@code webp}；{@code null} 表示与输入一致
     * @return 新的压缩参数实例
     */
    public CompressOptions targetFormat(String format) {
        return new CompressOptions(level, maxColors, dithering, iterations, quality, format,
                keepMetadata, discardWhenLarger, width, height);
    }

    /**
     * 复制并设置是否保留元数据
     *
     * @param keep 是否保留
     * @return 新的压缩参数实例
     */
    public CompressOptions keepMetadata(boolean keep) {
        return new CompressOptions(level, maxColors, dithering, iterations, quality, targetFormat,
                keep, discardWhenLarger, width, height);
    }

    /**
     * 复制并设置「压缩后更大则丢弃」
     *
     * @param discard 是否丢弃
     * @return 新的压缩参数实例
     */
    public CompressOptions discardWhenLarger(boolean discard) {
        return new CompressOptions(level, maxColors, dithering, iterations, quality, targetFormat,
                keepMetadata, discard, width, height);
    }

    /**
     * 复制并设置目标尺寸
     *
     * <p>宽高均为正数时按给定尺寸缩放；仅指定其一时按等比缩放；均为非正数时不缩放。
     *
     * @param targetWidth  目标宽度
     * @param targetHeight 目标高度
     * @return 新的压缩参数实例
     */
    public CompressOptions size(int targetWidth, int targetHeight) {
        return new CompressOptions(level, maxColors, dithering, iterations, quality, targetFormat,
                keepMetadata, discardWhenLarger, targetWidth, targetHeight);
    }

    /**
     * 判断当前参数是否会触发有损调色板量化
     *
     * @return 需要量化返回 {@code true}
     */
    public boolean isQuantized() {
        return maxColors > 0;
    }
}