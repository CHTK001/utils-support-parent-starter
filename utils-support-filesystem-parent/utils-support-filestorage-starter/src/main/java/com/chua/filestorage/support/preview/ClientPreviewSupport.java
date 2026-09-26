package com.chua.filestorage.support.preview;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 客户端直渲染预览支持类。
 *
 * <p>本类是「预览渲染通道」的唯一事实来源，被两套并行的文件服务过滤器共同使用，
 * 避免扩展名清单、渲染器映射、请求参数与响应头在各过滤器中重复维护：</p>
 * <ul>
 *   <li>{@code com.chua.filestorage.support.filter.FileStorageViewServerFilter}
 *       —— utils 侧通用 SPI 过滤器</li>
 *   <li>{@code com.chua.starter.filesystem.support.server.FileStorageServerFilter}
 *       —— spring 侧独立文件服务器过滤器</li>
 * </ul>
 *
 * <p><b>设计目标</b>：{@code ?preview=true} 请求命中「客户端可直渲染」扩展名时，
 * 直接回吐原始文件字节（不读、不转、不缓存），由前端组件在浏览器内解析渲染，
 * 从而彻底避免服务端 LibreOffice / Aspose / OnlyOffice 的转换开销与首次打开延迟。
 * 前端渲染失败时可追加 {@code &render=server} 显式退回服务端渲染链路。</p>
 *
 * <p><b>能力边界（已实测确认，勿凭 README 臆断）</b>：前端 Vue3 Office 组件族
 * （{@code @vue-office/docx}、{@code @vue-office/excel}、{@code @vue-office/pdf}、
 * {@code @vue-office/pptx}）底层全部为 ZIP / OOXML 解析器。对产物做过字节级扫描，
 * 均不含 OLE2 / CFB（复合文档）文件头 {@code D0 CF 11 E0 A1 B1 1A E1}，也无任何
 * OLE 解析器符号，因此<strong>无法</strong>解析传统二进制格式：</p>
 * <ul>
 *   <li>{@code .doc}、{@code .ppt} —— 传统 OLE2 复合文档，<strong>不支持</strong>，
 *       仍需服务端转换链路兜底</li>
 *   <li>{@code .odt}、{@code .ods}、{@code .odp} —— OpenDocument 格式，
 *       不属于该组件族，<strong>不支持</strong></li>
 * </ul>
 *
 * <p>上述不支持的扩展名不会进入本类的客户端清单，因而自动沿用服务端渲染路径。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ClientPreviewSupport {

    /**
     * 客户端渲染器标识：Word（{@code .docx} 及同构的宏/模板变体）
     */
    public static final String RENDERER_DOCX = "docx";

    /**
     * 客户端渲染器标识：Excel（{@code .xlsx} / {@code .xls} 及同构变体）
     */
    public static final String RENDERER_EXCEL = "excel";

    /**
     * 客户端渲染器标识：PDF
     */
    public static final String RENDERER_PDF = "pdf";

    /**
     * 客户端渲染器标识：PowerPoint（{@code .pptx} 及同构变体）
     */
    public static final String RENDERER_PPTX = "pptx";

    /**
     * 响应头名称：预览通道标识
     */
    public static final String HEADER_PREVIEW = "X-FileStorage-Preview";

    /**
     * 响应头名称：客户端渲染器标识
     */
    public static final String HEADER_CLIENT_RENDERER = "X-FileStorage-Client-Renderer";

    /**
     * 预览通道取值：客户端直渲染（回吐原始字节）
     */
    public static final String CHANNEL_CLIENT = "client";

    /**
     * 预览通道取值：服务端渲染（SPI 提供者 / 转换链路）
     */
    public static final String CHANNEL_SERVER = "server";

    /**
     * 查询参数名称：渲染通道选择
     */
    public static final String PARAM_RENDER = "render";

    /**
     * 复合扩展名集合（需优先于单段扩展名识别）
     */
    private static final Set<String> COMPOUND_EXTENSIONS = Set.of(
            "tar.gz", "tar.bz2", "tar.xz", "tar.zst", "tar.lz4", "tar.lzma", "tar.sz");

    /**
     * 扩展名到客户端渲染器标识的映射
     */
    private static final Map<String, String> EXTENSION_TO_RENDERER;

    /**
     * OLE2 / CFB 复合文档文件头（传统 .doc / .xls / .ppt）
     */
    private static final byte[] MAGIC_OLE2 = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };

    /**
     * ZIP 本地文件头（OOXML 家族：.docx / .xlsx / .pptx 也用它）
     */
    private static final byte[] MAGIC_ZIP = {0x50, 0x4B, 0x03, 0x04};

    /**
     * PDF 文件头
     */
    private static final byte[] MAGIC_PDF = {0x25, 0x50, 0x44, 0x46};

    /**
     * RTF 文件头
     */
    private static final byte[] MAGIC_RTF = {0x7B, 0x5C, 0x72, 0x74};

    /**
     * 嗅探 OOXML 内部部件路径的最大扫描字节数。
     *
     * <p>OOXML 是 ZIP 容器，{@code [Content_Types].xml} 通常紧邻文件头，
     * 绝大多数文档在 8 KB 内即可命中；限制扫描范围避免为超大文件做无谓的字符串搜索。</p>
     */
    private static final int OOXML_SNIFF_LIMIT = 64 * 1024;

    /**
     * 仅支持传统二进制形态、客户端无法解析的扩展名（OLE2 容器）。
     *
     * <p>这些扩展名与 OOXML 同名系列（{@code .doc} vs {@code .docx}）在
     * 扩展名层面无法区分，必须结合内容魔数判定，见
     * {@link #rendererOfSniffed(byte[], String)}。</p>
     *
     * <p><b>实测依据</b>（务必以此为准，勿被产物里的字符串误导）：</p>
     * <ul>
     *   <li>{@code @vue-office/excel} <b>不能</b>读取传统 BIFF(.xls)。虽然其产物中
     *       可检出 108 处 {@code BIFF} 与 {@code Workbook stream} 字样，
     *       但实测把真实 BIFF8 文件交给它渲染会抛
     *       {@code Can't find end of central directory : is this a zip file}
     *       —— 即它按 ZIP 解析 OLE2 字节后失败。那些字样只是 exceljs 内部常量，
     *       并非可用的 BIFF 读取器。</li>
     *   <li>{@code @vue-office/docx} / {@code @vue-office/pptx} 分别基于
     *       docx-preview 与 pptx-preview，均为 ZIP/OOXML 解析器，读不了 OLE2。</li>
     * </ul>
     *
     * <p>因此 doc / xls / ppt 三者都必须嗅探：命中 OLE2 则交服务端 POI，
     * 命中 ZIP（扩展名骗人的 OOXML）才交客户端。</p>
     */
    private static final Set<String> LEGACY_BINARY_EXTS = Set.of("doc", "xls", "ppt");

    /**
     * 已知会伪装成传统扩展名的 OOXML 部件标记。
     *
     * <p>key 为 ZIP 内部部件路径片段，value 为应使用的客户端渲染器。
     * 仅收录与 {@link #LEGACY_BINARY_EXTS} 同名的传统扩展所对应的 OOXML 部件，
     * 避免误判其他 ZIP 类文件。</p>
     */
    private static final Map<String, String> OOXML_PART_MARKERS = Map.of(
            "word/document.xml", RENDERER_DOCX,
            "xl/workbook.xml", RENDERER_EXCEL,
            "ppt/presentation.xml", RENDERER_PPTX
    );

    static {
        Map<String, String> map = new LinkedHashMap<>();
        // Word：OOXML wordprocessingml 家族（含宏启用与模板变体）
        for (String ext : new String[]{"docx", "docm", "dotx", "dotm"}) {
            map.put(ext, RENDERER_DOCX);
        }
        // Excel：OOXML spreadsheetml 家族 + 传统 xls + 二进制 xlsb
        for (String ext : new String[]{"xlsx", "xlsm", "xlsb", "xltx", "xltm", "xlt", "xls"}) {
            map.put(ext, RENDERER_EXCEL);
        }
        // PowerPoint：OOXML presentationml 家族（传统 ppt 为 OLE2，不在支持范围）
        for (String ext : new String[]{"pptx", "pptm", "potx", "potm", "ppsx", "ppsm"}) {
            map.put(ext, RENDERER_PPTX);
        }
        // PDF
        map.put("pdf", RENDERER_PDF);
        EXTENSION_TO_RENDERER = Collections.unmodifiableMap(map);
    }

    /**
     * 私有构造，禁止实例化工具类
     */
    private ClientPreviewSupport() {
        throw new AssertionError("No com.chua.filestorage.support.preview.ClientPreviewSupport instances for you!");
    }

    /**
     * 判断扩展名是否可由客户端组件直渲染。
     *
     * @param extension 扩展名（不带点，大小写不敏感）；可为 空
     * @return true 表示支持客户端直渲染
     */
    public static boolean isClientRenderable(String extension) {
        return rendererOf(extension) != null;
    }

    /**
     * 查询扩展名对应的客户端渲染器标识。
     *
     * @param extension 扩展名（不带点，大小写不敏感）；可为 空
     * @return 渲染器标识（{@link #RENDERER_DOCX} / {@link #RENDERER_EXCEL}
     *         / {@link #RENDERER_PDF} / {@link #RENDERER_PPTX}）；
     *         不支持时返回 空
     */
    public static String rendererOf(String extension) {
        if (extension == null || extension.isEmpty()) {
            return null;
        }
        return EXTENSION_TO_RENDERER.get(extension.toLowerCase(Locale.ENGLISH));
    }

    /**
     * 结合<b>内容魔数</b>判定客户端渲染器，解决「扩展名与真实格式不符」的问题。
     *
     * <p>背景：现实中大量文件扩展名不可信——Word/WPS「另存为」、在线转换、
     * 邮件客户端附件等都会产生 {@code 实际是 .docx 却叫 .doc} 的文件。
     * 仅按扩展名判断会导致这类文件被当成 OLE2 送进 POI 二进制解析而失败，
     * 白白浪费了它本可被客户端完美渲染的事实。</p>
     *
     * <p>判定顺序：</p>
     * <ol>
     *   <li>扩展名属于传统二进制家族（doc / xls / ppt）时<strong>只看魔数</strong>：
     *       命中 OLE2 头则确认是传统格式（交服务端 POI 处理，返回 {@ 空}）；
     *       命中 ZIP 头则进一步扫描 OOXML 部件路径，按真实类型给出渲染器
     *       （{@code .doc} 里装的是 docx → 返回 {@link #RENDERER_DOCX}）。</li>
     *   <li>其他扩展名仍按扩展名判定（{@code docx / xlsx / pdf} 等无需嗅探）。</li>
     * </ol>
     *
     * @param content   文件字节；可为 {@ 空}（此时退化为纯扩展名判定）
     * @param extension 文件扩展名（小写，不含点）；可为 {@ 空}
     * @return 渲染器标识；应走服务端渲染时返回 {@ 空}
     */
    public static String rendererOfSniffed(byte[] content, String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ENGLISH);
        if (!LEGACY_BINARY_EXTS.contains(ext)) {
            return rendererOf(ext);
        }
        if (content == null || content.length < 4) {
            // 无内容可嗅探：保守按扩展名处理
            return null;
        }
        if (startsWith(content, MAGIC_OLE2)) {
            // 确实是传统二进制格式，交给服务端 POI
            return null;
        }
        if (startsWith(content, MAGIC_ZIP)) {
            String sniffed = sniffOoxmlRenderer(content);
            if (sniffed != null) {
                return sniffed;
            }
        }
        return null;
    }

    /**
     * 判断是否可由客户端组件直渲染（结合内容魔数）。
     *
     * @param content   文件字节；可为 {@ 空}
     * @param extension 文件扩展名（小写，不含点）；可为 {@ 空}
     * @return true 表示支持客户端直渲染
     */
    public static boolean isClientRenderableSniffed(byte[] content, String extension) {
        return rendererOfSniffed(content, extension) != null;
    }

    /**
     * 扫描 ZIP 容器内的部件路径，识别真实 OOXML 类型。
     *
     * @param content ZIP 字节
     * @return 渲染器标识；非已知 OOXML 返回 {@ 空}
     */
    private static String sniffOoxmlRenderer(byte[] content) {
        int limit = Math.min(content.length, OOXML_SNIFF_LIMIT);
        // 部件路径以 ASCII 存于 ZIP 的本地文件头/中央目录中，直接按字节匹配
        for (Map.Entry<String, String> entry : OOXML_PART_MARKERS.entrySet()) {
            if (indexOfAscii(content, entry.getKey().getBytes(StandardCharsets.US_ASCII), limit) >= 0) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 判断字节数组是否以指定前缀开头。
     *
     * @param data   数据
     * @param prefix 前缀
     * @return true 表示匹配
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
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
     * 在指定范围内查找 ASCII 字节序列首次出现的位置。
     *
     * @param data  数据
     * @param needle 待查找序列
     * @param limit  搜索上限
     * @return 首次出现下标；未找到返回 -1
     */
    private static int indexOfAscii(byte[] data, byte[] needle, int limit) {
        int end = Math.min(data.length, limit);
        int max = end - needle.length;
        outer:
        for (int i = 0; i <= max; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * 判断内容是否为传统 OLE2 复合文档（真正的 .doc / .xls / .ppt）。
     *
     * @param content 文件字节
     * @return true 表示 OLE2 容器
     */
    public static boolean isOle2(byte[] content) {
        return content != null && startsWith(content, MAGIC_OLE2);
    }

    /**
     * 判断内容是否为 ZIP 容器（OOXML 家族）。
     *
     * @param content 文件字节
     * @return true 表示 ZIP 容器
     */
    public static boolean isZip(byte[] content) {
        return content != null && content.length >= 4 && startsWith(content, MAGIC_ZIP);
    }

    /**
     * 判断内容是否为 PDF。
     *
     * @param content 文件字节
     * @return true 表示 PDF
     */
    public static boolean isPdf(byte[] content) {
        return content != null && content.length >= 4 && startsWith(content, MAGIC_PDF);
    }

    /**
     * 判断内容是否为 RTF。
     *
     * @param content 文件字节
     * @return true 表示 RTF
     */
    public static boolean isRtf(byte[] content) {
        return content != null && content.length >= 4 && startsWith(content, MAGIC_RTF);
    }

    /**
     * 查询扩展名对应的客户端渲染器标识，支持复合扩展名回退。
     *
     * <p>先按原样查询（兼容复合后缀），失败后再取末段单扩展名查询。</p>
     *
     * @param extension 扩展名候选（不带点，大小写不敏感）；可为 空
     * @return 渲染器标识；不支持时返回 空
     */
    public static String rendererOfCandidate(String extension) {
        String direct = rendererOf(extension);
        if (direct != null) {
            return direct;
        }
        if (extension == null) {
            return null;
        }
        int dot = extension.lastIndexOf('.');
        return dot < 0 ? null : rendererOf(extension.substring(dot + 1));
    }

    /**
     * 取得全部支持客户端直渲染的扩展名集合（只读）。
     *
     * @return 扩展名集合，保持声明顺序
     */
    public static Set<String> clientRenderableExtensions() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(EXTENSION_TO_RENDERER.keySet()));
    }

    /**
     * 判断本次请求是否应优先走客户端直渲染通道。
     *
     * <p>缺省（未传 {@code render} 参数）为客户端优先；显式传入
     * {@code render=server} 时退回服务端渲染，用于前端组件渲染失败后的兜底重试。</p>
     *
     * @param renderParam {@code render} 查询参数的原始取值；可为 空
     * @return true 表示客户端优先；false 表示强制服务端渲染
     */
    public static boolean isClientPreferred(String renderParam) {
        if (renderParam == null || renderParam.isBlank()) {
            return true;
        }
        return !CHANNEL_SERVER.equalsIgnoreCase(renderParam.trim());
    }

    /**
     * 从文件名或对象键中提取小写扩展名，复合扩展名优先识别。
     *
     * <p>本方法统一了两套过滤器此前各自实现的扩展名提取逻辑：</p>
     * <ul>
     *   <li>utils 侧 {@code FileStorageViewServerFilter#getExt}（复合后缀优先）</li>
     *   <li>spring 侧 {@code FileStorageServerFilter#simpleExtOf}（仅末段）</li>
     * </ul>
     *
     * @param fileNameOrKey 文件名或对象键（含路径）；可为 空
     * @return 小写扩展名；无扩展名时返回空串
     */
    public static String resolveExtension(String fileNameOrKey) {
        if (fileNameOrKey == null || !fileNameOrKey.contains(".")) {
            return "";
        }
        String lower = fileNameOrKey.toLowerCase(Locale.ENGLISH);
        for (String compound : COMPOUND_EXTENSIONS) {
            if (lower.endsWith("." + compound)) {
                return compound;
            }
        }
        return lower.substring(lower.lastIndexOf('.') + 1);
    }
}
