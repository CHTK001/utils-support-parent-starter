package com.chua.common.support.file;


/**
* 文件类型枚举，按文件扩展名对文件进行分类。
*
* <p>用于快速判断文件所属类别，支持图片、文档、视频、音频、压缩包、代码等类型。</p>
*
* <p>使用示例：</p>
* <pre>{@code
* FileType type = FileType.detect("report.pdf");
* System.out.println(type.getCategory()); // 输出: DOCUMENT
*
* boolean isImage = FileType.isImage("photo.jpg"); // true
* }</pre>
*
* @author CH
* @since 1.0
 */
public enum FileType {

    // ========== 图片 ==========
    /** JPEG 图片 */
    JPEG("jpg", "jpeg", Category.IMAGE),
    /** PNG 图片 */
    PNG("png", Category.IMAGE),
    /** GIF 动图 */
    GIF("gif", Category.IMAGE),
    /** BMP 位图 */
    BMP("bmp", Category.IMAGE),
    /** WebP 图片 */
    WEBP("webp", Category.IMAGE),
    /** SVG 矢量图 */
    SVG("svg", Category.IMAGE),
    /** TIFF 图像 */
    TIFF("tiff", "tif", Category.IMAGE),
    /** ICO 图标 */
    ICO("ico", Category.IMAGE),

    // ========== 文档 ==========
    /** PDF 文档 */
    PDF("pdf", Category.DOCUMENT),
    /** Word 文档 */
    DOC("doc", Category.DOCUMENT),
    DOCX("docx", Category.DOCUMENT),
    /** Excel 表格 */
    XLS("xls", Category.DOCUMENT),
    XLSX("xlsx", Category.DOCUMENT),
    /** PPT 演示 */
    PPT("ppt", Category.DOCUMENT),
    PPTX("pptx", Category.DOCUMENT),
    /** 纯文本 */
    TXT("txt", Category.DOCUMENT),
    /** CSV 表格 */
    CSV("csv", Category.DOCUMENT),
    /** Markdown */
    MARKDOWN("md", Category.DOCUMENT),
    /** RTF 富文本 */
    RTF("rtf", Category.DOCUMENT),

    // ========== 视频 ==========
    /** MP4 视频 */
    MP4("mp4", Category.VIDEO),
    /** AVI 视频 */
    AVI("avi", Category.VIDEO),
    /** MKV 视频 */
    MKV("mkv", Category.VIDEO),
    /** MOV 视频（QuickTime） */
    MOV("mov", Category.VIDEO),
    /** FLV 视频 */
    FLV("flv", Category.VIDEO),
    /** WMV 视频 */
    WMV("wmv", Category.VIDEO),
    /** WebM 视频 */
    WEBM("webm", Category.VIDEO),

    // ========== 音频 ==========
    /** MP3 音频 */
    MP3("mp3", Category.AUDIO),
    /** WAV 音频 */
    WAV("wav", Category.AUDIO),
    /** FLAC 无损音频 */
    FLAC("flac", Category.AUDIO),
    /** AAC 音频 */
    AAC("aac", Category.AUDIO),
    /** OGG 音频 */
    OGG("ogg", Category.AUDIO),
    /** WMA 音频 */
    WMA("wma", Category.AUDIO),

    // ========== 压缩包 ==========
    /** ZIP 压缩包 */
    ZIP("zip", Category.ARCHIVE),
    /** RAR 压缩包 */
    RAR("rar", Category.ARCHIVE),
    /** 7z 压缩包 */
    SEVEN_Z("7z", Category.ARCHIVE),
    /** TAR 归档 */
    TAR("tar", Category.ARCHIVE),
    /** GZip 压缩 */
    GZ("gz", Category.ARCHIVE),
    /** BZ2 压缩 */
    BZ2("bz2", Category.ARCHIVE),
    /** XZ 压缩 */
    XZ("xz", Category.ARCHIVE),

    // ========== 代码 ==========
    /** Java 源文件 */
    JAVA("java", Category.CODE),
    /** Python 源文件 */
    PYTHON("py", Category.CODE),
    /** JavaScript 源文件 */
    JAVASCRIPT("js", Category.CODE),
    /** TypeScript 源文件 */
    TYPESCRIPT("ts", Category.CODE),
    /** HTML 文件 */
    HTML("html", "htm", Category.CODE),
    /** CSS 样式表 */
    CSS("css", Category.CODE),
    /** XML 文件 */
    XML("xml", Category.CODE),
    /** JSON 文件 */
    JSON("json", Category.CODE),
    /** YAML 配置文件 */
    YAML("yaml", "yml", Category.CODE),
    /** SQL 文件 */
    SQL("sql", Category.CODE),
    /** Shell 脚本 */
    SHELL("sh", Category.CODE),
    /** C 源文件 */
    C("c", Category.CODE),
    /** C++ 源文件 */
    CPP("cpp", "cc", "cxx", Category.CODE),
    /** Go 源文件 */
    GO("go", Category.CODE),
    /** Rust 源文件 */
    RUST("rs", Category.CODE),
    /** Properties 配置文件 */
    PROPERTIES("properties", Category.CODE),

