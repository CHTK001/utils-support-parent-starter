package com.chua.pdf.support.export;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.ExportException;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * PDF 字体解析器。
 *
 * <p>PDFBox 不内置任何字体，Standard 14 无法渲染中文，因此必须显式加载一个
 * 覆盖目标字符集的字体文件。</p>
 *
 * <p>解析顺序：</p>
 * <ol>
 *     <li>系统属性 {@code chua.export.pdf.font} 或环境变量 {@code CHUA_PDF_FONT} 指定的文件</li>
 *     <li>类路径资源 {@code fonts/pdf-font.ttf} / {@code .ttc} / {@code .otf}</li>
 *     <li>操作系统字体目录中按优先级命名的候选字体</li>
 *     <li>兜底：Standard 14（Helvetica，<b>不支持中文</b>）</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class PdfFonts {

    /**
     * 日志。
     */
    private static final Logger log = LoggerFactory.getLogger(PdfFonts.class);

    /**
     * 字体路径系统属性。
     */
    public static final String FONT_PROPERTY = "chua.export.pdf.font";

    /**
     * 字体路径环境变量。
     */
    public static final String FONT_ENV = "CHUA_PDF_FONT";

    /**
     * 类路径字体资源候选。
     */
    private static final List<String> CLASSPATH_CANDIDATES = List.of(
            "fonts/pdf-font.ttf", "fonts/pdf-font.ttc", "fonts/pdf-font.otf",
            "fonts/NotoSansSC-Regular.otf", "fonts/source-han-sans.otf");

    /**
     * 系统字体候选（按优先级）。
     *
     * <p>单字体文件优先于字体集合：{@code .ttc} 需要逐个子字体解析，
     * 命中稳定性不如 {@code .ttf}。</p>
     */
    private static final List<String> SYSTEM_CANDIDATES = List.of(
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/simsun.ttf",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/STHeiti Light.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf");

    /**
     * 字体集合内优先选用的子字体名。
     */
    private static final List<String> PREFERRED = List.of(
            "MicrosoftYaHei", "Microsoft YaHei", "SimSun", "NSimSun", "SimHei",
            "DengXian", "Deng", "NotoSansCJKsc-Regular", "Noto Sans CJK SC",
            "PingFangSC-Regular", "PingFang SC", "WenQuanYiZenHei", "WenQuanYi Zen Hei");

    /**
     * 系统字体目录。
     */
    private static final List<String> FONT_DIRS = List.of(
            "C:/Windows/Fonts",
            "/usr/share/fonts",
            "/usr/local/share/fonts",
            "/System/Library/Fonts",
            System.getProperty("user.home", "") + "/.fonts");

    private PdfFonts() {
    }

    /**
     * 加载 PDF 字体。
     *
     * @param document 目标文档
     * @return 字体
     * @throws IOException 加载失败
     */
    public static PDFont load(PDDocument document) throws IOException {
        String configured = System.getProperty(FONT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(FONT_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            File file = new File(configured);
            if (file.isFile()) {
                return fromFile(document, file);
            }
            log.warn("指定的 PDF 字体不存在: {}", configured);
        }
        for (String resource : CLASSPATH_CANDIDATES) {
            var in = PdfFonts.class.getClassLoader().getResourceAsStream(resource);
            if (in != null) {
                try (in) {
                    return PDType0Font.load(document, in, true);
                }
            }
        }
        File system = findSystemFont();
        if (system != null) {
            return fromFile(document, system);
        }
        log.warn("未找到可用的中文 PDF 字体，回退到 Helvetica，中文将无法正常显示。"
                + "可通过 -D{}=/path/to/font.ttf 指定。", FONT_PROPERTY);
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    /**
     * 从字体文件加载（支持 ttf / otf / ttc）。
     *
     * @param document 目标文档
     * @param file     字体文件
     * @return 字体
     * @throws IOException 加载失败
     */
    private static PDFont fromFile(PDDocument document, File file) throws IOException {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".ttc") || name.endsWith(".otc")) {
            return fromCollection(document, file);
        }
        return PDType0Font.load(document, file);
    }

    /**
     * 从字体集合（{@code .ttc} / {@code .otc}）中挑选子字体。
     *
     * <p>集合内子字体的 PostScript 名差异很大，因此先按候选名逐个匹配，
     * 匹配不到时退回集合中的第一个子字体，而不是直接失败。</p>
     *
     * @param document 目标文档
     * @param file     字体集合文件
     * @return 字体
     * @throws IOException 加载失败
     */
    private static PDFont fromCollection(PDDocument document, File file) throws IOException {
        try (TrueTypeCollection collection = new TrueTypeCollection(file)) {
            var matched = new AtomicReference<PDFont>();
            var fallback = new AtomicReference<String>();
            collection.processAllFonts(font -> {
                String name = fontName(font);
                if (name == null) {
                    return;
                }
                fallback.compareAndSet(null, name);
                if (PREFERRED.contains(name)) {
                    try {
                        matched.compareAndSet(null, PDType0Font.load(document, font, true));
                    } catch (IOException e) {
                        log.debug("加载 TTC 子字体失败: {}", name, e);
                    }
                }
            });
            if (matched.get() != null) {
                return matched.get();
            }
            String name = fallback.get();
            if (name == null) {
                throw new ExportException("TTC 字体中未找到可用的子字体: " + file);
            }
            var font = collection.getFontByName(name);
            return font == null
                    ? throwCollection(file)
                    : PDType0Font.load(document, font, true);
        }
    }

    /**
     * 读取子字体名，失败时返回 {@code null}。
     *
     * @param font 子字体
     * @return 字体名
     */
    private static String fontName(TrueTypeFont font) {
        try {
            return font.getName();
        } catch (IOException e) {
            log.debug("读取 TTC 子字体名失败", e);
            return null;
        }
    }

    /**
     * 抛出"集合中无可用子字体"异常。
     *
     * @param file 字体集合文件
     * @return 不会正常返回
     */
    private static PDFont throwCollection(File file) {
        throw new ExportException("TTC 字体中未找到可用的子字体: " + file);
    }

    /**
     * 在系统字体目录中查找候选字体。
     *
     * @return 字体文件，找不到返回 {@code null}
     */
    private static File findSystemFont() {
        for (String candidate : SYSTEM_CANDIDATES) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file;
            }
        }
        for (String dir : FONT_DIRS) {
            if (dir.isEmpty()) {
                continue;
            }
            Path path = Path.of(dir);
            if (!Files.isDirectory(path)) {
                continue;
            }
            for (String pattern : List.of("NotoSansCJK*", "msyh*", "simhei*", "simsun*", "wqy-zenhei*")) {
                File found = scan(path, pattern);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 浅层扫描字体目录。
     *
     * @param dir     目录
     * @param pattern 通配模式
     * @return 命中的字体文件
     */
    private static File scan(Path dir, String pattern) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".ttf") || name.endsWith(".ttc") || name.endsWith(".otf");
                    })
                    .filter(path -> matches(path.getFileName().toString(), pattern))
                    .findFirst()
                    .map(Path::toFile)
                    .orElse(null);
        } catch (IOException e) {
            log.debug("扫描字体目录失败: {}", dir, e);
            return null;
        }
    }

    /**
     * 极简通配匹配（仅支持 {@code *}）。
     *
     * @param name    文件名
     * @param pattern 通配模式
     * @return 是否匹配
     */
    private static boolean matches(String name, String pattern) {
        if (!pattern.contains("*")) {
            return name.equalsIgnoreCase(pattern);
        }
        int star = pattern.indexOf('*');
        String head = pattern.substring(0, star);
        String tail = pattern.substring(star + 1);
        return name.length() >= head.length() + tail.length()
                && name.regionMatches(true, 0, head, 0, head.length())
                && name.regionMatches(true, name.length() - tail.length(), tail, 0, tail.length());
    }

    /**
     * 判断当前环境是否具备中文渲染能力。
     *
     * @return 是否可用
     */
    public static boolean cjkAvailable() {
        String configured = System.getProperty(FONT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(FONT_ENV);
        }
        if (configured != null && !configured.isBlank() && new File(configured).isFile()) {
            return true;
        }
        for (String resource : CLASSPATH_CANDIDATES) {
            if (PdfFonts.class.getClassLoader().getResource(resource) != null) {
                return true;
            }
        }
        return findSystemFont() != null;
    }

    /**
     * 获取候选字体名称列表（排障用）。
     *
     * @return 候选名称
     */
    public static List<String> candidates() {
        return Wraps.list(SYSTEM_CANDIDATES);
    }
}