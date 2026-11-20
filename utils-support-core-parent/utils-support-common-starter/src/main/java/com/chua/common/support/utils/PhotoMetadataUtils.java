package com.chua.common.support.utils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 *   <tr><td>AI 元数据检测</td><td>{@link #analyze(byte[])}</td><td>输出判定、置信度、C2PA/来源类型/生成器/参数块等信号</td></tr>
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
 *   <li>本类只处理元数据字节，不解码/重编码像素；像素级旋转请配合 {@link BufferedImageUtils}；</li>
 *   <li>检测结果<b>仅供参考</b>：元数据可被伪造或剥离，无 AI 痕迹不代表一定是实拍。</li>
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
     * 分析照片元数据中的 AI 痕迹。
     *
     * <p>依次检查 C2PA 内容凭证、IPTC {@code DigitalSourceType}、XMP 生成器/来源类型、
     * EXIF Software/UserComment 以及生成参数块（prompt/steps/sampler/seed 等）。</p>
     *
     * @param data 图像文件字节（JPEG/PNG/WebP）
     * @return 检测报告，永不为 {@code null}；数据无效或格式不支持时判定为「无法判断」
     */
    public static Report analyze(byte[] data) {
        if (data == null || data.length < 12) {
            return Report.unknown("无有效图像数据");
        }
        if (isJpeg(data)) {
            return analyzeJpeg(data);
        }
        if (isPng(data)) {
            return analyzePng(data);
        }
        if (isWebp(data)) {
            return analyzeWebp(data);
        }
        return Report.unknown("不支持的格式（当前支持 JPEG / PNG / WebP）");
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

        List<JpegSegment> segments = new ArrayList<>();
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
        List<JpegSegment> segments = new ArrayList<>();
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