    // ========== 其他 ==========
    /**
     * 未知类型
     * @param OTHER 方法入参 OTHER
     */
    UNKNOWN("", Category.OTHER);

    /** 文件扩展名列表（小写，不含点） */
    private final String[] extensions;
    /** 文件类别 */
    private final Category category;

    /**
     * 构造方法，创建 文件类型 实例。
     *
     * @param primaryExtension 方法入参 primaryExtension
     * @param category 方法入参 category
     */
    FileType(String primaryExtension, Category category) {
        this.extensions = new String[]{primaryExtension};
        this.category = category;
    }

    /**
     * 构造方法，创建 文件类型 实例。
     *
     * @param ext1 方法入参 ext1
     * @param ext2 方法入参 ext2
     * @param category 方法入参 category
     */
    FileType(String ext1, String ext2, Category category) {
        this.extensions = new String[]{ext1, ext2};
        this.category = category;
    }

    /**
     * 构造方法，创建 文件类型 实例。
     *
     * @param ext1 方法入参 ext1
     * @param ext2 方法入参 ext2
     * @param ext3 方法入参 ext3
     * @param category 方法入参 category
     */
    FileType(String ext1, String ext2, String ext3, Category category) {
        this.extensions = new String[]{ext1, ext2, ext3};
        this.category = category;
    }

    /**
    * 获取文件类别。
    *
    * @return 文件类别（图片/文档/视频/音频/压缩包/代码/其他）
    */
    public Category getCategory() {
        return category;
    }

    /**
    * 获取主扩展名（第一个注册的扩展名）。
    *
    * @return 扩展名（不含点）
    */
    public String getPrimaryExtension() {
        return extensions[0];
    }

    /**
    * 获取所有支持的扩展名。
    *
    * @return 扩展名数组
    */
    public String[] getExtensions() {
        return extensions;
    }

    /**
    * 根据文件名/扩展名检测文件类型。
    *
    * @param filename 文件名（如 {@code "report.pdf"}）或扩展名（如 {@code "pdf"}）
    * @return 文件类型枚举，未识别返回 {@link #UNKNOWN}
    */
    public static FileType detect(String filename) {
        if (filename == null || filename.isEmpty()) {
            return UNKNOWN;
        }

        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase().trim()
                : filename.toLowerCase().trim();

        for (FileType type : values()) {
            for (String e : type.extensions) {
                if (e.equals(ext)) {
                    return type;
                }
            }
        }
        return UNKNOWN;
    }

    /**
    * 判断文件名是否为图片类型。
    *
    * @param filename 文件名
    * @return 如果是图片返回 {@code true}
    */
    public static boolean isImage(String filename) {
        return detect(filename).category == Category.IMAGE;
    }

    /**
    * 判断文件名是否为文档类型。
    *
    * @param filename 文件名
    * @return 如果是文档返回 {@code true}
    */
    public static boolean isDocument(String filename) {
        return detect(filename).category == Category.DOCUMENT;
    }

    /**
    * 判断文件名是否为视频类型。
    *
    * @param filename 文件名
    * @return 如果是视频返回 {@code true}
    */
    public static boolean isVideo(String filename) {
        return detect(filename).category == Category.VIDEO;
    }

    /**
    * 判断文件名是否为音频类型。
    *
    * @param filename 文件名
    * @return 如果是音频返回 {@code true}
    */
    public static boolean isAudio(String filename) {
        return detect(filename).category == Category.AUDIO;
    }

    /**
    * 判断文件名是否为压缩包类型。
    *
    * @param filename 文件名
    * @return 如果是压缩包返回 {@code true}
    */
    public static boolean isArchive(String filename) {
        return detect(filename).category == Category.ARCHIVE;
    }

    /**
    * 判断文件名是否为代码/配置文件类型。
    *
    * @param filename 文件名
    * @return 如果是代码/配置文件返回 {@code true}
    */
    public static boolean isCode(String filename) {
        return detect(filename).category == Category.CODE;
    }

    /**
    * 文件类别枚举。
    */
    public enum Category {
        /** 图片 */
        IMAGE,
        /** 文档 */
        DOCUMENT,
        /** 视频 */
        VIDEO,
        /** 音频 */
        AUDIO,
        /** 压缩包 */
        ARCHIVE,
        /** 代码/配置文件 */
        CODE,
        /** 其他 */
        OTHER
    }
}
