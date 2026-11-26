package com.chua.common.support.utils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 照片 AI 元数据检测与清除工具类（纯 JDK，无第三方依赖）。
 *
 * <p>参考 MiniWebtool「照片 AI 元数据清除」工具的能力实现：读取照片元数据中的
 * C2PA 内容凭证（JUMBF）、IPTC {@code DigitalSourceType}、XMP、EXIF Software/UserComment
 * 以及扩散模型常见的生成参数块（prompt/steps/sampler/seed 等），判断照片是否带有
 * AI 生成/编辑痕迹；并支持在不重新压缩像素的前提下移除 AI 标签（保留
 * EXIF Orientation 与 ICC 色彩配置文件，像素字节不变）。</p>
 *
 * <h2>能力矩阵</h2>
 * <table border="1">
 *   <tr><th>能力</th><th>方法</th><th>说明</th></tr>
 *   <tr><td>AI 检测（元数据 + 像素统计）</td><td>{@link #analyze(byte[])}</td><td>先查 C2PA/来源类型/生成器/参数块；元数据无信号时做像素统计兜底（上采样伪迹、传感器噪声特征），输出判定、置信度与全部信号</td></tr>
 *   <tr><td>是否 AI 生成/编辑</td><td>{@link #isAiGenerated(byte[])}</td><td>判定为「AI 生成」或「AI 参与（编辑/合成）」时返回 true</td></tr>
 *   <tr><td>仅取判定结果</td><td>{@link #aiVerdict(byte[])}</td><td>AI 生成 / AI 参与（编辑/合成）/ 未发现 AI 痕迹 / 无法判断</td></tr>
 *   <tr><td>清除 AI 元数据</td><td>{@link #stripAiMetadata(byte[])}</td><td>免重压缩移除 C2PA/XMP/注释中的 AI 标签，返回新字节数组</td></tr>
 * </table>
 *
 * <h2>支持格式</h2>
 * <ul>
 *   <li><b>JPEG</b>：APP1 XMP/EXIF、APP11 C2PA(JUMBF)、COM 注释；清除时保留 EXIF 与 APP2 ICC；</li>
 *   <li><b>PNG</b>：eXIf、iTXt/tEXt/zTXt（含 XMP 包与 Stable Diffusion 的 parameters 文本）、c2pa/caBX 块；</li>
 *   <li><b>WebP</b>：EXIF、XMP、C2PA RIFF 块；</li>
 *   <li><b>HEIC/HEIF</b>：暂不支持（返回「无法判断」）。</li>
 * </ul>
 *
 * <p>生成参数块按各工具的真实落盘口径识别：AUTOMATIC1111 系（PNG tEXt {@code parameters}，
 * 或 JPEG/WebP 的 EXIF UserComment，含 Steps/Sampler/CFG scale/Seed/Model hash 等行）、
 * ComfyUI（{@code prompt} / {@code workflow} JSON 文本块）、NovelAI（tEXt {@code Software}
 * 与 {@code Comment} JSON）、InvokeAI（{@code sd-metadata} JSON）。C2PA 依据
 * {@code c2pa.actions} 中的 IPTC DigitalSourceType（trainedAlgorithmicMedia 等）判定。</p>
 *
 * <h2>限制说明</h2>
 * <ul>
 *   <li>JPEG 的 EXIF 段<b>不会被重写</b>（避免破坏 Orientation 指针结构），因此写在
 *       EXIF Software 字段里的生成器名称不会被清除；AI 判定依赖的 XMP/C2PA/注释段会被清除；</li>
 *   <li>扩展 XMP（multi-segment）仅做检测，不做分段重组重写；</li>
 *   <li>清除（strip）只处理元数据字节，不解码/重编码像素；像素级旋转请配合 {@link BufferedImageUtils}；
 *       检测（analyze）在元数据无信号时会对像素做<b>只读</b>统计采样（降采样后计算残差自相关等），不修改原图；</li>
 *   <li>检测结果<b>仅供参考</b>：元数据可被伪造或剥离，无 AI 痕迹不代表一定是实拍；
 *       像素统计兜底为启发式方法，截图、图表、程序绘制的合成图形也可能呈现类似统计特征而出现疑似误报；
 *       扁平数字内容路径（跨通道残差相关）对剥除元数据的 AI 平涂插画、手绘插画、logo、错误页等
 *       同属「无传感器噪声的平坦数字图」，存在把真实数字素材误判为「疑似 AI」的可能，故阈值从严且结论仅为「疑似」；</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * PhotoMetadataUtils.Report report = PhotoMetadataUtils.analyze(bytes);
 * System.out.println(report.verdict() + " / 置信度 " + report.confidence());
 * if (report.hasContentCredentials()) { System.out.println("含 C2PA 内容凭证"); }
 *
 * PhotoMetadataUtils.StripResult cleaned = PhotoMetadataUtils.stripAiMetadata(bytes);
 * if (cleaned.modified()) { cleaned.removed().forEach(System.out::println); }
 * }</pre>
 *
 * <h2>线程安全</h2>
 * <p>所有方法均为静态方法且无共享可变状态，线程安全。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class PhotoMetadataUtils {

    /** 判定结果：AI 生成 */
    public static final String VERDICT_AI_GENERATED = "AI 生成";
    /** 判定结果：AI 参与（编辑/合成） */
    public static final String VERDICT_AI_EDITED = "AI 参与（编辑/合成）";
    /** 判定结果：未发现 AI 痕迹 */
    public static final String VERDICT_NO_AI_SIGNALS = "未发现 AI 痕迹";
    /** 判定结果：无法判断（格式不支持或无有效数据） */
    public static final String VERDICT_UNKNOWN = "无法判断";

    /** 置信度：高（有显式 AI 来源类型标签或 C2PA AI 声明） */
    public static final String CONFIDENCE_HIGH = "高";
    /** 置信度：中（有生成器名称或生成参数块） */
    public static final String CONFIDENCE_MEDIUM = "中";
    /** 置信度：低（仅间接特征或无信号） */
    public static final String CONFIDENCE_LOW = "低";

    /** EXIF：图片描述 */
    private static final int TAG_IMAGE_DESCRIPTION = 0x010E;
    /** EXIF：制造商 */
    private static final int TAG_MAKE = 0x010F;
    /** EXIF：型号 */
    private static final int TAG_MODEL = 0x0110;
    /** EXIF：方向 */
    private static final int TAG_ORIENTATION = 0x0112;
    /** EXIF：软件 */
    private static final int TAG_SOFTWARE = 0x0131;
    /** EXIF：日期时间 */
    private static final int TAG_DATE_TIME = 0x0132;
    /** EXIF：作者 */
    private static final int TAG_ARTIST = 0x013B;
    /** EXIF：版权 */
    private static final int TAG_COPYRIGHT = 0x8298;
    /** EXIF：Exif 子 IFD 指针 */
    private static final int TAG_EXIF_IFD = 0x8769;
    /** EXIF：用户注释（生成参数常藏在这里） */
    private static final int TAG_USER_COMMENT = 0x9286;

    /** JPEG APP1：EXIF 段命名空间前缀 */
    private static final byte[] JPEG_EXIF_PREFIX = {'E', 'x', 'i', 'f', 0, 0};
    /** JPEG APP1：标准 XMP 段命名空间前缀 */
    private static final byte[] JPEG_XMP_PREFIX =
            "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);

    /** PNG 块类型：EXIF */
    private static final int PNG_CHUNK_EXIF = chunkType("eXIf");
    /** PNG 块类型：国际文本（可含 XMP） */
    private static final int PNG_CHUNK_ITXT = chunkType("iTXt");
    /** PNG 块类型：拉丁文本 */
    private static final int PNG_CHUNK_TEXT = chunkType("tEXt");
    /** PNG 块类型：压缩文本 */
    private static final int PNG_CHUNK_ZTXT = chunkType("zTXt");
    /** PNG 块类型：C2PA 内容凭证（JUMBF） */
    private static final int PNG_CHUNK_C2PA = chunkType("c2pa");
    /** PNG 块类型：C2PA/CAI 兼容块 */
    private static final int PNG_CHUNK_CABX = chunkType("caBX");
    /** PNG XMP 文本块的关键字 */
    private static final String PNG_XMP_KEYWORD = "XML:com.adobe.xmp";
    /** Stable Diffusion 等 WebUI 写入 PNG 的参数块关键字 */
    private static final String PNG_PARAMS_KEYWORD = "parameters";
    /** PNG 文本块：EXIF Software 的对应物（NovelAI 等写入） */
    private static final String PNG_SOFTWARE_KEYWORD = "Software";
    /** ComfyUI 写入 PNG 的生成提示词 JSON 关键字 */
    private static final String PNG_PROMPT_KEYWORD = "prompt";
    /** ComfyUI 写入 PNG 的工作流 JSON 关键字 */
    private static final String PNG_WORKFLOW_KEYWORD = "workflow";
    /** NovelAI 写入 PNG 的参数 JSON 关键字 */
    private static final String PNG_COMMENT_KEYWORD = "Comment";
    /** InvokeAI 写入 PNG 的元数据 JSON 关键字 */
    private static final String PNG_SD_METADATA_KEYWORD = "sd-metadata";

    /** WebP RIFF 块：EXIF */
    private static final int WEBP_CHUNK_EXIF = chunkType("EXIF");
    /** WebP RIFF 块：XMP */
    private static final int WEBP_CHUNK_XMP = chunkType("XMP ");
    /** WebP RIFF 块：C2PA 内容凭证 */
    private static final int WEBP_CHUNK_C2PA = chunkType("C2PA");

    /** 生成器名称（强信号）：出现在任意文本里都值得报告 */
    private static final String[] GENERATOR_STRONG = {
            "midjourney", "stable diffusion", "stablediffusion", "stable-diffusion",
            "dall-e", "dall·e", "dall·e", "dalle", "openai", "sora", "gpt-4o",
            "adobe firefly", "firefly", "comfyui", "automatic1111", "novelai",
            "sdxl", "dreambooth", "civitai", "kohya", "invokeai", "runway",
            "google imagen", "生成式人工智能", "ai生成", "ai 生成",
            "niji", "leonardo.ai", "leonardo ai", "recraft", "swarmui", "sd webui",
            "generative ai", "generativeai", "ai-generated", "ai generated", "人工智能生成"
    };

    /** 生成器名称（弱信号）：仅当出现在 Software/CreatorTool/UserComment 等专用字段时采信 */
    private static final String[] GENERATOR_WEAK = {
            "imagen", "flux", "qwen", "doubao", "wanx", "hunyuan", "kling", "pika", "luma",
            "jimeng", "即梦", "可灵", "文心一格", "通义万相", "混元", "豆包",
            "grok", "gemini", "leonardo", "ideogram", "diffusers", "minimax",
            "seedream", "hidream", "kolors", "cogview", "pixverse", "vidu", "hailuo",
            "ernie", "合成媒体"
    };

    /** 显式 AI 来源类型/标签关键词（大小写不敏感） */
    private static final String[] AI_LABEL_KEYWORDS = {
            "trainedalgorithmicmedia", "compositewithtrainedalgorithmicmedia",
            "compositesynthetic", "algorithmicmedia", "generativeai", "aigc"
    };

    /** IPTC DigitalSourceType：元素文本形式 */
    private static final Pattern P_DST_ELEMENT = Pattern.compile(
            "(?is)<[\\w.-]*:DigitalSourceType[^>]*>\\s*([^<]+?)\\s*</");
    /** IPTC DigitalSourceType：属性形式 */
    private static final Pattern P_DST_ATTRIBUTE = Pattern.compile(
            "[\\w.-]*:DigitalSourceType=\"([^\"]+)\"");
    /** XMP CreatorTool 元素 */
    private static final Pattern P_CREATOR_TOOL = Pattern.compile(
            "(?is)<xmp:CreatorTool([^>]*)>(.*?)</xmp:CreatorTool>");
    /** XMP xmpMM:History 的 softwareAgent（属性形式） */
    private static final Pattern P_SOFTWARE_AGENT_ATTR = Pattern.compile(
            "[\\w.-]*:softwareAgent=\"([^\"]+)\"");
    /** XMP xmpMM:History 的 softwareAgent（元素形式） */
    private static final Pattern P_SOFTWARE_AGENT_ELEMENT = Pattern.compile(
            "(?is)<[\\w.-]*:softwareAgent[^>]*>(.*?)</");

    /**
     * 私有构造方法，防止实例化
     */
    private PhotoMetadataUtils() {
    }

    /**
     * 分析照片中的 AI 痕迹。
     *
     * <p>先检查元数据：C2PA 内容凭证、IPTC {@code DigitalSourceType}、XMP 生成器/来源类型、
     * EXIF Software/UserComment 以及生成参数块（prompt/steps/sampler/seed 等）。网络图片与
     * 多数 AI 生成图会剥离这些元数据，因此元数据无信号时，再对像素做只读统计兜底分析：
     * 扩散模型等生成图像常见上采样/网格伪迹（残差自相关呈周期性峰值、相邻残差正负交替的
     * 棋盘纹理），且缺失相机传感器噪声特征（跨通道噪声不相关）。兜底证据成立时判为
     * 「AI 参与（编辑/合成）」（疑似），并在信号中给出具体依据。</p>
     *
     * @param data 图像文件字节（JPEG/PNG/WebP）
     * @return 检测报告，永不为 {@code null}；数据无效或格式不支持时判定为「无法判断」
     */
    public static Report analyze(byte[] data) {
        if (data == null || data.length < 12) {
            return Report.unknown("无有效图像数据");
        }
        Report report;
        if (isJpeg(data)) {
            report = analyzeJpeg(data);
        } else if (isPng(data)) {
            report = analyzePng(data);
        } else if (isWebp(data)) {
            report = analyzeWebp(data);
        } else {
            return Report.unknown("不支持的格式（当前支持 JPEG / PNG / WebP）");
        }
        String verdict = report.verdict();
        if (VERDICT_AI_GENERATED.equals(verdict) || VERDICT_AI_EDITED.equals(verdict)) {
            return report;
        }
        PixelAnalysis pixel = analyzePixels(data, report.format());
        if (pixel == null) {
            return report;
        }
        List<String> signals = new ArrayList<>(report.signals());
        signals.addAll(pixel.signals());
        if (pixel.hits() <= 0) {
            if (VERDICT_NO_AI_SIGNALS.equals(report.verdict())) {
                signals.add("像素统计：未发现上采样伪迹或传感器噪声异常（启发式，仅供参考）");
                return new Report(report.format(), report.verdict(), report.confidence(),
                        report.hasContentCredentials(), report.digitalSourceType(),
                        report.generator(), report.generatorSource(), report.generationParams(), signals);
            }
            return report;
        }
        String confidence = pixel.hits() >= 2 ? CONFIDENCE_MEDIUM : CONFIDENCE_LOW;
        return new Report(report.format(), VERDICT_AI_EDITED, confidence,
                report.hasContentCredentials(), report.digitalSourceType(),
                report.generator(), report.generatorSource(), report.generationParams(), signals);
    }

    /**
     * 判断照片是否带有 AI 生成/编辑痕迹。
     *
     * @param data 图像文件字节
     * @return 判定为「AI 生成」或「AI 参与（编辑/合成）」时返回 true
     */
    public static boolean isAiGenerated(byte[] data) {
        Report report = analyze(data);
        return VERDICT_AI_GENERATED.equals(report.verdict()) || VERDICT_AI_EDITED.equals(report.verdict());
    }

    /**
     * 仅取 AI 判定结果。
     *
     * @param data 图像文件字节
     * @return 判定结果文案，见 {@link #analyze(byte[])}
     */
    public static String aiVerdict(byte[] data) {
        return analyze(data).verdict();
    }

    /**
     * 在不重新压缩像素的前提下清除照片中的 AI 元数据标签。
     *
     * <p>移除对象：C2PA/JUMBF 内容凭证、XMP 中的 AI 来源类型/生成器节点、
     * 含 AI 痕迹的注释与生成参数块；保留 EXIF（含 Orientation）、ICC 色彩配置文件
     * 与所有像素数据。像素字节不会发生变化，仅重组元数据段。</p>
     *
     * @param data 图像文件字节（JPEG/PNG/WebP）
     * @return 清除结果：新字节数组 + 被移除/清除的条目列表；数据无效时原样返回
     */
    public static StripResult stripAiMetadata(byte[] data) {
        if (data == null || data.length < 12) {
            return new StripResult(data, List.of(), false);
        }
        List<String> removed = new ArrayList<>();
        byte[] cleaned = data;
        if (isJpeg(data)) {
            cleaned = stripJpeg(data, removed);
        } else if (isPng(data)) {
            cleaned = stripPng(data, removed);
        } else if (isWebp(data)) {
            cleaned = stripWebp(data, removed);
        }
        if (removed.isEmpty()) {
            return new StripResult(data, List.of(), false);
        }
        return new StripResult(cleaned, List.copyOf(removed), true);
    }

    /**
     * 汇总各格式的检测结果并给出判定与置信度。
     */
    private static Report summarize(String format, boolean contentCredentials, boolean credentialsAi,
                                    String digitalSourceType, String generator, String generatorSource,
                                    boolean generationParams, List<String> extraSignals) {
        List<String> signals = new ArrayList<>(extraSignals);
        if (contentCredentials) {
            signals.add("存在 C2PA 内容凭证（JUMBF）");
        }
        if (digitalSourceType != null && !digitalSourceType.isEmpty()) {
            signals.add("IPTC DigitalSourceType = " + digitalSourceType);
        }
        if (generator != null && !generator.isEmpty()) {
            signals.add("生成器信息（" + generatorSource + "）：" + generator);
        }
        if (generationParams) {
            signals.add("检测到生成参数块（prompt/steps/sampler/seed 等）");
        }

        String dst = digitalSourceType == null ? "" : digitalSourceType.toLowerCase();
        if (dst.contains("compositewithtrainedalgorithmicmedia")) {
            return new Report(format, VERDICT_AI_EDITED, CONFIDENCE_HIGH,
                    contentCredentials, digitalSourceType, generator, generatorSource,
                    generationParams, signals);
        }
        if (dst.contains("algorithmiccorrection")) {
            signals.add("IPTC 口径：经算法校正/增强（AI 参与）");
            return new Report(format, VERDICT_AI_EDITED, CONFIDENCE_MEDIUM,
                    contentCredentials, digitalSourceType, generator, generatorSource,
                    generationParams, signals);
        }
        boolean explicitAi = credentialsAi
                || dst.contains("trainedalgorithmicmedia")
                || dst.contains("compositesynthetic")
                || dst.contains("algorithmicmedia")
                || dst.contains("generative");
        if (explicitAi) {
            return new Report(format, VERDICT_AI_GENERATED, CONFIDENCE_HIGH,
                    contentCredentials, digitalSourceType, generator, generatorSource,
                    generationParams, signals);
        }
        if (generator != null && !generator.isEmpty()) {
            return new Report(format, VERDICT_AI_GENERATED, CONFIDENCE_MEDIUM,
                    contentCredentials, digitalSourceType, generator, generatorSource,
                    generationParams, signals);
        }
        if (generationParams) {
            return new Report(format, VERDICT_AI_GENERATED, CONFIDENCE_MEDIUM,
                    contentCredentials, digitalSourceType, generator, generatorSource,
                    generationParams, signals);
        }
        if (contentCredentials) {
            signals.add("内容凭证未声明 AI 生成（不代表一定为实拍）");
            return new Report(format, VERDICT_NO_AI_SIGNALS, CONFIDENCE_MEDIUM,
                    contentCredentials, digitalSourceType, generator, generatorSource,
                    generationParams, signals);
        }
        return new Report(format, VERDICT_NO_AI_SIGNALS, CONFIDENCE_LOW,
                contentCredentials, digitalSourceType, generator, generatorSource,
                generationParams, signals);
    }

    // ==== 像素统计兜底分析（纯 JDK，只读采样，不修改原图）====

    /**
     * 像素统计兜底分析结果：命中证据数与信号描述。{@code hits <= 0} 表示未发现可疑特征。
     */
    private record PixelAnalysis(int hits, List<String> signals) {
    }

    /**
     * 频谱峰度量：行/列平均功率谱在上采样频点的显著度（峰高相对局部中位数的倍数），
     * 以及所选轴半谱的平坦度（中位数/均值），用于照片内容闸门。
     */
    private record SpectralPeak(double ratio, int period, String axis, double spread) {
    }

    /**
     * 像素统计中心裁剪的最大边长（控制解码与计算开销）。
     * 注意：不做降采样——偶数步长降采样会破坏周期 2/4 的上采样网格伪迹，
     * 导致最常见的 AI 输出尺寸（如 1024×1024）检测失效。
     */
    private static final int PIXEL_SAMPLE_MAX_SIDE = 1024;

    /**
     * 像素统计采样的最小边长（过小图像统计不可靠，跳过兜底）。
     */
    private static final int PIXEL_SAMPLE_MIN_SIDE = 96;

    /**
     * 谱峰显著度判定阈值：峰高需达到局部中位数的倍数（无损/低压缩格式）。
     */
    private static final double SPECTRAL_PEAK_RATIO = 3.0;

    /**
     * JPEG 的谱峰阈值更高：8×8 块结构与重压缩会在高频残留额外能量。
     */
    private static final double SPECTRAL_PEAK_RATIO_JPEG = 4.5;

    /**
     * 强谱峰阈值：达到该值时单独计为两条证据（置信度中）。
     */
    private static final double SPECTRAL_PEAK_STRONG = 8.0;

    /**
     * 照片内容闸门的纹理占比下限：内部像素中 |拉普拉斯残差|&gt;3 的占比低于该值时，
     * 视为截图/渐变/大面积平坦内容，其谱峰多来自压缩或抖动伪影，不计为上采样证据。
     *
     * <p>低于该值的图不再走上采样谱峰路径，改走「扁平数字内容」路径，以从严的
     * 跨通道残差相关（{@link #FLAT_CORR_MISSING}）作为唯一证据。</p>
     */
    private static final double PHOTOGRAPHIC_TEX_MIN = 0.20;

    /**
     * 照片内容闸门的纹理占比上限：占比过高（接近满幅强边缘）多为 CAD 填充图案、
     * 棋盘格等周期纹理，其谱峰来自图案本身的重复结构，不计为上采样证据。
     */
    private static final double PHOTOGRAPHIC_TEX_MAX = 0.80;

    /**
     * 照片内容闸门的谱平坦度下限：半谱中位数/均值低于该值说明谱能量高度集中于
     * 个别频点（合成图形的线谱）；上采样伪迹应叠加在连续谱之上，故不采信。
     */
    private static final double SPECTRAL_SPREAD_MIN = 0.05;

    /**
     * 跨通道残差相关高于该值时，视为缺少独立的相机传感器噪声特征（照片内容路径）。
     */
    private static final double CROSS_CHANNEL_CORR_MISSING = 0.85;

    /**
     * 照片内容路径的跨通道检查：亮度残差标准差下限（过低说明几乎无内容，统计不可靠）。
     */
    private static final double CROSS_CHANNEL_STD_MIN = 1.5;

    /**
     * 照片内容路径的跨通道检查：平均饱和度下限（过低说明近灰度，跨通道相关无意义）。
     */
    private static final double CROSS_CHANNEL_SAT_MIN = 4;

    /**
     * 扁平数字内容路径（纹理占比低于 {@link #PHOTOGRAPHIC_TEX_MIN}）的跨通道相关下限。
     *
     * <p>扁平数字内容（AI 平涂插画 / 手绘插画 / 截图）缺乏相机的独立传感器噪声，跨通道
     * 残差相关通常接近 1；为抑制对手绘扁平插画、logo、错误页等真实数字素材的误报，
     * 此处阈值明显高于照片路径（0.95 而非 0.85）。</p>
     */
    private static final double FLAT_CORR_MISSING = 0.95;

    /**
     * 扁平数字内容路径：亮度残差标准差下限（排除近乎纯色/纯渐变的平坦图）。
     *
     * <p>2026-11-26 由 10.0 下调到 8.0：实测一张裁切重压缩的 AI 平涂贴纸 σ=9.0、
     * 14 张真实界面截图的 σ 为 13.0~46.3，下调后截图侧仍由 {@link #FLAT_SAT_MIN}
     * 拦住，误报数为 0。</p>
     */
    private static final double FLAT_STD_MIN = 8.0;

    /**
     * 扁平数字内容路径：平均饱和度下限（排除近灰度图）。
     *
     * <p>2026-11-26 由 20.0 下调到 8.0：这是区分「近灰度界面截图」与「彩色平涂插画」
     * 的真正分界——界截图的 sat 实测只有 1.4~4.4，而 AI 平涂贴纸 sat=10.0，
     * 原 20.0 会把后者一并放过。下调到 8.0 后两侧仍留有 2 倍以上余量。</p>
     */
    private static final double FLAT_SAT_MIN = 8.0;

    /**
     * 照片路径与扁平路径的临界带上限（纹理占比）。
     *
     * <p>纹理占比落在 [{@link #PHOTOGRAPHIC_TEX_MIN}, 本值] 区间的图，既可能是照片，
     * 也可能是被裁切/重压缩破坏掉上采样网格伪迹的平涂插画——实测样本 tex=0.205
     * 只比下限高 0.005，分支选择对零点几个百分点极度敏感。该区间内照片路径若
     * 找不到谱峰（主证据缺失），补跑一次扁平路径，命中即判「疑似」，避免因分支
     * 边界而漏掉整类样本。</p>
     */
    private static final double PHOTOGRAPHIC_TEX_CRITICAL = 0.25;

    /**
     * 对图像做像素统计兜底分析（元数据无 AI 信号时使用）。
     *
     * <p>上采样（放大）的图像会在像素网格上留下周期 2/4 的网格伪迹：对拉普拉斯残差
     * 做行/列平均功率谱，在奈奎斯特频点（周期 2）或四分之一频点（周期 4）出现显著峰。
     * 相机照片还会带有跨通道相关的传感器噪声，生成图像通常缺失。</p>
     *
     * <p>按纹理占比分两条路径，控制误报：</p>
     * <ul>
     *   <li><b>照片内容</b>（纹理占比处于 [0.20, 0.80]）：以谱峰为上采样证据，跨通道
     *       噪声缺失作为加重证据；谱峰须先通过谱连续度闸门，排除截图、渐变、周期填充
     *       图案等对谱峰的误触发；</li>
     *   <li><b>临界带补跑</b>（纹理占比处于 [0.20, 0.25]）：照片路径找不到谱峰时，补跑
     *       一次扁平路径。裁切/重压缩会破坏上采样网格伪迹，使平涂插画被分到照片路径后
     *       直接放过；补跑命中仍只计一条证据（结论为「疑似」）。</li>
     *   <li><b>扁平数字内容</b>（纹理占比 &lt; 0.20，如 AI 平涂插画、手绘插画、截图）：
     *       上采样谱峰不可靠（常因大面积平坦内容而消失），改用「跨通道残差相关异常偏高
     *       （ρ ≥ 0.95）+ 残差强度 σ ≥ 8 + 平均饱和度 sat ≥ 8」作为强证据。数字生成/绘制
     *       的平坦图缺少相机独立的传感器噪声，相关接近 1；阈值明显高于照片路径以抑制
     *       对真实数字素材的误报。此路径为启发式，对本类素材仍可能存在误报。</li>
     * </ul>
     *
     * @param data   图像文件字节
     * @param format 已识别的格式名（JPEG/PNG/WebP），用于按压缩格式调整判定阈值
     * @return 分析结果；图像无法解码、过小或不适合统计时返回 {@code null}
     */
    private static PixelAnalysis analyzePixels(byte[] data, String format) {
        BufferedImage img = decodeForPixelStatistics(data);
        if (img == null) {
            return null;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if (w < PIXEL_SAMPLE_MIN_SIDE || h < PIXEL_SAMPLE_MIN_SIDE) {
            return null;
        }
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        int n = w * h;
        double[] luma = new double[n];
        double[] red = new double[n];
        double[] green = new double[n];
        double[] blue = new double[n];
        for (int i = 0; i < n; i++) {
            int argb = pixels[i];
            int a = (argb >>> 24) & 0xFF;
            int r = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int b = argb & 0xFF;
            if (a != 255) {
                // 透明/半透明像素的 RGB 无意义（解码器通常给 0），直接参与统计会把贴纸类
                // PNG 的跨通道相关与饱和度算坏（实测透明底贴纸 ρ 从 0.98 掉到 0.47）。
                // 统一按 alpha 合成到白底，保证统计量只反映可见像素。
                r = (r * a + 255 * (255 - a)) / 255;
                g = (g * a + 255 * (255 - a)) / 255;
                b = (b * a + 255 * (255 - a)) / 255;
            }
            red[i] = r;
            green[i] = g;
            blue[i] = b;
            luma[i] = 0.299 * r + 0.587 * g + 0.114 * b;
        }
        pixels = null;
        double[] lap = laplacian(luma, w, h);
        double texFraction = textureFraction(lap, w, h);
        List<String> signals = new ArrayList<>();
        int hits;
        if (texFraction > PHOTOGRAPHIC_TEX_MAX) {
            // 纹理占比过高（接近满幅强边缘）：CAD 填充、棋盘格等周期纹理，
            // 谱峰来自图案本身的重复结构，不作为上采样证据。
            return new PixelAnalysis(0, List.of());
        }
        if (texFraction >= PHOTOGRAPHIC_TEX_MIN) {
            // 照片内容：以上采样网格伪迹（谱峰）为主证据，跨通道噪声缺失为加重证据。
            SpectralPeak peak = measureSpectralPeak(lap, w, h, format);
            if (peak == null) {
                // 临界带补跑：裁切/重压缩会破坏网格伪迹，使平涂插画被分到照片路径后
                // 直接放过；该区间内再给扁平路径一次机会（命中仍只算「疑似」）。
                if (texFraction <= PHOTOGRAPHIC_TEX_CRITICAL) {
                    String noise = crossChannelNoiseSignal(red, green, blue, lap, w, h,
                            FLAT_CORR_MISSING, FLAT_STD_MIN, FLAT_SAT_MIN);
                    if (noise != null) {
                        return new PixelAnalysis(1, List.of(noise));
                    }
                }
                return new PixelAnalysis(0, List.of());
            }
            hits = peak.ratio() >= SPECTRAL_PEAK_STRONG ? 2 : 1;
            signals.add(String.format(
                    "像素统计：功率谱在周期 %d 频点呈显著峰（%s方向显著度 %.1f 倍于局部中位），符合上采样网格伪迹特征",
                    peak.period(), peak.axis(), peak.ratio()));
            String noise = crossChannelNoiseSignal(red, green, blue, lap, w, h,
                    CROSS_CHANNEL_CORR_MISSING, CROSS_CHANNEL_STD_MIN, CROSS_CHANNEL_SAT_MIN);
            if (noise != null) {
                signals.add(noise);
                hits++;
            }
        } else {
            // 扁平数字内容（AI 平涂插画 / 手绘插画 / 截屏）：上采样谱峰不可靠，
            // 改用「跨通道残差相关异常偏高」这一强证据，阈值从严以抑制误报。
            String noise = crossChannelNoiseSignal(red, green, blue, lap, w, h,
                    FLAT_CORR_MISSING, FLAT_STD_MIN, FLAT_SAT_MIN);
            if (noise == null) {
                return new PixelAnalysis(0, List.of());
            }
            signals.add(noise);
            hits = 1;
        }
        return new PixelAnalysis(hits, signals);
    }

    /**
     * 纹理占比：内部像素中拉普拉斯残差绝对值大于 3 的比例。照片内容通常有明显
     * 占比；截图/渐变等大面积平坦内容占比极低，周期填充图案占比接近 1。
     */
    private static double textureFraction(double[] lap, int w, int h) {
        long count = 0;
        long total = 0;
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                if (Math.abs(lap[row + x]) > 3) {
                    count++;
                }
                total++;
            }
        }
        return total == 0 ? 0 : (double) count / total;
    }

    /**
     * 跨通道传感器噪声特征缺失检查。
     *
     * <p>相机照片的拉普拉斯残差 = 共享的图像结构 + 各通道相对独立的传感器噪声，
     * 跨通道残差相关为中等水平；生成图像缺少独立的传感器噪声，残差几乎只剩共享
     * 结构，跨通道相关异常偏高。仅当图像确实存在纹理/噪声（亮度残差标准差足够大）
     * 且非近灰度图时才判定，避免对平滑图与单色图误报。</p>
     *
     * @param corrMin 跨通道相关下限（高于该值视为缺失独立传感器噪声）
     * @param stdMin  亮度残差标准差下限（过低说明几乎无内容）
     * @param satMin  平均饱和度下限（过低说明近灰度，跨通道相关无意义）
     * @return 命中时的信号描述；未达阈值返回 {@code null}
     */
    private static String crossChannelNoiseSignal(double[] red, double[] green, double[] blue,
                                                  double[] lumaLap, int w, int h,
                                                  double corrMin, double stdMin, double satMin) {
        double satSum = 0;
        for (int i = 0; i < red.length; i++) {
            double mx = Math.max(red[i], Math.max(green[i], blue[i]));
            double mn = Math.min(red[i], Math.min(green[i], blue[i]));
            satSum += mx - mn;
        }
        if (satSum / red.length < satMin) {
            return null;
        }
        double[] rLap = laplacian(red, w, h);
        double[] gLap = laplacian(green, w, h);
        double[] bLap = laplacian(blue, w, h);
        double corr = (corrSamePosition(rLap, gLap) + corrSamePosition(rLap, bLap) + corrSamePosition(gLap, bLap)) / 3;
        double sum = 0;
        double sumSq = 0;
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double v = lumaLap[y * w + x];
                sum += v;
                sumSq += v * v;
                count++;
            }
        }
        double variance = sumSq / count - (sum / count) * (sum / count);
        double std = variance > 0 ? Math.sqrt(variance) : 0;
        if (std < stdMin || corr < corrMin) {
            return null;
        }
        return String.format("像素统计：跨通道残差相关性异常偏高（平均 ρ=%.2f，亮度残差 σ=%.1f），缺少独立的相机传感器噪声特征", corr, std);
    }

    /**
     * 度量拉普拉斯残差行/列平均功率谱在上采样频点的显著峰。
     *
     * <p>上采样（放大）会在像素网格上留下周期 2/4 的网格伪迹：残差功率谱在奈奎斯特
     * 频点（周期 2）或四分之一频点（周期 4）出现显著峰。对每行/列残差加 Hann 窗做
     * FFT，沿另一维平均得到平均功率谱，用峰高相对局部中位数的倍数衡量显著度，以
     * 排除图像内容与压缩块结构的干扰。JPEG 的 8×8 块边界也会贡献高频能量，故 JPEG
     * 采用更高阈值。</p>
     *
     * @return 最显著的谱峰；未达到阈值时返回 {@code null}
     */
    private static SpectralPeak measureSpectralPeak(double[] residual, int w, int h, String format) {
        double threshold = "JPEG".equals(format) ? SPECTRAL_PEAK_RATIO_JPEG : SPECTRAL_PEAK_RATIO;
        double[][] spectra = averagedPowerSpectra(residual, w, h);
        int[] periods = {2, 4};
        String[] axes = {"水平", "垂直"};
        SpectralPeak best = null;
        for (int axis = 0; axis < 2; axis++) {
            double[] spectrum = spectra[axis];
            int n = spectrum.length;
            // 照片内容闸门：上采样伪迹应叠加在连续谱之上；线谱（合成图形）不采信
            double spread = spectralSpread(spectrum);
            // 组合峰可能因包络调制偏离理想谐波 bin 若干 bin（边带效应），在带内取最大峰
            int band = Math.max(4, n / 64);
            for (int period : periods) {
                for (int j = 1; j < period; j++) {
                    int ideal = (int) Math.round((double) n * j / period);
                    if (ideal < 4 || ideal > n / 2) {
                        continue;
                    }
                    int lo = Math.max(4, ideal - band);
                    int hi = Math.min(n / 2, ideal + band);
                    int peakBin = lo;
                    for (int b = lo; b <= hi; b++) {
                        if (spectrum[b] > spectrum[peakBin]) {
                            peakBin = b;
                        }
                    }
                    double ratio = peakProminence(spectrum, peakBin, 8);
                    if (ratio >= threshold && spread >= SPECTRAL_SPREAD_MIN
                            && (best == null || ratio > best.ratio())) {
                        best = new SpectralPeak(ratio, period, axes[axis], spread);
                    }
                }
            }
        }
        return best;
    }

    /**
     * 谱平坦度：半谱（bin 4..n/2）中位数与均值之比。照片内容的连续谱该值明显
     * 大于合成图形/纯周期图案的线谱。
     */
    private static double spectralSpread(double[] spectrum) {
        int n = spectrum.length;
        int to = n / 2;
        if (to < 4) {
            return 0;
        }
        double[] half = new double[to - 4 + 1];
        double sum = 0;
        for (int i = 4; i <= to; i++) {
            half[i - 4] = spectrum[i];
            sum += spectrum[i];
        }
        Arrays.sort(half);
        double median = half.length % 2 == 1
                ? half[half.length / 2]
                : (half[half.length / 2 - 1] + half[half.length / 2]) / 2;
        double mean = sum / half.length;
        return mean <= 1e-12 ? 0 : median / mean;
    }

    /**
     * 行/列方向平均功率谱：对每行（列）残差加 Hann 窗做 FFT，功率沿另一维平均。
     * FFT 长度取不小于 2 倍边长的 2 的幂，避免循环卷绕影响高频估计。
     */
    private static double[][] averagedPowerSpectra(double[] residual, int w, int h) {
        int n = Math.max(4, Integer.highestOneBit(Math.max(w, h) * 2 - 1) << 1);
        double[] rowPower = new double[n];
        double[] colPower = new double[n];
        double[] re = new double[n];
        double[] im = new double[n];
        double[] winX = new double[w];
        for (int x = 0; x < w; x++) {
            winX[x] = 0.5 - 0.5 * Math.cos(2 * Math.PI * x / (w - 1));
        }
        double[] winY = new double[h];
        for (int y = 0; y < h; y++) {
            winY[y] = 0.5 - 0.5 * Math.cos(2 * Math.PI * y / (h - 1));
        }
        for (int y = 1; y < h - 1; y++) {
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            int row = y * w;
            for (int x = 0; x < w; x++) {
                re[x] = residual[row + x] * winX[x];
            }
            fft(re, im);
            for (int b = 0; b < n; b++) {
                rowPower[b] += re[b] * re[b] + im[b] * im[b];
            }
        }
        for (int x = 1; x < w - 1; x++) {
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            for (int y = 0; y < h; y++) {
                re[y] = residual[y * w + x] * winY[y];
            }
            fft(re, im);
            for (int b = 0; b < n; b++) {
                colPower[b] += re[b] * re[b] + im[b] * im[b];
            }
        }
        for (int b = 0; b < n; b++) {
            rowPower[b] /= Math.max(1, h - 2);
            colPower[b] /= Math.max(1, w - 2);
        }
        return new double[][]{rowPower, colPower};
    }

    /**
     * 峰显著度：P[bin] 相对窗口内（排除峰两侧 ±2）中位数的倍数。
     */
    private static double peakProminence(double[] power, int bin, int half) {
        int lo = Math.max(1, bin - half);
        int hi = Math.min(power.length - 1, bin + half);
        if (hi - lo < 4 || bin < lo || bin > hi) {
            return 0;
        }
        double[] window = new double[hi - lo + 1];
        int m = 0;
        for (int i = lo; i <= hi; i++) {
            if (Math.abs(i - bin) <= 2) {
                continue;
            }
            window[m++] = power[i];
        }
        if (m == 0) {
            return 0;
        }
        Arrays.sort(window, 0, m);
        double median = (m % 2 == 1) ? window[m / 2] : (window[m / 2 - 1] + window[m / 2]) / 2;
        if (median <= 1e-12) {
            return 0;
        }
        return power[bin] / median;
    }

    /**
     * 迭代基-2 FFT（就地计算）。
     */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j |= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    /**
     * 解码图像用于像素统计：读取全分辨率原图，超过 {@link #PIXEL_SAMPLE_MAX_SIDE}
     * 边长时取中心区域裁剪。<b>不做降采样</b>——偶数步长降采样会使周期 2/4 的
     * 上采样网格伪迹在采样网格上混叠消失，导致检测失效。
     */
    private static BufferedImage decodeForPixelStatistics(byte[] data) {
        ImageInputStream stream = null;
        try {
            stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
            if (stream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 80_000_000L) {
                    return null;
                }
                BufferedImage full = reader.read(0, reader.getDefaultReadParam());
                if (full == null) {
                    return null;
                }
                int cropWidth = Math.min(width, PIXEL_SAMPLE_MAX_SIDE);
                int cropHeight = Math.min(height, PIXEL_SAMPLE_MAX_SIDE);
                if (cropWidth == width && cropHeight == height) {
                    return full;
                }
                int x0 = (width - cropWidth) / 2;
                int y0 = (height - cropHeight) / 2;
                return full.getSubimage(x0, y0, cropWidth, cropHeight);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignore) {
                    // 忽略关闭异常
                }
            }
        }
    }

    /**
     * 计算拉普拉斯残差（4 邻域），边界置 0。
     */
    private static double[] laplacian(double[] v, int w, int h) {
        double[] out = new double[w * h];
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                out[i] = 4 * v[i] - v[i - 1] - v[i + 1] - v[i - w] - v[i + w];
            }
        }
        return out;
    }

    /**
     * 同位置皮尔逊相关系数（用于跨通道残差）。
     */
    private static double corrSamePosition(double[] a, double[] b) {
        int count = a.length;
        double sa = 0;
        double sb = 0;
        double saa = 0;
        double sbb = 0;
        double sab = 0;
        for (int i = 0; i < count; i++) {
            double x = a[i];
            double y = b[i];
            sa += x;
            sb += y;
            saa += x * x;
            sbb += y * y;
            sab += x * y;
        }
        double cov = sab - sa * sb / count;
        double va = saa - sa * sa / count;
        double vb = sbb - sb * sb / count;
        if (va <= 1e-9 || vb <= 1e-9) {
            return 0;
        }
        return cov / Math.sqrt(va * vb);
    }

    // ==== 格式识别 ====

    /**
     * 是否为 JPEG（SOI: FF D8）。
     */
    private static boolean isJpeg(byte[] data) {
        return data != null && data.length >= 4
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
    }

    /**
     * 是否为 PNG（8 字节签名）。
     */
    private static boolean isPng(byte[] data) {
        return data != null && data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }

    /**
     * 是否为 WebP（RIFF....WEBP）。
     */
    private static boolean isWebp(byte[] data) {
        return data != null && data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    // ==== JPEG：段扫描与 AI 痕迹分析 ====

    /**
     * JPEG 段（marker + 载荷）。
     */
    private record JpegSegment(int marker, byte[] payload) {
    }

    /**
     * EXIF 解析结果（仅保留与 AI 判定相关的文本字段）。
     */
    private record ExifInfo(String software, String userComment, String imageDescription,
                            String make, String model, String artist, String copyright,
                            int orientation) {

        static ExifInfo empty() {
            return new ExifInfo("", "", "", "", "", "", "", -1);
        }
    }

    /**
     * 扫描 JPEG 段结构。返回头部扫描结束位置（SOS 之后的熵编码数据需另行原样保留）。
     *
     * @param data     JPEG 文件字节
     * @param segments 输出参数：解析出的段列表
     * @return 头部扫描结束偏移
     */
    private static int readJpegSegments(byte[] data, List<JpegSegment> segments) {
        int i = 2;
        while (i + 4 <= data.length) {
            if ((data[i] & 0xFF) != 0xFF) {
                break;
            }
            int marker = data[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            if (marker == 0xD9) {
                i += 2;
                break;
            }
            int length = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            if (length < 2) {
                break;
            }
            int payloadStart = i + 4;
            int payloadLength = length - 2;
            if (payloadStart + payloadLength > data.length) {
                payloadLength = Math.max(0, data.length - payloadStart);
            }
            byte[] payload = new byte[payloadLength];
            System.arraycopy(data, payloadStart, payload, 0, payloadLength);
            segments.add(new JpegSegment(marker, payload));
            i = payloadStart + payloadLength;
            if (marker == 0xDA) {
                break;
            }
        }
        return Math.min(i, data.length);
    }

    /**
     * 分析 JPEG 的 XMP/EXIF/C2PA/注释段中的 AI 痕迹。
     */
    private static Report analyzeJpeg(byte[] data) {
        boolean contentCredentials = false;
        boolean credentialsAi = false;
        String xmp = null;
        ExifInfo exif = ExifInfo.empty();
        List<String> comments = new ArrayList<>();
        List<String> extraSignals = new ArrayList<>();

        var segments = new ArrayList<JpegSegment>();
        readJpegSegments(data, segments);
        for (JpegSegment segment : segments) {
            if (segment.marker() == 0xE1) {
                byte[] payload = segment.payload();
                if (startsWith(payload, JPEG_XMP_PREFIX)) {
                    xmp = new String(payload, JPEG_XMP_PREFIX.length,
                            payload.length - JPEG_XMP_PREFIX.length, StandardCharsets.UTF_8);
                } else if (startsWith(payload, JPEG_EXIF_PREFIX)) {
                    exif = parseExif(subarray(payload, JPEG_EXIF_PREFIX.length));
                }
            } else if (segment.marker() == 0xEB) {
                contentCredentials = true;
                String jumbf = new String(segment.payload(), StandardCharsets.ISO_8859_1);
                if (containsAnyIgnoreCase(jumbf, AI_LABEL_KEYWORDS)) {
                    credentialsAi = true;
                    extraSignals.add("C2PA 内容凭证中声明了 AI 生成/编辑动作");
                }
            } else if (segment.marker() == 0xFE) {
                comments.add(new String(segment.payload(), StandardCharsets.ISO_8859_1));
            }
        }
        return summarizeFromTexts("JPEG", contentCredentials, credentialsAi, xmp, exif,
                String.join("\n", comments), extraSignals);
    }

    /**
     * 清除 JPEG 中的 AI 元数据段（APP11 C2PA、含 AI 痕迹的 XMP/注释）。
     * EXIF 段保持原样以保留 Orientation 等结构；SOS 之后的熵编码数据原样拷贝。
     */
    private static byte[] stripJpeg(byte[] data, List<String> removed) {
        var segments = new ArrayList<JpegSegment>();
        int headerEnd = readJpegSegments(data, segments);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(0xFF);
        out.write(0xD8);
        for (JpegSegment segment : segments) {
            int marker = segment.marker();
            byte[] payload = segment.payload();
            if (marker == 0xEB) {
                removed.add("JPEG APP11（C2PA/JUMBF 内容凭证）");
                continue;
            }
            if (marker == 0xE1 && startsWith(payload, JPEG_XMP_PREFIX)) {
                String xmp = new String(payload, JPEG_XMP_PREFIX.length,
                        payload.length - JPEG_XMP_PREFIX.length, StandardCharsets.UTF_8);
                if (containsAiSignals(xmp)) {
                    String filtered = filterXmp(xmp);
                    if (containsAiSignals(filtered) || !fitsInApp1(filtered)) {
                        removed.add("JPEG APP1 XMP 段（含 AI 痕迹，整段移除）");
                    } else {
                        byte[] newPayload = new byte[JPEG_XMP_PREFIX.length + filtered.length()];
                        System.arraycopy(JPEG_XMP_PREFIX, 0, newPayload, 0, JPEG_XMP_PREFIX.length);
                        byte[] text = filtered.getBytes(StandardCharsets.UTF_8);
                        System.arraycopy(text, 0, newPayload, JPEG_XMP_PREFIX.length, text.length);
                        writeJpegSegment(out, 0xE1, newPayload);
                        removed.add("JPEG APP1 XMP 中的 AI 标签已清除");
                    }
                } else {
                    writeJpegSegment(out, marker, payload);
                }
                continue;
            }
            if (marker == 0xFE && containsAiSignals(new String(payload, StandardCharsets.ISO_8859_1))) {
                removed.add("JPEG 注释段（含 AI 痕迹）");
                continue;
            }
            writeJpegSegment(out, marker, payload);
        }
        if (headerEnd < data.length) {
            out.write(data, headerEnd, data.length - headerEnd);
        }
        return out.toByteArray();
    }

    /**
     * 写回一个 JPEG 段（marker + 长度 + 载荷）。
     */
    private static void writeJpegSegment(ByteArrayOutputStream out, int marker, byte[] payload) {
        out.write(0xFF);
        out.write(marker & 0xFF);
        int length = Math.min(payload.length + 2, 0xFFFF);
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(payload, 0, Math.min(payload.length, length - 2));
    }

    // ==== EXIF/TIFF 解析 ====

    /**
     * 解析 EXIF/TIFF 字节，提取 IFD0 与 Exif 子 IFD 中的常用文本标签。
     *
     * @param tiff 以「II/MM」字节序头开始的 TIFF 数据
     */
    private static ExifInfo parseExif(byte[] tiff) {
        if (tiff == null || tiff.length < 8) {
            return ExifInfo.empty();
        }
        boolean bigEndian = tiff[0] == 'M' && tiff[1] == 'M';
        boolean littleEndian = tiff[0] == 'I' && tiff[1] == 'I';
        if (!bigEndian && !littleEndian) {
            return ExifInfo.empty();
        }
        Map<Integer, String> values = new LinkedHashMap<>();
        int ifd0 = (int) readUnsignedInt(tiff, 4, bigEndian);
        collectIfdTexts(tiff, ifd0, bigEndian, values, 0);
        int orientation = values.containsKey(TAG_ORIENTATION)
                ? parseIntSafely(values.get(TAG_ORIENTATION)) : -1;
        return new ExifInfo(
                orEmpty(values.get(TAG_SOFTWARE)),
                orEmpty(values.get(TAG_USER_COMMENT)),
                orEmpty(values.get(TAG_IMAGE_DESCRIPTION)),
                orEmpty(values.get(TAG_MAKE)),
                orEmpty(values.get(TAG_MODEL)),
                orEmpty(values.get(TAG_ARTIST)),
                orEmpty(values.get(TAG_COPYRIGHT)),
                orientation);
    }

    /**
     * 递归读取 IFD 中的文本类标签（ASCII/UNDEFINED/单值 SHORT）。
     */
    private static void collectIfdTexts(byte[] tiff, int ifdOffset, boolean bigEndian,
                                        Map<Integer, String> values, int depth) {
        if (ifdOffset <= 0 || ifdOffset + 2 > tiff.length || depth > 2) {
            return;
        }
        int count = readUnsignedShort(tiff, ifdOffset, bigEndian);
        for (int i = 0; i < count; i++) {
            int entry = ifdOffset + 2 + i * 12;
            if (entry + 12 > tiff.length) {
                return;
            }
            int tag = readUnsignedShort(tiff, entry, bigEndian);
            int type = readUnsignedShort(tiff, entry + 2, bigEndian);
            long valueCount = readUnsignedInt(tiff, entry + 4, bigEndian) & 0xFFFFFFFFL;
            if (tag == TAG_EXIF_IFD && type == 4 && valueCount >= 1) {
                int subIfd = (int) (readUnsignedInt(tiff, entry + 8, bigEndian) & 0xFFFFFFFFL);
                collectIfdTexts(tiff, subIfd, bigEndian, values, depth + 1);
                continue;
            }
            String text = readTextValue(tiff, entry, type, valueCount, bigEndian);
            if (text != null && !values.containsKey(tag)) {
                values.put(tag, text);
            }
        }
    }

    /**
     * 读取单个标签的文本值（仅 ASCII/UNDEFINED/SHORT）。
     */
    private static String readTextValue(byte[] tiff, int entry, int type, long valueCount,
                                        boolean bigEndian) {
        if (valueCount <= 0 || valueCount > 1_000_000L) {
            return null;
        }
        int unit = typeSize(type);
        if (unit <= 0) {
            return null;
        }
        long total = unit * valueCount;
        int valueOffset;
        if (total <= 4) {
            valueOffset = entry + 8;
        } else {
            long offset = readUnsignedInt(tiff, entry + 8, bigEndian) & 0xFFFFFFFFL;
            if (offset <= 0 || offset >= tiff.length) {
                return null;
            }
            valueOffset = (int) offset;
        }
        int length = (int) Math.min(total, tiff.length - valueOffset);
        if (length <= 0) {
            return null;
        }
        if (type == 2) {
            return decodeAscii(tiff, valueOffset, length);
        }
        if (type == 7) {
            if (length <= 8) {
                return null;
            }
            String charset = new String(tiff, valueOffset, 8, StandardCharsets.ISO_8859_1);
            if (charset.startsWith("UNICODE")) {
                return new String(tiff, valueOffset + 8, length - 8, StandardCharsets.UTF_16BE)
                        .replace("\u0000", "").trim();
            }
            if (charset.startsWith("JIS")) {
                return null;
            }
            return decodeAscii(tiff, valueOffset + 8, length - 8);
        }
        if (type == 3 && valueCount == 1) {
            return String.valueOf(readUnsignedShort(tiff, valueOffset, bigEndian));
        }
        return null;
    }

    /**
     * EXIF 数据类型对应的字节宽度。
     */
    private static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> -1;
        };
    }

    /**
     * 解码 EXIF ASCII 字符串：先按 UTF-8，出现替换符时退回 ISO-8859-1。
     */
    private static String decodeAscii(byte[] data, int offset, int length) {
        int end = offset + length;
        while (end > offset && data[end - 1] == 0) {
            end--;
        }
        if (end <= offset) {
            return "";
        }
        String utf8 = new String(data, offset, end - offset, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1);
        }
        return utf8;
    }

    /**
     * 按指定字节序读取无符号 16 位整数。
     */
    private static int readUnsignedShort(byte[] data, int offset, boolean bigEndian) {
        if (offset < 0 || offset + 2 > data.length) {
            return 0;
        }
        int a = data[offset] & 0xFF;
        int b = data[offset + 1] & 0xFF;
        return bigEndian ? (a << 8) | b : (b << 8) | a;
    }

    /**
     * 按指定字节序读取无符号 32 位整数。
     */
    private static long readUnsignedInt(byte[] data, int offset, boolean bigEndian) {
        if (offset < 0 || offset + 4 > data.length) {
            return 0;
        }
        long a = data[offset] & 0xFFL;
        long b = data[offset + 1] & 0xFFL;
        long c = data[offset + 2] & 0xFFL;
        long d = data[offset + 3] & 0xFFL;
        return bigEndian ? (a << 24) | (b << 16) | (c << 8) | d : (d << 24) | (c << 16) | (b << 8) | a;
    }

    // ==== AI 信号判定与 XMP 过滤 ====

    /**
     * 判断一段文本（XMP/注释/参数块）是否含有 AI 痕迹：显式来源类型、
     * 强信号生成器名称、生成参数块，或 CreatorTool 字段中的生成器名称。
     */
    private static boolean containsAiSignals(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        if (findDigitalSourceType(text) != null) {
            return true;
        }
        if (findGenerator(text, true) != null) {
            return true;
        }
        if (looksLikeGenerationParams(text)) {
            return true;
        }
        String creatorTool = findCreatorTool(text);
        if (creatorTool != null && findGenerator(creatorTool, false) != null) {
            return true;
        }
        String agent = findSoftwareAgent(text);
        return agent != null && findGenerator(agent, false) != null;
    }

    /**
     * 在文本中查找 AI 生成器名称。
     *
     * @param text       待检索文本
     * @param strongOnly true 时仅匹配强信号关键词（降低整段文本误报）
     * @return 命中的生成器名称（适合展示），未命中返回 null
     */
    private static String findGenerator(String text, boolean strongOnly) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String lower = text.toLowerCase();
        for (String keyword : GENERATOR_STRONG) {
            if (lower.contains(keyword)) {
                return capitalize(keyword);
            }
        }
        if (!strongOnly) {
            for (String keyword : GENERATOR_WEAK) {
                if (lower.contains(keyword)) {
                    return keyword;
                }
            }
        }
        return null;
    }

    /**
     * 提取 IPTC DigitalSourceType（元素文本或属性两种形式）。
     */
    private static String findDigitalSourceType(String xmp) {
        if (StringUtils.isBlank(xmp)) {
            return null;
        }
        Matcher element = P_DST_ELEMENT.matcher(xmp);
        if (element.find()) {
            return element.group(1).trim();
        }
        Matcher attribute = P_DST_ATTRIBUTE.matcher(xmp);
        if (attribute.find()) {
            return attribute.group(1).trim();
        }
        return null;
    }

    /**
     * 提取 XMP xmp:CreatorTool 的文本值。
     */
    private static String findCreatorTool(String xmp) {
        if (StringUtils.isBlank(xmp)) {
            return null;
        }
        Matcher creator = P_CREATOR_TOOL.matcher(xmp);
        return creator.find() ? creator.group(2).trim() : null;
    }

    /**
     * 提取 XMP xmpMM:History 的 softwareAgent（处理历史条目的属性/元素两种形式）。
     */
    private static String findSoftwareAgent(String xmp) {
        if (StringUtils.isBlank(xmp)) {
            return null;
        }
        Matcher attr = P_SOFTWARE_AGENT_ATTR.matcher(xmp);
        if (attr.find()) {
            return attr.group(1).trim();
        }
        Matcher element = P_SOFTWARE_AGENT_ELEMENT.matcher(xmp);
        return element.find() ? element.group(1).trim() : null;
    }

    /**
     * 是否为承载生成器/生成参数的 PNG 文本块关键字：
     * A1111 的 {@code parameters}、ComfyUI 的 {@code prompt}/{@code workflow}、
     * NovelAI 的 {@code Software}/{@code Comment}（JSON）、InvokeAI 的 {@code sd-metadata}。
     */
    private static boolean isGenerationTextKeyword(String keyword, String value) {
        if (PNG_PARAMS_KEYWORD.equalsIgnoreCase(keyword) || PNG_SOFTWARE_KEYWORD.equalsIgnoreCase(keyword)
                || PNG_PROMPT_KEYWORD.equalsIgnoreCase(keyword) || PNG_WORKFLOW_KEYWORD.equalsIgnoreCase(keyword)
                || PNG_SD_METADATA_KEYWORD.equalsIgnoreCase(keyword)) {
            return true;
        }
        // Comment 是常见通用关键字，仅当内容为 JSON（NovelAI 参数块）时按生成参数处理
        return PNG_COMMENT_KEYWORD.equalsIgnoreCase(keyword) && value != null && value.trim().startsWith("{");
    }

    /**
     * 是否像生成参数块。按各工具的真实落盘格式识别：
     * <ul>
     *   <li>AUTOMATIC1111 系（PNG parameters / EXIF UserComment）：{@code Steps:} /
     *       {@code Sampler:} / {@code CFG scale:} / {@code Seed:} / {@code Model hash:} 行；</li>
     *   <li>ComfyUI：{@code prompt} / {@code workflow} JSON（节点表含 class_type 与采样参数）；</li>
     *   <li>NovelAI：{@code Comment} JSON（prompt/steps/seed/sampler 键）；</li>
     *   <li>InvokeAI：{@code sd-metadata} JSON；</li>
     *   <li>通用扩散参数块：prompt + (sampler/steps) + (seed/cfg)。</li>
     * </ul>
     */
    private static boolean looksLikeGenerationParams(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String lower = text.toLowerCase();
        if (lower.contains("negative prompt")
                && (lower.contains("steps") || lower.contains("sampler")
                || lower.contains("seed") || lower.contains("cfg"))) {
            return true;
        }
        if (lower.contains("[workflow]") || lower.contains("[prompt]")
                || lower.contains("[sd-metadata]")) {
            return true;
        }
        if (lower.contains("class_type") && (lower.contains("seed") || lower.contains("steps"))) {
            return true;
        }
        if (!lower.contains("prompt")) {
            return false;
        }
        boolean hasSampler = lower.contains("sampler") || lower.contains("steps");
        boolean hasSeed = lower.contains("seed") || lower.contains("cfg");
        return hasSampler && hasSeed;
    }

    /**
     * 由 EXIF + XMP + 额外文本汇总检测报告（JPEG/PNG/WebP 共用）。
     */
    private static Report summarizeFromTexts(String format, boolean contentCredentials,
                                             boolean credentialsAi, String xmp, ExifInfo exif,
                                             String extraText, List<String> extraSignals) {
        String digitalSourceType = findDigitalSourceType(xmp);
        String generator = null;
        String generatorSource = null;
        if (!StringUtils.isBlank(exif.software())) {
            String found = findGenerator(exif.software(), false);
            if (found != null) {
                generator = found;
                generatorSource = "EXIF Software";
            }
        }
        if (generator == null && !StringUtils.isBlank(exif.userComment())) {
            String found = findGenerator(exif.userComment(), false);
            if (found != null) {
                generator = found;
                generatorSource = "EXIF UserComment";
            }
        }
        if (generator == null && !StringUtils.isBlank(xmp)) {
            String creatorTool = findCreatorTool(xmp);
            if (!StringUtils.isBlank(creatorTool)) {
                String found = findGenerator(creatorTool, false);
                if (found != null) {
                    generator = found;
                    generatorSource = "XMP CreatorTool";
                }
            }
        }
        if (generator == null && !StringUtils.isBlank(xmp)) {
            String agent = findSoftwareAgent(xmp);
            if (!StringUtils.isBlank(agent)) {
                String found = findGenerator(agent, false);
                if (found != null) {
                    generator = found;
                    generatorSource = "XMP softwareAgent";
                }
            }
        }
        if (generator == null && !StringUtils.isBlank(extraText)) {
            String found = findGenerator(extraText, true);
            if (found != null) {
                generator = found;
                generatorSource = "图像内嵌文本";
            }
        }
        boolean generationParams = looksLikeGenerationParams(exif.userComment())
                || looksLikeGenerationParams(xmp)
                || looksLikeGenerationParams(exif.imageDescription())
                || looksLikeGenerationParams(extraText);
        return summarize(format, contentCredentials, credentialsAi, orEmpty(digitalSourceType),
                orEmpty(generator), orEmpty(generatorSource), generationParams, extraSignals);
    }

    /**
     * 过滤 XMP 包中的 AI 相关内容：删除 DigitalSourceType、C2PA/内容凭证节点，
     * 以及内容为生成器名称的 CreatorTool / softwareAgent；其余内容原样保留。
     */
    private static String filterXmp(String xmp) {
        String result = xmp;
        result = result.replaceAll("(?is)<[\\w.-]*:DigitalSourceType\\b[^>]*/>", " ");
        result = result.replaceAll("(?is)<([\\w.-]*:DigitalSourceType)\\b[^>]*>.*?</\\s*\\1\\s*>", " ");
        result = result.replaceAll("(?i)[\\w.-]*:DigitalSourceType=\"[^\"]*\"", " ");
        result = result.replaceAll("(?is)<[\\w.-]*(?:c2pa|ContentCredentials|JUMBF)[\\w.-]*\\b[^>]*/>", " ");
        result = result.replaceAll("(?is)<([\\w.-]*(?:c2pa|ContentCredentials|JUMBF)[\\w.-]*)\\b[^>]*>.*?</\\s*\\1\\s*>", " ");
        result = removeGeneratorFields(result, P_CREATOR_TOOL, 2);
        result = removeGeneratorFields(result, P_SOFTWARE_AGENT_ATTR, 1);
        result = removeGeneratorFields(result, P_SOFTWARE_AGENT_ELEMENT, 1);
        return padXmp(result);
    }

    /**
     * 从 XMP 中移除值包含生成器名称的字段（按给定模式与值分组号逐个匹配替换）。
     */
    private static String removeGeneratorFields(String xmp, Pattern pattern, int valueGroup) {
        Matcher matcher = pattern.matcher(xmp);
        StringBuffer buffer = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            if (findGenerator(matcher.group(valueGroup), false) != null) {
                matcher.appendReplacement(buffer, " ");
                changed = true;
            } else {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        if (changed) {
            matcher.appendTail(buffer);
            return buffer.toString();
        }
        return xmp;
    }

    /**
     * XMP 包按 4 字节补齐空格（对齐惯例）。
     */
    private static String padXmp(String xmp) {
        int padding = (4 - (xmp.length() % 4)) % 4;
        return xmp + " ".repeat(padding);
    }

    /**
     * 清除后的 XMP 是否还能装回单个 APP1 段。
     */
    private static boolean fitsInApp1(String xmp) {
        return JPEG_XMP_PREFIX.length + xmp.getBytes(StandardCharsets.UTF_8).length + 2 <= 0xFFFF;
    }

    // ==== PNG：块扫描与清除 ====

    /**
     * PNG 文本块内容（tEXt/iTXt/zTXt 解析结果）。
     */
    private record PngText(String keyword, String value) {
    }

    /**
     * PNG 数据块。
     */
    private record PngChunk(int type, byte[] payload) {
    }

    /**
     * 把 4 字符块类型名组合为 int。
     */
    private static int chunkType(String name) {
        return ((name.charAt(0) & 0xFF) << 24) | ((name.charAt(1) & 0xFF) << 16)
                | ((name.charAt(2) & 0xFF) << 8) | (name.charAt(3) & 0xFF);
    }

    /**
     * 把 int 块类型还原为 4 字符名称。
     */
    private static String chunkName(int type) {
        return "" + (char) ((type >> 24) & 0xFF) + (char) ((type >> 16) & 0xFF)
                + (char) ((type >> 8) & 0xFF) + (char) (type & 0xFF);
    }

    /**
     * 读取 PNG 全部数据块。
     */
    private static List<PngChunk> readPngChunks(byte[] data) {
        List<PngChunk> chunks = new ArrayList<>();
        int i = 8;
        while (i + 8 <= data.length) {
            int length = (int) readUnsignedInt(data, i, true);
            int type = (int) readUnsignedInt(data, i + 4, true);
            int dataStart = i + 8;
            if (length < 0 || dataStart + length > data.length) {
                break;
            }
            byte[] payload = new byte[length];
            System.arraycopy(data, dataStart, payload, 0, length);
            chunks.add(new PngChunk(type, payload));
            i = dataStart + length + 4;
        }
        return chunks;
    }

    /**
     * 解析 PNG 文本块（tEXt/iTXt/zTXt），支持 zlib 解压。
     */
    private static PngText decodePngText(PngChunk chunk) {
        byte[] payload = chunk.payload();
        int keywordEnd = indexOfZero(payload, 0);
        if (keywordEnd < 0) {
            return null;
        }
        String keyword = latin1(payload, 0, keywordEnd);
        if (chunk.type() == PNG_CHUNK_TEXT) {
            return new PngText(keyword, latin1(payload, keywordEnd + 1, payload.length - keywordEnd - 1));
        }
        if (chunk.type() == PNG_CHUNK_ZTXT) {
            int method = keywordEnd + 1;
            if (method >= payload.length || payload[method] != 0) {
                return null;
            }
            return new PngText(keyword, inflate(payload, method + 1));
        }
        int pos = keywordEnd + 1;
        if (pos + 2 > payload.length) {
            return null;
        }
        boolean compressed = payload[pos] == 1;
        pos += 2;
        int languageEnd = indexOfZero(payload, pos);
        if (languageEnd < 0) {
            return null;
        }
        int translatedEnd = indexOfZero(payload, languageEnd + 1);
        if (translatedEnd < 0) {
            return null;
        }
        int textStart = translatedEnd + 1;
        if (textStart > payload.length) {
            return null;
        }
        if (compressed) {
            return new PngText(keyword, inflate(payload, textStart));
        }
        return new PngText(keyword, utf8(payload, textStart, payload.length - textStart));
    }

    /**
     * 解压 zlib 数据为 UTF-8 文本。
     */
    private static String inflate(byte[] data, int offset) {
        if (offset >= data.length) {
            return "";
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, data.length - offset);
            byte[] buffer = new byte[64 * 1024];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            return "";
        } finally {
            inflater.end();
        }
    }

    /**
     * 分析 PNG 的 eXIf/文本块/C2PA 块中的 AI 痕迹。
     */
    private static Report analyzePng(byte[] data) {
        boolean contentCredentials = false;
        boolean credentialsAi = false;
        String xmp = null;
        String parameters = null;
        ExifInfo exif = ExifInfo.empty();
        List<String> extraSignals = new ArrayList<>();
        for (PngChunk chunk : readPngChunks(data)) {
            if (chunk.type() == PNG_CHUNK_C2PA || chunk.type() == PNG_CHUNK_CABX) {
                contentCredentials = true;
                String text = new String(chunk.payload(), StandardCharsets.ISO_8859_1);
                if (containsAnyIgnoreCase(text, AI_LABEL_KEYWORDS)) {
                    credentialsAi = true;
                    extraSignals.add("C2PA 内容凭证中声明了 AI 生成/编辑动作");
                }
            } else if (chunk.type() == PNG_CHUNK_EXIF) {
                exif = parseExif(chunk.payload());
            } else if (chunk.type() == PNG_CHUNK_ITXT || chunk.type() == PNG_CHUNK_TEXT
                    || chunk.type() == PNG_CHUNK_ZTXT) {
                PngText text = decodePngText(chunk);
                if (text == null) {
                    continue;
                }
                String keyword = text.keyword();
                if (PNG_XMP_KEYWORD.equalsIgnoreCase(keyword)) {
                    xmp = text.value();
                } else if (isGenerationTextKeyword(keyword, text.value())) {
                    parameters = (parameters == null ? "" : parameters + "\n")
                            + "[" + keyword + "] " + text.value();
                }
            }
        }
        return summarizeFromTexts("PNG", contentCredentials, credentialsAi, xmp, exif,
                parameters, extraSignals);
    }

    /**
     * 清除 PNG 中的 AI 元数据：移除 c2pa/caBX 块、含 AI 痕迹的 XMP/参数/文本块与 eXIf；
     * 其余块（含 iCCP、IDAT）逐字节保留，不重新压缩。
     */
    private static byte[] stripPng(byte[] data, List<String> removed) {
        List<PngChunk> chunks = readPngChunks(data);
        if (chunks.isEmpty()) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        for (PngChunk chunk : chunks) {
            int type = chunk.type();
            byte[] payload = chunk.payload();
            if (type == PNG_CHUNK_C2PA || type == PNG_CHUNK_CABX) {
                removed.add("PNG " + chunkName(type) + " 块（C2PA/JUMBF 内容凭证）");
                continue;
            }
            if (type == PNG_CHUNK_ITXT || type == PNG_CHUNK_TEXT || type == PNG_CHUNK_ZTXT) {
                PngText text = decodePngText(chunk);
                if (text != null && PNG_XMP_KEYWORD.equalsIgnoreCase(text.keyword())) {
                    if (containsAiSignals(text.value())) {
                        String filtered = filterXmp(text.value());
                        if (containsAiSignals(filtered)) {
                            removed.add("PNG " + chunkName(type) + " XMP 文本块（含 AI 痕迹，整块移除）");
                            continue;
                        }
                        writePngChunk(out, PNG_CHUNK_ITXT, pngXmpPayload(text.keyword(), filtered));
                        removed.add("PNG " + chunkName(type) + " XMP 中的 AI 标签已清除");
                        continue;
                    }
                } else if (text != null && containsAiSignals(text.value())) {
                    removed.add("PNG " + chunkName(type) + " 文本块「" + text.keyword() + "」（含 AI 痕迹）");
                    continue;
                }
            }
            if (type == PNG_CHUNK_EXIF && exifHasAiSignal(payload)) {
                removed.add("PNG eXIf 块（含生成器信息）");
                continue;
            }
            writePngChunk(out, type, payload);
        }
        return out.toByteArray();
    }

    /**
     * 组装未压缩的 iTXt 载荷（关键词 + 压缩标志/方法 + 空 语言/翻译关键字 + UTF-8 文本）。
     */
    private static byte[] pngXmpPayload(String keyword, String text) {
        byte[] keywordBytes = keyword.getBytes(StandardCharsets.ISO_8859_1);
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[keywordBytes.length + 5 + textBytes.length];
        int pos = 0;
        System.arraycopy(keywordBytes, 0, payload, pos, keywordBytes.length);
        pos += keywordBytes.length + 1;
        payload[pos++] = 0;
        payload[pos++] = 0;
        payload[pos++] = 0;
        payload[pos++] = 0;
        System.arraycopy(textBytes, 0, payload, pos, textBytes.length);
        return payload;
    }

    /**
     * 写回 PNG 块（长度 + 类型 + 数据 + CRC32）。
     */
    private static void writePngChunk(ByteArrayOutputStream out, int type, byte[] payload) {
        out.write((payload.length >>> 24) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF);
        out.write((payload.length >>> 8) & 0xFF);
        out.write(payload.length & 0xFF);
        out.write((type >>> 24) & 0xFF);
        out.write((type >>> 16) & 0xFF);
        out.write((type >>> 8) & 0xFF);
        out.write(type & 0xFF);
        out.writeBytes(payload);
        CRC32 crc = new CRC32();
        crc.update((type >>> 24) & 0xFF);
        crc.update((type >>> 16) & 0xFF);
        crc.update((type >>> 8) & 0xFF);
        crc.update(type & 0xFF);
        crc.update(payload);
        int value = (int) crc.getValue();
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * PNG eXIf 块中是否含生成器信息。
     */
    private static boolean exifHasAiSignal(byte[] tiff) {
        ExifInfo exif = parseExif(tiff);
        return findGenerator(exif.software(), false) != null
                || findGenerator(exif.userComment(), false) != null
                || looksLikeGenerationParams(exif.userComment());
    }

    // ==== WebP：RIFF 块扫描与清除 ====

    /**
     * WebP RIFF 数据块。
     */
    private record WebpChunk(int type, byte[] payload) {
    }

    /**
     * 读取 WebP RIFF 块列表（从 12 字节 RIFF 头之后开始）。
     */
    private static List<WebpChunk> readWebpChunks(byte[] data) {
        List<WebpChunk> chunks = new ArrayList<>();
        int i = 12;
        while (i + 8 <= data.length) {
            int type = (int) readUnsignedInt(data, i, true);
            int length = (int) readUnsignedInt(data, i + 4, false);
            int dataStart = i + 8;
            if (length < 0 || dataStart + length > data.length) {
                break;
            }
            byte[] payload = new byte[length];
            System.arraycopy(data, dataStart, payload, 0, length);
            chunks.add(new WebpChunk(type, payload));
            i = dataStart + length + (length & 1);
        }
        return chunks;
    }

    /**
     * 分析 WebP 的 XMP/EXIF/C2PA 块中的 AI 痕迹。
     */
    private static Report analyzeWebp(byte[] data) {
        String xmp = null;
        ExifInfo exif = ExifInfo.empty();
        boolean contentCredentials = false;
        boolean credentialsAi = false;
        List<String> extraSignals = new ArrayList<>();
        for (WebpChunk chunk : readWebpChunks(data)) {
            if (chunk.type() == WEBP_CHUNK_XMP) {
                xmp = new String(chunk.payload(), StandardCharsets.UTF_8);
            } else if (chunk.type() == WEBP_CHUNK_EXIF) {
                exif = parseExif(normalizeExifPayload(chunk.payload()));
            } else if (chunk.type() == WEBP_CHUNK_C2PA) {
                contentCredentials = true;
                String jumbf = new String(chunk.payload(), StandardCharsets.ISO_8859_1);
                if (containsAnyIgnoreCase(jumbf, AI_LABEL_KEYWORDS)) {
                    credentialsAi = true;
                    extraSignals.add("C2PA 内容凭证中声明了 AI 生成/编辑动作");
                }
            }
        }
        if (!contentCredentials && !StringUtils.isBlank(xmp) && xmp.toLowerCase().contains("c2pa")) {
            contentCredentials = true;
            credentialsAi = containsAnyIgnoreCase(xmp, AI_LABEL_KEYWORDS);
        }
        return summarizeFromTexts("WebP", contentCredentials, credentialsAi, xmp, exif, null,
                extraSignals);
    }

    /**
     * 清除 WebP 中的 AI 元数据：移除/过滤 XMP 块、移除含生成器信息的 EXIF 块，
     * VP8/ICCP 等图像数据不动，并回填 RIFF 大小。
     */
    private static byte[] stripWebp(byte[] data, List<String> removed) {
        List<WebpChunk> chunks = readWebpChunks(data);
        if (chunks.isEmpty()) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.writeBytes(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});
        boolean changed = false;
        for (WebpChunk chunk : chunks) {
            int type = chunk.type();
            byte[] payload = chunk.payload();
            if (type == WEBP_CHUNK_C2PA) {
                changed = true;
                removed.add("WebP C2PA 块（内容凭证）");
                continue;
            }
            if (type == WEBP_CHUNK_XMP) {
                String xmp = new String(payload, StandardCharsets.UTF_8);
                if (containsAiSignals(xmp)) {
                    changed = true;
                    String filtered = filterXmp(xmp);
                    if (containsAiSignals(filtered)) {
                        removed.add("WebP XMP 块（含 AI 痕迹，整块移除）");
                        continue;
                    }
                    writeWebpChunk(out, type, filtered.getBytes(StandardCharsets.UTF_8));
                    removed.add("WebP XMP 中的 AI 标签已清除");
                    continue;
                }
            }
            if (type == WEBP_CHUNK_EXIF && exifHasAiSignal(normalizeExifPayload(payload))) {
                changed = true;
                removed.add("WebP EXIF 块（含生成器信息）");
                continue;
            }
            writeWebpChunk(out, type, payload);
        }
        if (!changed) {
            return data;
        }
        byte[] result = out.toByteArray();
        int riffSize = result.length - 8;
        result[4] = (byte) (riffSize & 0xFF);
        result[5] = (byte) ((riffSize >> 8) & 0xFF);
        result[6] = (byte) ((riffSize >> 16) & 0xFF);
        result[7] = (byte) ((riffSize >> 24) & 0xFF);
        return result;
    }

    /**
     * 写回 WebP RIFF 块（fourcc + 小端长度 + 数据 + 奇数补零）。
     */
    private static void writeWebpChunk(ByteArrayOutputStream out, int type, byte[] payload) {
        out.write((type >>> 24) & 0xFF);
        out.write((type >>> 16) & 0xFF);
        out.write((type >>> 8) & 0xFF);
        out.write(type & 0xFF);
        int length = payload.length;
        out.write(length & 0xFF);
        out.write((length >> 8) & 0xFF);
        out.write((length >> 16) & 0xFF);
        out.write((length >> 24) & 0xFF);
        out.writeBytes(payload);
        if ((length & 1) == 1) {
            out.write(0);
        }
    }

    /**
     * 兼容个别编码器在 EXIF 载荷前加「Exif\0\0」前缀的情况。
     */
    private static byte[] normalizeExifPayload(byte[] payload) {
        return startsWith(payload, JPEG_EXIF_PREFIX) ? subarray(payload, JPEG_EXIF_PREFIX.length) : payload;
    }

    // ==== 通用小工具 ====

    /**
     * 判断字节数组是否以给定前缀开头。
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 截取从 offset 开始的子数组。
     */
    private static byte[] subarray(byte[] data, int offset) {
        if (data == null || offset <= 0) {
            return data;
        }
        if (offset >= data.length) {
            return new byte[0];
        }
        byte[] result = new byte[data.length - offset];
        System.arraycopy(data, offset, result, 0, result.length);
        return result;
    }

    /**
     * 按 ISO-8859-1 解码字节片段。
     */
    private static String latin1(byte[] data, int offset, int length) {
        if (offset < 0 || length <= 0 || offset + length > data.length) {
            return "";
        }
        return new String(data, offset, length, StandardCharsets.ISO_8859_1);
    }

    /**
     * 按 UTF-8 解码字节片段。
     */
    private static String utf8(byte[] data, int offset, int length) {
        if (offset < 0 || length <= 0 || offset + length > data.length) {
            return "";
        }
        return new String(data, offset, length, StandardCharsets.UTF_8);
    }

    /**
     * 从 from 开始查找第一个 0 字节。
     */
    private static int indexOfZero(byte[] data, int from) {
        for (int i = Math.max(0, from); i < data.length; i++) {
            if (data[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 空值转空串。
     */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 宽松的整数解析，失败返回 -1。
     */
    private static int parseIntSafely(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 文本中是否包含任一关键词（大小写不敏感；关键词需为小写）。
     */
    private static boolean containsAnyIgnoreCase(String text, String... keywords) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 首字母大写（用于展示生成器名称）。
     */
    private static String capitalize(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return keyword;
        }
        return Character.toUpperCase(keyword.charAt(0)) + keyword.substring(1);
    }

    // ==== 结果类型 ====

    /**
     * AI 元数据检测报告。
     *
     * @param format                图像格式：JPEG / PNG / WebP / UNKNOWN
     * @param verdict               判定结果：AI 生成 / AI 参与（编辑/合成）/ 未发现 AI 痕迹 / 无法判断
     * @param confidence            置信度：高 / 中 / 低
     * @param hasContentCredentials 是否存在 C2PA 内容凭证（JUMBF）
     * @param digitalSourceType     IPTC DigitalSourceType 值（无则空串）
     * @param generator             命中的生成器名称（无则空串）
     * @param generatorSource       生成器名称的来源字段（无则空串）
     * @param generationParams      是否检测到生成参数块（prompt/steps/sampler/seed 等）
     * @param signals               全部检测信号描述
     */
    public record Report(String format, String verdict, String confidence,
                         boolean hasContentCredentials, String digitalSourceType,
                         String generator, String generatorSource,
                         boolean generationParams, List<String> signals) {

        /**
         * 无效数据/不支持格式的占位报告。
         */
        static Report unknown(String note) {
            return new Report("UNKNOWN", VERDICT_UNKNOWN, CONFIDENCE_LOW,
                    false, "", "", "", false, List.of(note));
        }
    }

    /**
     * 清除结果。
     *
     * @param data     清除后的图像字节；未做任何清除时与输入为同一数组
     * @param removed  被移除/清除的条目描述
     * @param modified 是否发生了清除
     */
    public record StripResult(byte[] data, List<String> removed, boolean modified) {
    }
}
