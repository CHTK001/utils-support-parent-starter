package com.chua.common.support.utils;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 图片转提示词生成器：分析图片的画幅、色彩、明暗、对比与细节特征，
 * 生成可直接复制到主流 AI 绘图 / 视频工具的提示词，并给出中文的画面拆解
 * （画幅、色彩、光线、情绪、媒介与镜头建议）。
 *
 * <p>对标 miniwebtool「AI图片转提示词生成器」，覆盖其三种使用方式：</p>
 * <ul>
 *   <li>复刻模式：{@link #generate(byte[])}，按图片特征生成整套提示词；</li>
 *   <li>换主体模式：{@link #generate(byte[], String)}，保留画面风格，替换主体描述；</li>
 *   <li>创意模式：{@link #generateFromIdea(String)}，无图片、仅凭一句话构想生成提示词。</li>
 * </ul>
 *
 * <p>输出包含：通用自然语言提示词（ChatGPT / Gemini / Copilot 等）、Midjourney 提示词
 * （带 {@code --ar} 画幅参数）、Stable Diffusion / Flux 提示词（含负面提示词）、
 * 视频生成提示词（Veo / Kling / Seedance 等）、短提示词，以及中文画面拆解分析。</p>
 *
 * <p><b>实现口径与限制：</b>与在线版基于视觉大模型不同，本实现为纯 JDK 统计特征方案，
 * 零第三方依赖、可离线运行；能稳定描述「长什么样」（画幅 / 主色板 / 明暗 / 饱和度 /
 * 对比度 / 细节密度），但<b>无法语义识别「画的是什么」</b>——主体信息请通过
 * {@code subjectHint} 传入或在提示词中人工补充。图片解码复用 {@link BufferedImageUtils}，
 * 支持 JPEG / PNG / BMP / GIF；WebP、HEIC、TIFF 等需先转码。
 * 所有推断（光线、媒介、情绪、镜头）均为启发式参考，不代表唯一解释。</p>
 */
public final class ImagePromptUtils {

    /** 采样分析的最大边长，超过则等比缩小后统计。 */
    private static final int SAMPLE_MAX = 160;

    /** 未提供主体时的占位描述。 */
    private static final String DEFAULT_SUBJECT = "a scene";

    /** 常用画幅比例（宽:高），用于把任意尺寸归一到可读比例。 */
    private static final int[][] COMMON_RATIOS = {
            {1, 1}, {4, 3}, {3, 4}, {3, 2}, {2, 3}, {16, 9},
            {9, 16}, {21, 9}, {5, 4}, {4, 5}, {2, 1}, {1, 2}
    };

    /** 12 个色相区间的英文名（每 30° 一档），用于英文提示词。 */
    private static final String[] HUE_EN = {
            "red", "orange", "yellow", "yellow-green", "green", "cyan",
            "blue", "indigo", "purple", "magenta", "rose", "red"
    };

    /** 12 个色相区间的中文名，用于中文分析。 */
    private static final String[] HUE_ZH = {
            "红", "橙", "黄", "黄绿", "绿", "青", "蓝", "靛", "紫", "品红", "玫红", "红"
    };

    private ImagePromptUtils() {
    }

    /**
     * 复刻模式：分析图片并生成整套提示词（通用 / Midjourney / SD / 视频 / 短提示词）。
     *
     * @param image 图像文件字节（JPEG / PNG / BMP / GIF）
     * @return 提示词报告；格式不支持或解码失败时 {@code supported=false} 并附说明
     */
    public static PromptReport generate(byte[] image) {
        return generate(image, null);
    }

    /**
     * 换主体模式：保留图片的画面风格（色彩 / 光线 / 构图），把主体替换为指定描述。
     *
     * @param image       图像文件字节
     * @param subjectHint 主体或画面构想描述，如 “a dog riding a skateboard”；为空时使用通用占位
     * @return 提示词报告
     */
    public static PromptReport generate(byte[] image, String subjectHint) {
        if (image == null || image.length == 0) {
            return PromptReport.unsupported("图像数据为空");
        }
        BufferedImage source;
        try {
            source = BufferedImageUtils.toBufferedImage(image);
        } catch (Exception e) {
            return PromptReport.unsupported("图像解码失败：" + e.getClass().getSimpleName());
        }
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return PromptReport.unsupported("当前环境无法解码该图像格式（如 WebP/HEIC/TIFF），请先转码为 JPEG/PNG");
        }
        if ((long) source.getWidth() * source.getHeight() > 80_000_000L) {
            return PromptReport.unsupported("图像像素规模过大（超过 8000 万像素），请先缩小后重试");
        }
        Features features = analyzeFeatures(source, hasTransparency(source));
        return buildReport(source.getWidth(), source.getHeight(), features, subjectHint);
    }

    /**
     * 创意模式：不依赖图片，仅凭一句话构想生成同一套提示词；
     * 明暗、色调等画面特征由构想中的关键词推断（如 夜/night、夕阳/sunset、极简/minimal）。
     *
     * @param idea 画面构想描述
     * @return 提示词报告；描述为空时 {@code supported=false}
     */
    public static PromptReport generateFromIdea(String idea) {
        if (StringUtils.isBlank(idea)) {
            return PromptReport.unsupported("创意描述为空");
        }
        return buildReport(0, 0, ideaFeatures(idea), idea);
    }

    /**
     * 便捷方法：仅返回通用提示词文本；不支持/失败时返回空串。
     *
     * @param image 图像文件字节
     * @return 通用提示词
     */
    public static String toPrompt(byte[] image) {
        PromptReport report = generate(image);
        return report.supported() ? report.generalPrompt() : "";
    }

    // ==== 画面特征统计 ====

    /**
     * 画面统计特征（提示词与中文拆解的推断依据）。
     */
    private record Features(double brightness, double saturation, double contrast, double warmth,
                            double detail, boolean monochrome, boolean hasTransparency,
                            List<String> palette, int topHue1, int topHue2) {
    }

    /**
     * 对图像做降采样统计：平均亮度、平均饱和度、亮度标准差（对比度）、
     * 暖冷倾向、细节密度、主色板与主导色相。
     */
    private static Features analyzeFeatures(BufferedImage source, boolean transparent) {
        BufferedImage img = downsample(source, SAMPLE_MAX);
        int w = img.getWidth();
        int h = img.getHeight();
        double[] lum = new double[w * h];
        double sumLum = 0.0;
        double sumLumSq = 0.0;
        double sumSat = 0.0;
        double sumWarm = 0.0;
        int opaque = 0;
        int[] hueBins = new int[HUE_EN.length];
        var bins = new LinkedHashMap<Integer, int[]>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                double l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
                lum[y * w + x] = l;
                if (((argb >>> 24) & 0xFF) == 0) {
                    continue;
                }
                opaque++;
                sumLum += l;
                sumLumSq += l * l;
                float[] hsb = Color.RGBtoHSB(r, g, b, null);
                sumSat += hsb[1];
                sumWarm += (r - b) / 255.0;
                if (hsb[1] >= 0.15 && hsb[2] >= 0.15 && hsb[2] <= 0.95) {
                    hueBins[Math.min(HUE_EN.length - 1, (int) (hsb[0] * HUE_EN.length))]++;
                }
                int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                int[] acc = bins.computeIfAbsent(key, k -> new int[4]);
                acc[0]++;
                acc[1] += r;
                acc[2] += g;
                acc[3] += b;
            }
        }
        if (opaque == 0) {
            opaque = 1;
        }
        double brightness = sumLum / opaque;
        double saturation = sumSat / opaque;
        double variance = Math.max(0.0, sumLumSq / opaque - brightness * brightness);
        int hue1 = topHue(hueBins, opaque, -1);
        int hue2 = topHue(hueBins, opaque, hue1);
        return new Features(brightness, saturation, Math.sqrt(variance), sumWarm / opaque,
                detailDegree(lum, w, h), saturation < 0.08, transparent,
                topPalette(bins, opaque), hue1, hue2);
    }

    /**
     * 细节密度：相邻像素亮度的平均梯度（值越大细节越丰富）。
     */
    private static double detailDegree(double[] lum, int w, int h) {
        if (w < 2 || h < 2) {
            return 0.0;
        }
        double total = 0.0;
        int count = 0;
        for (int y = 0; y < h - 1; y++) {
            for (int x = 0; x < w - 1; x++) {
                int i = y * w + x;
                total += Math.abs(lum[i] - lum[i + 1]) + Math.abs(lum[i] - lum[i + w]);
                count += 2;
            }
        }
        return count == 0 ? 0.0 : total / count;
    }

    /**
     * 主色板：按 4bit/通道量化统计，取占比 ≥2% 的前 6 个颜色簇，输出十六进制色值。
     */
    private static List<String> topPalette(Map<Integer, int[]> bins, int total) {
        List<Map.Entry<Integer, int[]>> entries = new ArrayList<>(bins.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        List<String> palette = new ArrayList<>();
        for (Map.Entry<Integer, int[]> entry : entries) {
            if (palette.size() >= 6 || entry.getValue()[0] < total * 0.02) {
                break;
            }
            int[] acc = entry.getValue();
            palette.add(String.format(Locale.ROOT, "#%02X%02X%02X",
                    Math.round(acc[1] / (float) acc[0]), Math.round(acc[2] / (float) acc[0]),
                    Math.round(acc[3] / (float) acc[0])));
        }
        return palette;
    }

    /**
     * 主导色相：取统计占比最高（≥8%）的色相区间；exclude 用于跳过已选区间取第二主导色。
     */
    private static int topHue(int[] hueBins, int total, int exclude) {
        int best = -1;
        int bestCount = (int) (total * 0.08);
        for (int i = 0; i < hueBins.length; i++) {
            if (i != exclude && hueBins[i] > bestCount) {
                best = i;
                bestCount = hueBins[i];
            }
        }
        return best;
    }

    /**
     * 等比缩小到最大边长 maxSize 内（复用 {@link BufferedImageUtils#scaleImage}），小图原样返回。
     */
    private static BufferedImage downsample(BufferedImage source, int maxSize) {
        int w = source.getWidth();
        int h = source.getHeight();
        if (w <= maxSize && h <= maxSize) {
            return source;
        }
        double scale = maxSize / (double) Math.max(w, h);
        int sw = Math.max(1, (int) Math.round(w * scale));
        int sh = Math.max(1, (int) Math.round(h * scale));
        return BufferedImageUtils.scaleImage(source, sw, sh);
    }

    /**
     * 抽样判断图像是否含有效透明像素（PNG/GIF 透明通道）。
     */
    private static boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int step = Math.max(1, (int) Math.sqrt((double) w * h / 4096));
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) < 250) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 创意模式特征：从构想文本的关键词推断明暗 / 色调 / 饱和度 / 对比与细节倾向。
     */
    private static Features ideaFeatures(String idea) {
        String text = idea.toLowerCase();
        double brightness = containsAny(text, "night", "夜", "dark", "暗", "midnight", "月光") ? 0.18
                : containsAny(text, "bright", "明亮", "sunny", "阳光", "白天", "雪") ? 0.75 : 0.5;
        double warmth = containsAny(text, "warm", "暖", "sunset", "夕阳", "golden", "金", "秋", "篝火") ? 0.12
                : containsAny(text, "cool", "冷", "winter", "冬", "ice", "冰", "blue hour", "月") ? -0.12 : 0.0;
        double saturation = containsAny(text, "vivid", "鲜艳", "colorful", "五彩", "neon", "霓虹") ? 0.6
                : containsAny(text, "muted", "素", "gray", "灰", "minimal", "极简", "pastel", "淡") ? 0.12 : 0.35;
        double contrast = containsAny(text, "dramatic", "戏剧", "shadow", "阴影") ? 0.3 : 0.18;
        double detail = containsAny(text, "minimal", "极简", "flat", "扁平") ? 0.02 : 0.06;
        boolean mono = containsAny(text, "黑白", "monochrome", "grayscale", "单色");
        return new Features(brightness, mono ? 0.02 : saturation, contrast, warmth, detail, mono,
                false, List.of(), -1, -1);
    }

    /**
     * 文本中是否包含任一关键词。
     */
    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    // ==== 特征分级与画幅 ====

    /**
     * 画幅方向：横幅 / 竖幅 / 方图。
     */
    private static String orientationName(int width, int height) {
        if (width > height) {
            return "横幅";
        }
        if (width < height) {
            return "竖幅";
        }
        return "方图";
    }

    /**
     * 宽高比：先按最大公约数约分；结果过大时匹配常用比例，最后退回小数比值。
     */
    private static String aspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "1:1";
        }
        int gcd = gcd(width, height);
        int aw = width / gcd;
        int ah = height / gcd;
        if (aw <= 40 && ah <= 40) {
            return aw + ":" + ah;
        }
        double ratio = (double) width / height;
        for (int[] common : COMMON_RATIOS) {
            if (Math.abs(ratio - (double) common[0] / common[1]) < 0.02) {
                return common[0] + ":" + common[1];
            }
        }
        return String.format(Locale.ROOT, "%.2f:1", ratio);
    }

    /**
     * 最大公约数。
     */
    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /**
     * 亮度分级：暗调 / 适中 / 明亮。
     */
    private static String brightnessLevel(Features f) {
        if (f.brightness() < 0.32) {
            return "暗调";
        }
        if (f.brightness() > 0.62) {
            return "明亮";
        }
        return "适中";
    }

    /**
     * 饱和度分级：低饱和 / 中等饱和 / 高饱和。
     */
    private static String saturationLevel(Features f) {
        if (f.saturation() < 0.15) {
            return "低饱和";
        }
        if (f.saturation() > 0.45) {
            return "高饱和";
        }
        return "中等饱和";
    }

    /**
     * 对比度分级：低对比 / 中等对比 / 高对比。
     */
    private static String contrastLevel(Features f) {
        if (f.contrast() < 0.13) {
            return "低对比";
        }
        if (f.contrast() > 0.26) {
            return "高对比";
        }
        return "中等对比";
    }

    /**
     * 色调倾向：暖调 / 冷调 / 中性。
     */
    private static String colorTone(Features f) {
        if (f.warmth() > 0.05) {
            return "暖调";
        }
        if (f.warmth() < -0.05) {
            return "冷调";
        }
        return "中性";
    }

    // ==== 提示词与中文拆解组装 ====

    /**
     * 汇总特征与主体描述，生成整套提示词与中文画面拆解。
     */
    private static PromptReport buildReport(int width, int height, Features f, String subjectHint) {
        boolean ideaMode = width <= 0 || height <= 0;
        String orientation = ideaMode ? "" : orientationName(width, height);
        String ratio = aspectRatio(width, height);
        boolean hasSubject = !StringUtils.isBlank(subjectHint);
        String subject = hasSubject ? subjectHint.trim().replaceAll("\\s+", " ") : DEFAULT_SUBJECT;

        String tone = colorTone(f);
        String bright = brightnessLevel(f);
        String sat = saturationLevel(f);
        String contrast = contrastLevel(f);
        String lightingEn = lightingPhrase(f);
        String paletteEn = palettePhrase(f);
        String styleEn = stylePhrase(f);
        String detailEn = detailPhrase(f);
        String moodEn = moodPhrase(f);

        String general = subject + ", " + orientationPhrase(width, height) + ", " + paletteEn + ", "
                + lightingEn + ", " + styleEn + ", " + moodEn + ", " + detailEn
                + ", professional composition, high quality";
        String midjourney = subject + ", " + paletteEn + ", " + lightingEn + ", " + styleEn + ", "
                + detailEn + " --ar " + ratio + " --v 6"
                + (!f.monochrome() && f.saturation() < 0.5 ? " --style raw" : "");
        String stableDiffusion = "(masterpiece, best quality, ultra-detailed), " + subject + ", "
                + paletteEn + ", " + lightingEn + ", " + styleEn + ", " + detailEn + ", high resolution";
        String negative = "(worst quality, low quality:1.4), blurry, jpeg artifacts, watermark, text, signature";
        if (f.monochrome()) {
            negative = "(color image, saturated colors:1.3), " + negative;
        }
        String video = "Cinematic video of " + subject + ". " + capitalize(lightingEn) + ", "
                + moodEn + " atmosphere. Slow subtle camera push-in with stable framing, "
                + paletteEn + " color grading, natural motion, 24fps, 5 seconds.";
        String shortPrompt = subject + ", " + paletteEn + ", " + lightingEn + ", " + styleEn;

        String note;
        if (ideaMode) {
            note = "创意模式：未做图片分析，画面特征由构想关键词推断";
        } else if (!hasSubject) {
            note = "未提供主体提示（subjectHint），提示词使用通用主体占位，建议人工补充";
        } else {
            note = "";
        }
        return new PromptReport(true, note, width, height, orientation, ratio, f.palette(),
                tone, bright, sat, contrast, f.monochrome(), f.hasTransparency(),
                general, midjourney, stableDiffusion, negative, video, shortPrompt,
                buildAnalysis(ideaMode, width, height, orientation, ratio, f, tone, sat, contrast,
                        hasSubject ? subject : null));
    }

    /**
     * 画幅方向的英文表述。
     */
    private static String orientationPhrase(int width, int height) {
        if (width > height) {
            return "landscape orientation";
        }
        if (width < height) {
            return "portrait orientation";
        }
        return "square composition";
    }

    /**
     * 色彩描述（英文提示词用）。
     */
    private static String palettePhrase(Features f) {
        if (f.monochrome()) {
            return "monochrome grayscale palette";
        }
        String toneAdj = f.warmth() > 0.05 ? "warm" : f.warmth() < -0.05 ? "cool" : "neutral";
        String satAdj = f.saturation() > 0.45 ? "vivid" : f.saturation() < 0.15 ? "muted" : "natural";
        String hues = "";
        if (f.topHue1() >= 0) {
            hues = " with dominant " + HUE_EN[f.topHue1()]
                    + (f.topHue2() >= 0 ? " and " + HUE_EN[f.topHue2()] : "") + " hues";
        }
        return "a " + toneAdj + " " + satAdj + " palette" + hues;
    }

    /**
     * 光线描述（英文提示词用）。
     */
    private static String lightingPhrase(Features f) {
        boolean dark = f.brightness() < 0.32;
        boolean bright = f.brightness() > 0.62;
        if (dark && f.contrast() > 0.26) {
            return "dramatic low-key lighting with strong shadows";
        }
        if (dark) {
            return "dim ambient lighting, night-time mood";
        }
        if (bright && f.warmth() > 0.05 && f.saturation() >= 0.3) {
            return "warm golden-hour sunlight";
        }
        if (bright && f.warmth() < -0.05) {
            return "cool soft daylight, overcast blue-hour ambience";
        }
        if (bright) {
            return "soft diffused lighting with gentle shadows";
        }
        return "balanced natural lighting";
    }

    /**
     * 风格描述（英文提示词用）。
     */
    private static String stylePhrase(Features f) {
        if (f.monochrome()) {
            return "classic black-and-white photography style";
        }
        if (f.contrast() > 0.26 && f.detail() >= 0.05) {
            return "high-contrast photorealistic style";
        }
        if (f.contrast() < 0.13) {
            return "soft flat minimalist style";
        }
        if (f.saturation() > 0.45) {
            return "bold saturated color style";
        }
        return "natural realistic style";
    }

    /**
     * 细节描述（英文提示词用）。
     */
    private static String detailPhrase(Features f) {
        if (f.detail() >= 0.08) {
            return "rich fine details, sharp focus";
        }
        if (f.detail() <= 0.03) {
            return "smooth minimal look, soft focus";
        }
        return "clean moderate detail";
    }

    /**
     * 情绪描述（英文提示词用）。
     */
    private static String moodPhrase(Features f) {
        if (f.brightness() < 0.32 && f.contrast() > 0.26) {
            return "mysterious dramatic";
        }
        if (f.warmth() > 0.05 && f.brightness() > 0.62) {
            return "warm cheerful";
        }
        if (f.warmth() < -0.05) {
            return "calm cool";
        }
        if (f.saturation() < 0.15) {
            return "serene understated";
        }
        if (f.saturation() > 0.45 && f.brightness() > 0.62) {
            return "lively vibrant";
        }
        return "balanced natural";
    }

    /**
     * 首字母大写。
     */
    private static String capitalize(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * 中文画面拆解：画幅 / 色彩 / 主导色相 / 光线 / 情绪 / 媒介与风格 / 镜头建议 / 风格关键词 / 主体。
     */
    private static String buildAnalysis(boolean ideaMode, int width, int height, String orientation,
                                        String ratio, Features f, String tone, String sat,
                                        String contrast, String subject) {
        StringBuilder sb = new StringBuilder();
        sb.append("【画幅】");
        if (ideaMode) {
            sb.append("未指定（创意模式，默认按 1:1 生成）");
        } else {
            sb.append(width).append("×").append(height).append("，").append(orientation)
                    .append("（约 ").append(ratio).append("）");
        }
        sb.append('\n');
        sb.append("【色彩】");
        if (f.palette().isEmpty()) {
            sb.append("未提供色板（创意模式）");
        } else {
            sb.append("主色板 ").append(String.join("、", f.palette()));
        }
        sb.append("；").append(tone).append("、").append(sat).append("、").append(contrast);
        if (f.monochrome()) {
            sb.append("（近黑白/单色）");
        }
        if (f.hasTransparency()) {
            sb.append("；含透明区域");
        }
        sb.append('\n');
        if (f.topHue1() >= 0) {
            sb.append("【主导色相】").append(HUE_ZH[f.topHue1()]);
            if (f.topHue2() >= 0) {
                sb.append("、").append(HUE_ZH[f.topHue2()]);
            }
            sb.append('\n');
        }
        sb.append("【光线】").append(lightingZh(f)).append('\n');
        sb.append("【情绪】").append(moodZh(f)).append('\n');
        sb.append("【媒介与风格】").append(mediaZh(f)).append("；细节：").append(detailZh(f)).append('\n');
        sb.append("【镜头建议】").append(cameraSuggestion(f, width, height)).append('\n');
        sb.append("【风格关键词】").append(styleKeywords(f)).append('\n');
        sb.append("【主体】");
        if (!StringUtils.isBlank(subject)) {
            sb.append("已融入提示词：").append(subject);
        } else {
            sb.append("统计特征无法识别具体主体，建议通过 subjectHint 传入或人工补充");
        }
        return sb.toString();
    }

    /**
     * 光线推断（中文分析用）。
     */
    private static String lightingZh(Features f) {
        boolean dark = f.brightness() < 0.32;
        boolean bright = f.brightness() > 0.62;
        if (dark && f.contrast() > 0.26) {
            return "暗部占比高、明暗反差强，推断为低调戏剧性布光（夜景/强光源）";
        }
        if (dark) {
            return "整体偏暗，推断为弱光环境（夜景/室内弱光）";
        }
        if (bright && f.warmth() > 0.05) {
            return "整体明亮偏暖，类似黄金时刻的自然光";
        }
        if (bright && f.warmth() < -0.05) {
            return "明亮偏冷，类似阴天漫射光或蓝调时刻";
        }
        if (bright) {
            return "明亮柔和、阴影浅，类似柔光箱/漫射布光";
        }
        return "光线均衡，接近自然光/标准影棚布光";
    }

    /**
     * 情绪推断（中文分析用）。
     */
    private static String moodZh(Features f) {
        if (f.brightness() < 0.32 && f.contrast() > 0.26) {
            return "神秘、戏剧化";
        }
        if (f.warmth() > 0.05 && f.brightness() > 0.62) {
            return "温暖明快";
        }
        if (f.warmth() < -0.05) {
            return "清冷宁静";
        }
        if (f.saturation() < 0.15) {
            return "素雅平静";
        }
        if (f.saturation() > 0.45 && f.brightness() > 0.62) {
            return "明快有活力";
        }
        return "平和自然";
    }

    /**
     * 媒介推断（中文分析用，启发式）。
     */
    private static String mediaZh(Features f) {
        if (f.monochrome()) {
            return "推测为黑白摄影/单色调图像";
        }
        if (f.saturation() > 0.45 && f.contrast() > 0.26) {
            return "推测为高饱和商业摄影/数字插画";
        }
        if (f.detail() < 0.03) {
            return "倾向扁平插画/极简风格";
        }
        return "倾向写实摄影/通用数字图像";
    }

    /**
     * 细节描述（中文分析用）。
     */
    private static String detailZh(Features f) {
        if (f.detail() >= 0.08) {
            return "细节丰富、锐利";
        }
        if (f.detail() <= 0.03) {
            return "平滑简洁、虚化感";
        }
        return "中等细节、干净";
    }

    /**
     * 镜头/曝光建议（中文分析用，按画幅与特征给出可复现的拍摄参数）。
     */
    private static String cameraSuggestion(Features f, int width, int height) {
        if (f.brightness() < 0.32) {
            return "大光圈定焦 f/1.4–f/2.0，适当提高 ISO 或使用三脚架";
        }
        if (width > 0 && height > width && f.warmth() >= -0.05) {
            return "85mm 人像焦段定焦，f/1.8–f/2.2 浅景深，ISO 100–400";
        }
        if (width > height && f.detail() >= 0.08) {
            return "16–35mm 广角，f/8–f/11 保证景深，配合三脚架";
        }
        if (f.saturation() > 0.45 && f.brightness() > 0.62) {
            return "24–70mm 标准变焦，f/5.6–f/8，注意高光溢出";
        }
        return "24–70mm 标准变焦，f/4–f/8，ISO 100–400";
    }

    /**
     * 风格关键词（中英混合场景以英文关键词输出，便于直接贴入提示词）。
     */
    private static String styleKeywords(Features f) {
        List<String> keywords = new ArrayList<>();
        if (f.monochrome()) {
            keywords.add("monochrome");
        }
        if (f.brightness() < 0.32) {
            keywords.add("low-key");
        } else if (f.brightness() > 0.62) {
            keywords.add("bright");
        }
        if (f.contrast() > 0.26) {
            keywords.add("high-contrast");
        } else if (f.contrast() < 0.13) {
            keywords.add("soft");
        }
        if (f.saturation() > 0.45) {
            keywords.add("vivid");
        } else if (f.saturation() < 0.15) {
            keywords.add("muted");
        }
        if (f.warmth() > 0.05) {
            keywords.add("warm-tone");
        } else if (f.warmth() < -0.05) {
            keywords.add("cool-tone");
        }
        if (f.detail() >= 0.08) {
            keywords.add("high-detail");
        } else if (f.detail() <= 0.03) {
            keywords.add("minimalist");
        }
        return String.join(", ", keywords);
    }

    // ==== 结果类型 ====

    /**
     * 提示词生成报告。
     *
     * @param supported             是否成功解析图像/构想
     * @param note                  备注（不支持原因、创意模式说明或主体补充建议）
     * @param width                 图像宽度（创意模式为 0）
     * @param height                图像高度（创意模式为 0）
     * @param orientation           画幅方向：横幅 / 竖幅 / 方图（创意模式为空）
     * @param aspectRatio           约分后的宽高比，如 “16:9”
     * @param palette               主色板（十六进制色值，按占比降序）
     * @param colorTone             色调倾向：暖调 / 冷调 / 中性
     * @param brightnessLevel       亮度分级：暗调 / 适中 / 明亮
     * @param saturationLevel       饱和度分级：低饱和 / 中等饱和 / 高饱和
     * @param contrastLevel         对比度分级：低对比 / 中等对比 / 高对比
     * @param monochrome            是否接近黑白/单色
     * @param hasTransparency       是否含透明区域
     * @param generalPrompt         通用自然语言提示词（ChatGPT / Gemini / Copilot 等）
     * @param midjourneyPrompt      Midjourney 提示词（含 --ar 画幅参数）
     * @param stableDiffusionPrompt Stable Diffusion / Flux 提示词（标签式）
     * @param negativePrompt        负面提示词（配合 Stable Diffusion / Flux 使用）
     * @param videoPrompt           视频生成提示词（Veo / Kling / Seedance 等）
     * @param shortPrompt           短提示词（Canva 等应用的简短场景）
     * @param analysis              中文画面拆解：画幅/色彩/光线/情绪/媒介/镜头/风格关键词/主体
     */
    public record PromptReport(boolean supported, String note, int width, int height,
                               String orientation, String aspectRatio, List<String> palette,
                               String colorTone, String brightnessLevel, String saturationLevel,
                               String contrastLevel, boolean monochrome, boolean hasTransparency,
                               String generalPrompt, String midjourneyPrompt,
                               String stableDiffusionPrompt, String negativePrompt,
                               String videoPrompt, String shortPrompt, String analysis) {

        /**
         * 失败/不支持时的占位报告。
         */
        static PromptReport unsupported(String note) {
            return new PromptReport(false, note, 0, 0, "", "", List.of(),
                    "", "", "", "", false, false, "", "", "", "", "", "", "");
        }
    }
}
