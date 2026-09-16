package com.chua.common.support.image.png;

import com.chua.common.support.reflection.ReflectUtils;
import org.w3c.dom.Node;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.SampleModel;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.StringTokenizer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* PNG 图像元数据。
*
* <p>继承自 {@link javax.imageio.metadata.IIOMetadata}，封装 PNG 格式的所有元数据，
* 包括 IHDR、PLTE、trns、gama、srgb 等标准 PNG 块信息，以及 APNG 动画相关元数据。
* 支持元数据的读取、写入和标准 XML 树形结构的转换。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class PNGMetadata extends IIOMetadata implements Cloneable {

 // 包 scope
    public static final String
        nativeMetadataFormatName = "javax_imageio_png_1.0"; // natmetadata格式化名称

    protected static final String nativeMetadataFormatClassName
        = "com.tianscar.imageio.plugins.png.PNGMetadataFormat";

 // Color 类型 for IHDR chunk
    static final String[] IHDR_colorTypeNames = {
        "Grayscale", null, "RGB", "Palette",
        "GrayAlpha", null, "RGBAlpha"
    };

    static final int[] IHDR_numChannels = {
        1, 0, 3, 3, 2, 0, 4
    };

 // 位深度 for IHDR chunk
    static final String[] IHDR_bitDepths = {
        "1", "2", "4", "8", "16"
    };

 // Compression 方法 for IHDR chunk
    static final String[] IHDR_compressionMethodNames = {
        "deflate"
    };

 // 过滤器 方法 for IHDR chunk
    static final String[] IHDR_filterMethodNames = {
        "adaptive"
    };

 // Interlace 方法 for IHDR chunk
    static final String[] IHDR_interlaceMethodNames = {
        "none", "adam7"
    };

 // Compression 方法 for iccp chunk
    static final String[] iCCP_compressionMethodNames = {
        "deflate"
    };

 // Compression 方法 for ztxt chunk
    static final String[] zTXt_compressionMethodNames = {
        "deflate"
    };

    // "Unknown" unit for pHYs chunk
    /** Phys_unit_unknown */
    public static final int PHYS_UNIT_UNKNOWN = 0;

    // "Meter" unit for pHYs chunk
    /** Phys_unit_节拍 */
    public static final int PHYS_UNIT_METER = 1;

 // Unit specifiers for phys chunk
    static final String[] unitSpecifierNames = {
        "unknown", "meter"
    };

 // Rendering intents for srgb chunk
    static final String[] renderingIntentNames = {
        // 0
        "Perceptual",
        // 1
        "Relative colorimetric",
        // 2
        "Saturation",
        // 3
        "Absolute colorimetric"
    };

    // Color space types for Chroma->ColorSpaceType node
    static final String[] colorSpaceTypeNames = {
        "GRAY", null, "RGB", "RGB",
        "GRAY", null, "RGB"
    };

    static final String[] fcTL_disposalOperatorNames = {
            "none",
            "background",
            "previous"
    };

    static final String[] fcTL_blendOperatorNames = {
            "source",
            "over"
    };

    // IHDR chunk
    /** Ihdr_present */
    public boolean IHDR_present;
    /** Ihdr_width */
    public int IHDR_width;
    /** Ihdr_height */
    public int IHDR_height;
    /** IHDR 位深度 */
    public int IHDR_bitDepth;
    /** Ihdr_color类型 */
    public int IHDR_colorType;
    /** Ihdr_compressionmethod */
    public int IHDR_compressionMethod;
    /** Ihdr_filtermethod */
    public int IHDR_filterMethod;
        // 0 == none, 1 == adam7
        // ;
        /** Ihdr_interlacemethod */
        public int IHDR_interlaceMethod;

    // PLTE chunk
    /** Plte_present */
    public boolean PLTE_present;
    /** Plte_R */
    public byte[] PLTE_red;
    /** Plte_green */
    public byte[] PLTE_green;
    /** Plte_blue */
    public byte[] PLTE_blue;

 // 若非 null,则在编码过程中用于对调色板条目重新排序
 // 排序以最小化 trns 块的大小。因此,源中的某个索引
    // 'i' in the source should be encoded as index 'PLTE_order[i]'.
    // PLTE_order will be null unless 'initialize' is called with an
 // 索引颜色模型镜像类型。
    /** Plte_订单 */
    public int[] PLTE_order = null;

 // bkgd chunk
    // If external (non-PNG sourced) data has red = green = blue,
 // always 存储 it as gray 和 promote When.js.js 写入
    /** Bkgd_present */
    public boolean bKGD_present;
 // PNG_COLOR_GRAY, _RGB, 或 _PALETTE
    // ;
    /** Bkgd_color类型 */
    public int bKGD_colorType;
    /** Bkgd_索引 */
    public int bKGD_index;
    /** Bkgd_gray */
    public int bKGD_gray;
    /** Bkgd_R */
    public int bKGD_red;
    /** Bkgd_green */
    public int bKGD_green;
    /** Bkgd_blue */
    public int bKGD_blue;

 // chrm chunk
    /** Chrm_present */
    public boolean cHRM_present;
    /** Chrm_whitepointx坐标 */
    public int cHRM_whitePointX;
    /** Chrm_whitepointy坐标 */
    public int cHRM_whitePointY;
    /** Chrm_Rx坐标 */
    public int cHRM_redX;
    /** Chrm_Ry坐标 */
    public int cHRM_redY;
    /** Chrm_greenx坐标 */
    public int cHRM_greenX;
    /** Chrm_greeny坐标 */
    public int cHRM_greenY;
    /** Chrm_bluex坐标 */
    public int cHRM_blueX;
    /** Chrm_bluey坐标 */
    public int cHRM_blueY;

 // gama chunk
    /** Gama_present */
    public boolean gAMA_present;
    /** Gama_gamma */
    public int gAMA_gamma;

 // hist chunk
    /** Hist_present */
    public boolean hIST_present;
    /** Hist_histogram */
    public char[] hIST_histogram;

 // iccp chunk
    /** Iccp_present */
    public boolean iCCP_present;
    /** Iccp_配置文件名称 */
    public String iCCP_profileName;
    /** Iccp_compressionmethod */
    public int iCCP_compressionMethod;
    /** Iccp_compressed配置文件 */
    public byte[] iCCP_compressedProfile;

 // cicp chunk
    /** Cicp_present */
    public boolean cICP_present;
    /** Cicp_colourprimaries */
    public int cICP_colourPrimaries;
    /** Cicp_transferfunction */
    public int cICP_transferFunction;
    /** Cicp_matrixcoefficients */
    public int cICP_matrixCoefficients;
    /** Cicp_videofullrange标记 */
    public boolean cICP_videoFullRangeFlag;

 // exif chunk
    /** Exif_present */
    public boolean eXIf_present;
    /** Exif_数据 */
    public byte[] eXIf_data;

 // itxt chunk
    /** Itxt_keyword */
    public ArrayList<String> iTXt_keyword = new ArrayList<String>();
    /** Itxt_compression标记 */
    public ArrayList<Boolean> iTXt_compressionFlag = new ArrayList<Boolean>();
    /** Itxt_compressionmethod */
    public ArrayList<Integer> iTXt_compressionMethod = new ArrayList<Integer>();
    /** Itxt_language标签 */
    public ArrayList<String> iTXt_languageTag = new ArrayList<String>();
    /** Itxt_translatedkeyword */
    public ArrayList<String> iTXt_translatedKeyword = new ArrayList<String>();
    /** Itxt_文本 */
    public ArrayList<String> iTXt_text = new ArrayList<String>();

 // phys chunk
    /** Phys_present */
    public boolean pHYs_present;
    /** Phys_pixelsper单位xaxis */
    public int pHYs_pixelsPerUnitXAxis;
    /** Phys_pixelsper单位yaxis */
    public int pHYs_pixelsPerUnitYAxis;
        // 0 == unknown, 1 == meter
        // ;
        /** Phys_unitspecifier */
        public int pHYs_unitSpecifier;

 // sBIT chunk
    /** Sbit_present */
    public boolean sBIT_present;
        // PNG_COLOR_GRAY, _GRAY_ALPHA, _RGB, _RGB_ALPHA
        // ;
        /** Sbit_color类型 */
        public int sBIT_colorType;
    /** Sbit_graybits */
    public int sBIT_grayBits;
    /** Sbit_redbits */
    public int sBIT_redBits;
    /** Sbit_greenbits */
    public int sBIT_greenBits;
    /** Sbit_bluebits */
    public int sBIT_blueBits;
    /** Sbit_alphabits */
    public int sBIT_alphaBits;

 // splt chunk
    /** Splt_present */
    public boolean sPLT_present;
        // 1-79 characters
        // ;
        /** Splt_palette名称 */
        public String sPLT_paletteName;
 // 8 或 16
        // ;
        /** Splt_样本深度 */
        public int sPLT_sampleDepth;
    /** Splt_R */
    public int[] sPLT_red;
    /** Splt_green */
    public int[] sPLT_green;
    /** Splt_blue */
    public int[] sPLT_blue;
    /** Splt_alpha */
    public int[] sPLT_alpha;
    /** Splt_频率 */
    public int[] sPLT_frequency;

 // srgb chunk
    /** Srgb_present */
    public boolean sRGB_present;
    /** Srgb_renderingintent */
    public int sRGB_renderingIntent;

 // 文本 chunk
    // 1-79 characters
    // ;
    /** 文本_keyword */
    public ArrayList<String> tEXt_keyword = new ArrayList<String>();
    /** 文本_文本 */
    public ArrayList<String> tEXt_text = new ArrayList<String>();

 // 时间 chunk. Gives the 镜像 修改 时间.
    /** 时间_present */
    public boolean tIME_present;
    /** 时间_year */
    public int tIME_year;
    /** 时间_month */
    public int tIME_month;
    /** 时间_day */
    public int tIME_day;
    /** 时间_hour */
    public int tIME_hour;
    /** 时间_minute */
    public int tIME_minute;
    /** 时间_second */
    public int tIME_second;

 // 指定元数据是否包含镜像创建时间
    /** 创建_时间_present */
    public boolean creation_time_present;

 // 构成标准/文档/镜像创建时间的各个字段值。
    /** 创建_时间_year */
    public int creation_time_year;
    /** 创建_时间_month */
    public int creation_time_month;
    /** 创建_时间_day */
    public int creation_time_day;
    /** 创建_时间_hour */
    public int creation_time_hour;
    /** 创建_时间_minute */
    public int creation_time_minute;
    /** 创建_时间_second */
    public int creation_time_second;
    /** 创建_时间_偏移 */
    public ZoneOffset creation_time_offset;

    /*
    * 文本_创建_时间_present- Specifies whether 任意 文本 chunk (文本, itxt,
    * ztxt) exists with 镜像 创建 时间. The 数据 结构 corresponding
    * 转为 the 最后一个 decoded 文本 chunk with 创建 时间 是否 indicated by the
    * 迭代器- 文本_创建_时间_iter.
    *
    * 对含创建时间的文本块所做的任何更新，都会反映到
    * 标准/文档/镜像创建时间 之后 retrieving 时间 从 the 文本
    * chunk. If there are 多个 文本 chunks with 创建 时间, the 时间
    * retrieved 从 the 最后一个 decoded 文本 chunk will be used. A point 转为 笔记
    * 是否 that, retrieval 的 时间 从 文本 chunks 是否 possible only if the
    * encoded 时间 入 the chunk confirms 转为 either the recommended RFC1123
    * 格式化 或 ISO 格式化.
    *
    * Similarly, 任意 更新 转为 标准/文档/镜像创建时间 是否 reflected
    * on the 最后一个 decoded 文本 chunk's 数据 结构 with 时间 encoded 入
    * RFC1123 格式化. By 更新 the 文本 chunk's 数据 结构, we also
    * ensure that png镜像writer will 写入 镜像 创建 时间 on the 输出.
     */
    public boolean tEXt_creation_time_present;
    /** 文本_创建_时间_iter */
    private ListIterator<String> tEXt_creation_time_iter = null;
    /** 文本_创建时间密钥 */
    public static final String tEXt_creationTimeKey = "Creation Time";

 // trns chunk
    // If external (non-PNG sourced) data has red = green = blue,
 // always 存储 it as gray 和 promote When.js.js 写入
/** Trns_present */
public boolean tRNS_present;
 // PNG_COLOR_GRAY, _RGB, 或 _PALETTE
    // ;
    /** Trns_color类型 */
    public int tRNS_colorType;
 // May have fewer entries than PLTE_R, etc.
    // ;
    /** Trns_alpha */
    public byte[] tRNS_alpha;
    /** Trns_gray */
    public int tRNS_gray;
    /** Trns_R */
    public int tRNS_red;
    /** Trns_green */
    public int tRNS_green;
    /** Trns_blue */
    public int tRNS_blue;

 // ztxt chunk
    /** Z坐标txt_keyword */
    public ArrayList<String> zTXt_keyword = new ArrayList<String>();
    /** Z坐标txt_compressionmethod */
    public ArrayList<Integer> zTXt_compressionMethod = new ArrayList<Integer>();
    /** Z坐标txt_文本 */
    public ArrayList<String> zTXt_text = new ArrayList<String>();

 // actl chunk
    /** actl_present */
    public boolean acTL_present;
    /** actl_num_帧 */
    public int acTL_num_frames;
    /** actl_num_plays */
    public int acTL_num_plays;

 // fcTL chunk
    /** fctl_present */
    public boolean fcTL_present;
    /** fctl_sequence_数字 */
    public int fcTL_sequence_number;
    /** fctl_width */
    public int fcTL_width;
    /** fctl_height */
    public int fcTL_height;
    /** fcTL x 偏移量 */
    public int fcTL_x_offset;
    /** fcTL y 偏移量 */
    public int fcTL_y_offset;
    /** fctl_延迟_num */
    public int fcTL_delay_num;
    /** fctl_延迟_den */
    public int fcTL_delay_den;
    /** fctl_dispose_op */
    public int fcTL_dispose_op;
    /** fctl_blend_op */
    public int fcTL_blend_op;

 // fdat chunk
    /** fdat_present */
    public boolean fdAT_present;
    /** fdat_sequence_数字 */
    public int fdAT_sequence_number;

    // Unknown chunks
    /** Unknownchunk类型 */
    public ArrayList<String> unknownChunkType = new ArrayList<String>();
    /** Unknownchunk数据 */
    public ArrayList<byte[]> unknownChunkData = new ArrayList<byte[]>();

    /** 创建 pngmetadata 实例 */
    public PNGMetadata() {
        super(true,
              nativeMetadataFormatName,
              nativeMetadataFormatClassName,
              null, null);
    }

    /**
    * 创建 pngmetadata 实例。
    * @param metadata metadata
    */
    public PNGMetadata(IIOMetadata metadata) {
        super(invokeBoolean(metadata, "isNativeFormat"),
              invokeString(metadata, "getNativeMetadataFormatName"),
              invokeString(metadata, "getNativeMetadataFormatClassName"),
              /**
              * invoke布尔值。
              * @param target Target
              * @param methodName 方法名称
              * @return invoke布尔值的结果
               */
              null, null);
    }

    private static Boolean invokeBoolean(Object target, String methodName) {
        try {
            return (Boolean) ReflectUtils.invoke(target, methodName, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String invokeString(Object target, String methodName) {
        try {
            return (String) ReflectUtils.invoke(target, methodName, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
    * 初始化
    *
    * @param imageType 镜像类型
    * @param numBands numbands
     */
    public void initialize(ImageTypeSpecifier imageType, int numBands) {
        initialize(imageType.getColorModel(), imageType.getSampleModel(), numBands);
    }

    /**
    * 设置 IHDR_bitDepth 和 IHDR_colorType 变量。
    * The {@code numBands} 参数 是否 necessary 自
    * we may only be 写入 a subset 的 the 镜像 bands.
    * @param colorModel color模型
    * @param sampleModel 样本模型
    * @param numBands numbands
     */
    public void initialize(ColorModel colorModel, SampleModel sampleModel, int numBands) {

 // 初始化 IHDR_bitDepth
        int[] sampleSize = sampleModel.getSampleSize();
        int bitDepth = sampleSize[0];
 // 在所有通道中选择最大的位深
        // Fixes bug 4413109
        for (int i = 1; i < sampleSize.length; i++) {
            if (sampleSize[i] > bitDepth) {
                bitDepth = sampleSize[i];
            }
        }
 // 多通道图像必须具有 8 或 16 的位深
        if (sampleSize.length > 1 && bitDepth < 8) {
            bitDepth = 8;
        }

 // 将位深向上取整为 2 的幂
        if (bitDepth > 2 && bitDepth < 4) {
            bitDepth = 4;
        } else if (bitDepth > 4 && bitDepth < 8) {
            bitDepth = 8;
        } else if (bitDepth > 8 && bitDepth < 16) {
            bitDepth = 16;
        } else if (bitDepth > 16) {
            throw new RuntimeException("bitDepth > 16!");
        }
        IHDR_bitDepth = bitDepth;

 // 初始化 IHDR_color类型
        if (colorModel instanceof IndexColorModel icm) {
            int size = icm.getMapSize();

            byte[] reds = new byte[size];
            icm.getReds(reds);
            byte[] greens = new byte[size];
            icm.getGreens(greens);
            byte[] blues = new byte[size];
            icm.getBlues(blues);

            // Determine whether the color tables are actually a gray ramp
 // if the color 类型 是否包含 not been 设置 上一个
            boolean isGray = false;
            if (!IHDR_present ||
                (IHDR_colorType != PNG.PNG_COLOR_PALETTE)) {
                isGray = true;
                int scale = 255/((1 << IHDR_bitDepth) - 1);
                for (int i = 0; i < size; i++) {
                    byte red = reds[i];
                    if ((red != (byte)(i*scale)) ||
                        (red != greens[i]) ||
                        (red != blues[i])) {
                        isGray = false;
                        break;
                    }
                }
            }

            // Determine whether transparency exists
            boolean hasAlpha = colorModel.hasAlpha();

            byte[] alpha = null;
            if (hasAlpha) {
                alpha = new byte[size];
                icm.getAlphas(alpha);
            }

            /*
              * NB: PNG_COLOR_GRAY_ALPHA color 类型 may be not optimal for 镜像
              * contained more than 1024 pixels (或 even than 768 pixels 入 大小写 的
              * 单个 transparent pixel 入 palette).
              * For such 镜像 alpha 样本 入 raster will occupy more space than
              * it 是否 required 转为 存储 palette so it could be ReasonML 转为
              * use PNG_COLOR_PALETTE color 类型 for large 镜像.
             */

            if (isGray && hasAlpha && (bitDepth == 8 || bitDepth == 16)) {
                IHDR_colorType = PNG.PNG_COLOR_GRAY_ALPHA;
            } else if (isGray && !hasAlpha) {
                IHDR_colorType = PNG.PNG_COLOR_GRAY;
            } else {
                IHDR_colorType = PNG.PNG_COLOR_PALETTE;
                PLTE_present = true;
                PLTE_order = null;
                PLTE_red = reds.clone();
                PLTE_green = greens.clone();
                PLTE_blue = blues.clone();

                if (hasAlpha) {
                    tRNS_present = true;
                    tRNS_colorType = PNG.PNG_COLOR_PALETTE;

                    PLTE_order = new int[alpha.length];

                    // Reorder the palette so that non-opaque entries
 // come 第一个.  自 the trns chunk 执行 not have
                    // to store trailing 255's, this can save a
 // considerable amount 的 space When.js.js 编码
 // 镜像 with only one transparent pixel 值,
 // e.g., 镜像 从 GIF 源.

                    byte[] newAlpha = new byte[alpha.length];

 // 扫描 for non-opaque entries 和 assign them
 // 位置 启动 at 0.
                    int newIndex = 0;
                    for (int i = 0; i < alpha.length; i++) {
                        if (alpha[i] != (byte)255) {
                            PLTE_order[i] = newIndex;
                            newAlpha[newIndex] = alpha[i];
                            ++newIndex;
                        }
                    }
                    int numTransparent = newIndex;

 // 扫描 for opaque entries 和 assign them
 // 位置 following the non-opaque entries.
                    for (int i = 0; i < alpha.length; i++) {
                        if (alpha[i] == (byte)255) {
                            PLTE_order[i] = newIndex++;
                        }
                    }

                    // Reorder the palettes
                    byte[] oldRed = PLTE_red;
                    byte[] oldGreen = PLTE_green;
                    byte[] oldBlue = PLTE_blue;
 // 全部 have the same 长度
                    // = oldRed.length;
                    int len = oldRed.length;
                    PLTE_red = new byte[len];
                    PLTE_green = new byte[len];
                    PLTE_blue = new byte[len];
                    for (int i = 0; i < len; i++) {
                        PLTE_red[PLTE_order[i]] = oldRed[i];
                        PLTE_green[PLTE_order[i]] = oldGreen[i];
                        PLTE_blue[PLTE_order[i]] = oldBlue[i];
                    }

 // 副本 only the transparent entries into trns_alpha
                    tRNS_alpha = new byte[numTransparent];
                    System.arraycopy(newAlpha, 0,
                                     tRNS_alpha, 0, numTransparent);
                }
            }
        } else {
            if (numBands == 1) {
                IHDR_colorType = PNG.PNG_COLOR_GRAY;
            } else if (numBands == 2) {
                IHDR_colorType = PNG.PNG_COLOR_GRAY_ALPHA;
            } else if (numBands == 3) {
                IHDR_colorType = PNG.PNG_COLOR_RGB;
            } else if (numBands == 4) {
                IHDR_colorType = PNG.PNG_COLOR_RGB_ALPHA;
            } else {
                throw new RuntimeException("Number of bands not 1-4!");
            }
        }

        IHDR_present = true;
    }

    /**
    * 是否读取Only
    *
    * @return 是否读取only的结果
     */
    public boolean isReadOnly() {
        return false;
    }

    /**
    * clonebytesarray列表
    *
    * @param in 入
    * @return clonebytesarray列表的结果
     */
    private ArrayList<byte[]> cloneBytesArrayList(ArrayList<byte[]> in) {
        if (in == null) {
            return null;
        } else {
            ArrayList<byte[]> list = new ArrayList<byte[]>(in.size());
            for (byte[] b: in) {
                list.add((b == null) ? null : b.clone());
            }
            return list;
        }
    }

    // Deep clone
    /**
    * Clone
    *
    * @return clone的结果
     */
    public Object clone() {
        PNGMetadata metadata;
        try {
            metadata = (PNGMetadata)super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }

 // unknownchunk数据 needs deep clone
        metadata.unknownChunkData =
            cloneBytesArrayList(this.unknownChunkData);

        return metadata;
    }

    /**
    * 获取as树
    *
    * @param formatName 格式化名称
    * @return 获取as树的结果
     */
    public Node getAsTree(String formatName) {
        if (formatName.equals(nativeMetadataFormatName)) {
            return getNativeTree();
        } else if (formatName.equals
                   (IIOMetadataFormatImpl.standardMetadataFormatName)) {
            return getStandardTree();
        } else {
            throw new IllegalArgumentException("Not a recognized format!");
        }
    }

    /**
    * 获取NAT树
    *
    * @return 获取NAT树的结果
     */
    private Node getNativeTree() {
 // scratch 节点
 // 空;
        IIOMetadataNode node = null;
        IIOMetadataNode root = new IIOMetadataNode(nativeMetadataFormatName);

        // IHDR
        if (IHDR_present) {
            IIOMetadataNode IHDR_node = new IIOMetadataNode("IHDR");
            IHDR_node.setAttribute("width", Integer.toString(IHDR_width));
            IHDR_node.setAttribute("height", Integer.toString(IHDR_height));
            IHDR_node.setAttribute("bitDepth",
                                   Integer.toString(IHDR_bitDepth));
            IHDR_node.setAttribute("colorType",
                                   IHDR_colorTypeNames[IHDR_colorType]);
 // IHDR_compression方法 must be 0 入 PNG 1.1
            IHDR_node.setAttribute("compressionMethod",
                          IHDR_compressionMethodNames[IHDR_compressionMethod]);
 // IHDR_过滤器方法 must be 0 入 PNG 1.1
            IHDR_node.setAttribute("filterMethod",
                                    IHDR_filterMethodNames[IHDR_filterMethod]);
            IHDR_node.setAttribute("interlaceMethod",
                              IHDR_interlaceMethodNames[IHDR_interlaceMethod]);
            root.appendChild(IHDR_node);
        }

        // PLTE
        if (PLTE_present) {
            IIOMetadataNode PLTE_node = new IIOMetadataNode("PLTE");
            int numEntries = PLTE_red.length;
            for (int i = 0; i < numEntries; i++) {
                IIOMetadataNode entry = new IIOMetadataNode("PLTEEntry");
                entry.setAttribute("index", Integer.toString(i));
                entry.setAttribute("red",
                                   Integer.toString(PLTE_red[i] & 0xff));
                entry.setAttribute("green",
                                   Integer.toString(PLTE_green[i] & 0xff));
                entry.setAttribute("blue",
                                   Integer.toString(PLTE_blue[i] & 0xff));
                PLTE_node.appendChild(entry);
            }

            root.appendChild(PLTE_node);
        }

 // bkgd
        if (bKGD_present) {
            IIOMetadataNode bKGD_node = new IIOMetadataNode("bKGD");

            if (bKGD_colorType == PNG.PNG_COLOR_PALETTE) {
                node = new IIOMetadataNode("bKGD_Palette");
                node.setAttribute("index", Integer.toString(bKGD_index));
            } else if (bKGD_colorType == PNG.PNG_COLOR_GRAY) {
                node = new IIOMetadataNode("bKGD_Grayscale");
                node.setAttribute("gray", Integer.toString(bKGD_gray));
            } else if (bKGD_colorType == PNG.PNG_COLOR_RGB) {
                node = new IIOMetadataNode("bKGD_RGB");
                node.setAttribute("red", Integer.toString(bKGD_red));
                node.setAttribute("green", Integer.toString(bKGD_green));
                node.setAttribute("blue", Integer.toString(bKGD_blue));
            }
            bKGD_node.appendChild(node);

            root.appendChild(bKGD_node);
        }

 // chrm
        if (cHRM_present) {
            IIOMetadataNode cHRM_node = new IIOMetadataNode("cHRM");
            cHRM_node.setAttribute("whitePointX",
                              Integer.toString(cHRM_whitePointX));
            cHRM_node.setAttribute("whitePointY",
                              Integer.toString(cHRM_whitePointY));
            cHRM_node.setAttribute("redX", Integer.toString(cHRM_redX));
            cHRM_node.setAttribute("redY", Integer.toString(cHRM_redY));
            cHRM_node.setAttribute("greenX", Integer.toString(cHRM_greenX));
            cHRM_node.setAttribute("greenY", Integer.toString(cHRM_greenY));
            cHRM_node.setAttribute("blueX", Integer.toString(cHRM_blueX));
            cHRM_node.setAttribute("blueY", Integer.toString(cHRM_blueY));

            root.appendChild(cHRM_node);
        }

 // gama
        if (gAMA_present) {
            IIOMetadataNode gAMA_node = new IIOMetadataNode("gAMA");
            gAMA_node.setAttribute("value", Integer.toString(gAMA_gamma));

            root.appendChild(gAMA_node);
        }

 // hist
        if (hIST_present) {
            IIOMetadataNode hIST_node = new IIOMetadataNode("hIST");

            for (int i = 0; i < hIST_histogram.length; i++) {
                IIOMetadataNode hist =
                    new IIOMetadataNode("hISTEntry");
                hist.setAttribute("index", Integer.toString(i));
                hist.setAttribute("value",
                                  Integer.toString(hIST_histogram[i]));
                hIST_node.appendChild(hist);
            }

            root.appendChild(hIST_node);
        }

 // iccp
        if (iCCP_present) {
            IIOMetadataNode iCCP_node = new IIOMetadataNode("iCCP");
            iCCP_node.setAttribute("profileName", iCCP_profileName);
            iCCP_node.setAttribute("compressionMethod",
                          iCCP_compressionMethodNames[iCCP_compressionMethod]);

            Object profile = iCCP_compressedProfile;
            if (profile != null) {
                profile = ((byte[])profile).clone();
            }
            iCCP_node.setUserObject(profile);

            root.appendChild(iCCP_node);
        }

 // cicp
        if (cICP_present) {
            IIOMetadataNode cICP_node = new IIOMetadataNode("cICP");
            cICP_node.setAttribute("colourPrimaries", Integer.toString(cICP_colourPrimaries));
            cICP_node.setAttribute("transferFunction", Integer.toString(cICP_transferFunction));
            cICP_node.setAttribute("matrixCoefficients", Integer.toString(cICP_matrixCoefficients));
            cICP_node.setAttribute("videoFullRangeFlag", cICP_videoFullRangeFlag ? "TRUE" : "FALSE");

            root.appendChild(cICP_node);
        }

 // exif
        if (eXIf_present) {
            IIOMetadataNode eXIf_node = new IIOMetadataNode("eXIf");
            Object data = eXIf_data;
            if (data != null) {
                data = ((byte[])data).clone();
            }
            eXIf_node.setUserObject(data);

            root.appendChild(eXIf_node);
        }

 // itxt
        if (iTXt_keyword.size() > 0) {
            IIOMetadataNode iTXt_parent = new IIOMetadataNode("iTXt");
            for (int i = 0; i < iTXt_keyword.size(); i++) {
                IIOMetadataNode iTXt_node = new IIOMetadataNode("iTXtEntry");
                iTXt_node.setAttribute("keyword", iTXt_keyword.get(i));
                iTXt_node.setAttribute("compressionFlag",
                        iTXt_compressionFlag.get(i) ? "TRUE" : "FALSE");
                iTXt_node.setAttribute("compressionMethod",
                        iTXt_compressionMethod.get(i).toString());
                iTXt_node.setAttribute("languageTag",
                                       iTXt_languageTag.get(i));
                iTXt_node.setAttribute("translatedKeyword",
                                       iTXt_translatedKeyword.get(i));
                iTXt_node.setAttribute("text", iTXt_text.get(i));

                iTXt_parent.appendChild(iTXt_node);
            }

            root.appendChild(iTXt_parent);
        }

 // phys
        if (pHYs_present) {
            IIOMetadataNode pHYs_node = new IIOMetadataNode("pHYs");
            pHYs_node.setAttribute("pixelsPerUnitXAxis",
                              Integer.toString(pHYs_pixelsPerUnitXAxis));
            pHYs_node.setAttribute("pixelsPerUnitYAxis",
                                   Integer.toString(pHYs_pixelsPerUnitYAxis));
            pHYs_node.setAttribute("unitSpecifier",
                                   unitSpecifierNames[pHYs_unitSpecifier]);

            root.appendChild(pHYs_node);
        }

 // sBIT
        if (sBIT_present) {
            IIOMetadataNode sBIT_node = new IIOMetadataNode("sBIT");

            if (sBIT_colorType == PNG.PNG_COLOR_GRAY) {
                node = new IIOMetadataNode("sBIT_Grayscale");
                node.setAttribute("gray",
                                  Integer.toString(sBIT_grayBits));
            } else if (sBIT_colorType == PNG.PNG_COLOR_GRAY_ALPHA) {
                node = new IIOMetadataNode("sBIT_GrayAlpha");
                node.setAttribute("gray",
                                  Integer.toString(sBIT_grayBits));
                node.setAttribute("alpha",
                                  Integer.toString(sBIT_alphaBits));
            } else if (sBIT_colorType == PNG.PNG_COLOR_RGB) {
                node = new IIOMetadataNode("sBIT_RGB");
                node.setAttribute("red",
                                  Integer.toString(sBIT_redBits));
                node.setAttribute("green",
                                  Integer.toString(sBIT_greenBits));
                node.setAttribute("blue",
                                  Integer.toString(sBIT_blueBits));
            } else if (sBIT_colorType == PNG.PNG_COLOR_RGB_ALPHA) {
                node = new IIOMetadataNode("sBIT_RGBAlpha");
                node.setAttribute("red",
                                  Integer.toString(sBIT_redBits));
                node.setAttribute("green",
                                  Integer.toString(sBIT_greenBits));
                node.setAttribute("blue",
                                  Integer.toString(sBIT_blueBits));
                node.setAttribute("alpha",
                                  Integer.toString(sBIT_alphaBits));
            } else if (sBIT_colorType == PNG.PNG_COLOR_PALETTE) {
                node = new IIOMetadataNode("sBIT_Palette");
                node.setAttribute("red",
                                  Integer.toString(sBIT_redBits));
                node.setAttribute("green",
                                  Integer.toString(sBIT_greenBits));
                node.setAttribute("blue",
                                  Integer.toString(sBIT_blueBits));
            }
            sBIT_node.appendChild(node);

            root.appendChild(sBIT_node);
        }

 // splt
        if (sPLT_present) {
            IIOMetadataNode sPLT_node = new IIOMetadataNode("sPLT");

            sPLT_node.setAttribute("name", sPLT_paletteName);
            sPLT_node.setAttribute("sampleDepth",
                                   Integer.toString(sPLT_sampleDepth));

            int numEntries = sPLT_red.length;
            for (int i = 0; i < numEntries; i++) {
                IIOMetadataNode entry = new IIOMetadataNode("sPLTEntry");
                entry.setAttribute("index", Integer.toString(i));
                entry.setAttribute("red", Integer.toString(sPLT_red[i]));
                entry.setAttribute("green", Integer.toString(sPLT_green[i]));
                entry.setAttribute("blue", Integer.toString(sPLT_blue[i]));
                entry.setAttribute("alpha", Integer.toString(sPLT_alpha[i]));
                entry.setAttribute("frequency",
                                  Integer.toString(sPLT_frequency[i]));
                sPLT_node.appendChild(entry);
            }

            root.appendChild(sPLT_node);
        }

 // srgb
        if (sRGB_present) {
            IIOMetadataNode sRGB_node = new IIOMetadataNode("sRGB");
            sRGB_node.setAttribute("renderingIntent",
                                   renderingIntentNames[sRGB_renderingIntent]);

            root.appendChild(sRGB_node);
        }

 // 文本
        if (tEXt_keyword.size() > 0) {
            IIOMetadataNode tEXt_parent = new IIOMetadataNode("tEXt");
            for (int i = 0; i < tEXt_keyword.size(); i++) {
                IIOMetadataNode tEXt_node = new IIOMetadataNode("tEXtEntry");
                tEXt_node.setAttribute("keyword" , tEXt_keyword.get(i));
                tEXt_node.setAttribute("value" , tEXt_text.get(i));

                tEXt_parent.appendChild(tEXt_node);
            }

            root.appendChild(tEXt_parent);
        }

 // 时间
        if (tIME_present) {
            IIOMetadataNode tIME_node = new IIOMetadataNode("tIME");
            tIME_node.setAttribute("year", Integer.toString(tIME_year));
            tIME_node.setAttribute("month", Integer.toString(tIME_month));
            tIME_node.setAttribute("day", Integer.toString(tIME_day));
            tIME_node.setAttribute("hour", Integer.toString(tIME_hour));
            tIME_node.setAttribute("minute", Integer.toString(tIME_minute));
            tIME_node.setAttribute("second", Integer.toString(tIME_second));

            root.appendChild(tIME_node);
        }

 // trns
        if (tRNS_present) {
            IIOMetadataNode tRNS_node = new IIOMetadataNode("tRNS");

            if (tRNS_colorType == PNG.PNG_COLOR_PALETTE) {
                node = new IIOMetadataNode("tRNS_Palette");

                for (int i = 0; i < tRNS_alpha.length; i++) {
                    IIOMetadataNode entry =
                        new IIOMetadataNode("tRNS_PaletteEntry");
                    entry.setAttribute("index", Integer.toString(i));
                    entry.setAttribute("alpha",
                                       Integer.toString(tRNS_alpha[i] & 0xff));
                    node.appendChild(entry);
                }
            } else if (tRNS_colorType == PNG.PNG_COLOR_GRAY) {
                node = new IIOMetadataNode("tRNS_Grayscale");
                node.setAttribute("gray", Integer.toString(tRNS_gray));
            } else if (tRNS_colorType == PNG.PNG_COLOR_RGB) {
                node = new IIOMetadataNode("tRNS_RGB");
                node.setAttribute("red", Integer.toString(tRNS_red));
                node.setAttribute("green", Integer.toString(tRNS_green));
                node.setAttribute("blue", Integer.toString(tRNS_blue));
            }
            tRNS_node.appendChild(node);

            root.appendChild(tRNS_node);
        }

 // ztxt
        if (zTXt_keyword.size() > 0) {
            IIOMetadataNode zTXt_parent = new IIOMetadataNode("zTXt");
            for (int i = 0; i < zTXt_keyword.size(); i++) {
                IIOMetadataNode zTXt_node = new IIOMetadataNode("zTXtEntry");
                zTXt_node.setAttribute("keyword", zTXt_keyword.get(i));

                int cm = (zTXt_compressionMethod.get(i)).intValue();
                zTXt_node.setAttribute("compressionMethod",
                                       zTXt_compressionMethodNames[cm]);

                zTXt_node.setAttribute("text", zTXt_text.get(i));

                zTXt_parent.appendChild(zTXt_node);
            }

            root.appendChild(zTXt_parent);
        }

 // actl
        if (acTL_present) {
            IIOMetadataNode acTL_node = new IIOMetadataNode("acTL");
            acTL_node.setAttribute("num_frames", Integer.toString(acTL_num_frames));
            acTL_node.setAttribute("num_plays", Integer.toString(acTL_num_plays));

            root.appendChild(acTL_node);
        }

 // fcTL
        if (fcTL_present) {
            node = new IIOMetadataNode("fcTL");
            node.setAttribute("sequence_number",
                    Integer.toString(fcTL_sequence_number));
            node.setAttribute("width",
                    Integer.toString(fcTL_width));
            node.setAttribute("height",
                    Integer.toString(fcTL_height));
            node.setAttribute("x_offset", Integer.toString(fcTL_width));
            node.setAttribute("y_offset", Integer.toString(fcTL_height));
            node.setAttribute("delay_num", Integer.toString(fcTL_delay_num));
            node.setAttribute("delay_den", Integer.toString(fcTL_delay_den));
            node.setAttribute("dispose_op", fcTL_disposalOperatorNames[fcTL_dispose_op]);
            node.setAttribute("blend_op", fcTL_blendOperatorNames[fcTL_blend_op]);
            root.appendChild(node);
        }

 // fdat
        if (fdAT_present) {
            node = new IIOMetadataNode("fdAT");
            node.setAttribute("sequence_number",
                    Integer.toString(fdAT_sequence_number));
            root.appendChild(node);
        }

        // Unknown chunks
        if (unknownChunkType.size() > 0) {
            IIOMetadataNode unknown_parent =
                new IIOMetadataNode("UnknownChunks");
            for (int i = 0; i < unknownChunkType.size(); i++) {
                IIOMetadataNode unknown_node =
                    new IIOMetadataNode("UnknownChunk");
                unknown_node.setAttribute("type",
                                          unknownChunkType.get(i));
                unknown_node.setUserObject(unknownChunkData.get(i));

                unknown_parent.appendChild(unknown_node);
            }

            root.appendChild(unknown_parent);
        }

        return root;
    }

    /**
    * 获取num通道
    *
    * @return 获取num通道的结果
     */
    private int getNumChannels() {
 // Determine 数字 的 通道
        // Be careful about palette color with transparency
        int numChannels = IHDR_numChannels[IHDR_colorType];
        if (IHDR_colorType == PNG.PNG_COLOR_PALETTE &&
            tRNS_present && tRNS_colorType == IHDR_colorType) {
            numChannels = 4;
        }
        return numChannels;
    }

    /**
    * 获取标准chroma节点
    *
    * @return 获取标准chroma节点的结果
     */
    public IIOMetadataNode getStandardChromaNode() {
        IIOMetadataNode chroma_node = new IIOMetadataNode("Chroma");
 // scratch 节点
 // 空;
        IIOMetadataNode node = null;

        node = new IIOMetadataNode("ColorSpaceType");
        node.setAttribute("name", colorSpaceTypeNames[IHDR_colorType]);
        chroma_node.appendChild(node);

        node = new IIOMetadataNode("NumChannels");
        node.setAttribute("value", Integer.toString(getNumChannels()));
        chroma_node.appendChild(node);

        if (gAMA_present) {
            node = new IIOMetadataNode("Gamma");
            node.setAttribute("value", Float.toString(gAMA_gamma*1.0e-5F));
            chroma_node.appendChild(node);
        }

        node = new IIOMetadataNode("BlackIsZero");
        node.setAttribute("value", "TRUE");
        chroma_node.appendChild(node);

        if (PLTE_present) {
            boolean hasAlpha = tRNS_present &&
                (tRNS_colorType == PNG.PNG_COLOR_PALETTE);

            node = new IIOMetadataNode("Palette");
            for (int i = 0; i < PLTE_red.length; i++) {
                IIOMetadataNode entry =
                    new IIOMetadataNode("PaletteEntry");
                entry.setAttribute("index", Integer.toString(i));
                entry.setAttribute("red",
                                   Integer.toString(PLTE_red[i] & 0xff));
                entry.setAttribute("green",
                                   Integer.toString(PLTE_green[i] & 0xff));
                entry.setAttribute("blue",
                                   Integer.toString(PLTE_blue[i] & 0xff));
                if (hasAlpha) {
                    int alpha = (i < tRNS_alpha.length) ?
                        (tRNS_alpha[i] & 0xff) : 255;
                    entry.setAttribute("alpha", Integer.toString(alpha));
                }
                node.appendChild(entry);
            }
            chroma_node.appendChild(node);
        }

        if (bKGD_present) {
            if (bKGD_colorType == PNG.PNG_COLOR_PALETTE) {
                node = new IIOMetadataNode("BackgroundIndex");
                node.setAttribute("value", Integer.toString(bKGD_index));
            } else {
                node = new IIOMetadataNode("BackgroundColor");
                int r, g, b;

                if (bKGD_colorType == PNG.PNG_COLOR_GRAY) {
                    r = g = b = bKGD_gray;
                } else {
                    r = bKGD_red;
                    g = bKGD_green;
                    b = bKGD_blue;
                }
                node.setAttribute("red", Integer.toString(r));
                node.setAttribute("green", Integer.toString(g));
                node.setAttribute("blue", Integer.toString(b));
            }
            chroma_node.appendChild(node);
        }

        return chroma_node;
    }

    /**
    * 获取标准compression节点
    *
    * @return 获取标准compression节点的结果
     */
    public IIOMetadataNode getStandardCompressionNode() {
        IIOMetadataNode compression_node = new IIOMetadataNode("Compression");
 // scratch 节点
 // 空;
        IIOMetadataNode node = null;

        node = new IIOMetadataNode("CompressionTypeName");
        node.setAttribute("value", "deflate");
        compression_node.appendChild(node);

        node = new IIOMetadataNode("Lossless");
        node.setAttribute("value", "TRUE");
        compression_node.appendChild(node);

        node = new IIOMetadataNode("NumProgressiveScans");
        node.setAttribute("value",
                          (IHDR_interlaceMethod == 0) ? "1" : "7");
        compression_node.appendChild(node);

        return compression_node;
    }

    /**
    * Repeat
    *
    * @param s s
    * @param times 时间
    * @return repeat的结果
     */
    private String repeat(String s, int times) {
        if (times == 1) {
            return s;
        }
        StringBuilder sb = new StringBuilder((s.length() + 1)*times - 1);
        sb.append(s);
        for (int i = 1; i < times; i++) {
            sb.append(" ");
            sb.append(s);
        }
        return sb.toString();
    }

    /**
    * 获取标准数据节点
    *
    * @return 获取标准数据节点的结果
     */
    public IIOMetadataNode getStandardDataNode() {
        IIOMetadataNode data_node = new IIOMetadataNode("Data");
 // scratch 节点
 // 空;
        IIOMetadataNode node = null;

        node = new IIOMetadataNode("PlanarConfiguration");
        node.setAttribute("value", "PixelInterleaved");
        data_node.appendChild(node);

        node = new IIOMetadataNode("SampleFormat");
        node.setAttribute("value",
                          IHDR_colorType == PNG.PNG_COLOR_PALETTE ?
                          "Index" : "UnsignedIntegral");
        data_node.appendChild(node);

        String bitDepth = Integer.toString(IHDR_bitDepth);
        node = new IIOMetadataNode("BitsPerSample");
        node.setAttribute("value", repeat(bitDepth, getNumChannels()));
        data_node.appendChild(node);

        if (sBIT_present) {
            node = new IIOMetadataNode("SignificantBitsPerSample");
            String sbits;
            if (sBIT_colorType == PNG.PNG_COLOR_GRAY ||
                sBIT_colorType == PNG.PNG_COLOR_GRAY_ALPHA) {
                sbits = Integer.toString(sBIT_grayBits);
            } else {
                sbits = sBIT_redBits + " " +
                        sBIT_greenBits + " " +
                        sBIT_blueBits;
            }

            if (sBIT_colorType == PNG.PNG_COLOR_GRAY_ALPHA ||
                sBIT_colorType == PNG.PNG_COLOR_RGB_ALPHA) {
                sbits += " " + sBIT_alphaBits;
            }

            node.setAttribute("value", sbits);
            data_node.appendChild(node);
        }

 // 样本msb

        return data_node;
    }

    /**
    * 获取标准维度节点
    *
    * @return 获取标准维度节点的结果
     */
    public IIOMetadataNode getStandardDimensionNode() {
        IIOMetadataNode dimension_node = new IIOMetadataNode("Dimension");
 // scratch 节点
 // 空;
        IIOMetadataNode node = null;

        node = new IIOMetadataNode("PixelAspectRatio");
        float ratio = pHYs_present ?
            (float)pHYs_pixelsPerUnitXAxis/pHYs_pixelsPerUnitYAxis : 1.0F;
        node.setAttribute("value", Float.toString(ratio));
        dimension_node.appendChild(node);

        node = new IIOMetadataNode("ImageOrientation");
        node.setAttribute("value", "Normal");
        dimension_node.appendChild(node);

        if (pHYs_present && pHYs_unitSpecifier == PHYS_UNIT_METER) {
            node = new IIOMetadataNode("HorizontalPixelSize");
            node.setAttribute("value",
                              Float.toString(1000.0F/pHYs_pixelsPerUnitXAxis));
            dimension_node.appendChild(node);

            node = new IIOMetadataNode("VerticalPixelSize");
            node.setAttribute("value",
                              Float.toString(1000.0F/pHYs_pixelsPerUnitYAxis));
            dimension_node.appendChild(node);
        }

        if (fcTL_present) {
            node = new IIOMetadataNode("HorizontalPixelOffset");
            node.setAttribute("value", Integer.toString(fcTL_x_offset));
            dimension_node.appendChild(node);

            node = new IIOMetadataNode("VerticalPixelOffset");
            node.setAttribute("value", Integer.toString(fcTL_y_offset));
            dimension_node.appendChild(node);
        }

        return dimension_node;
    }

    /**
    * 获取标准文档节点
    *
    * @return 获取标准文档节点的结果
     */
    public IIOMetadataNode getStandardDocumentNode() {
        IIOMetadataNode document_node = null;

 // 检查 if 镜像 修改 时间 exists
        if (tIME_present) {
 // 创建 新 文档 节点
            document_node = new IIOMetadataNode("Document");

 // 节点 转为 hold 镜像 修改 时间
            IIOMetadataNode node = new IIOMetadataNode("ImageModificationTime");
            node.setAttribute("year", Integer.toString(tIME_year));
            node.setAttribute("month", Integer.toString(tIME_month));
            node.setAttribute("day", Integer.toString(tIME_day));
            node.setAttribute("hour", Integer.toString(tIME_hour));
            node.setAttribute("minute", Integer.toString(tIME_minute));
            node.setAttribute("second", Integer.toString(tIME_second));
            document_node.appendChild(node);
        }

 // 检查 if 镜像 创建 时间 exists
        if (creation_time_present) {
            if (document_node == null) {
 // 创建 新 文档 节点
                document_node = new IIOMetadataNode("Document");
            }

 // 节点 转为 hold 镜像 创建 时间
            IIOMetadataNode node = new IIOMetadataNode("ImageCreationTime");
            node.setAttribute("year", Integer.toString(creation_time_year));
            node.setAttribute("month", Integer.toString(creation_time_month));
            node.setAttribute("day", Integer.toString(creation_time_day));
            node.setAttribute("hour", Integer.toString(creation_time_hour));
            node.setAttribute("minute", Integer.toString(creation_time_minute));
            node.setAttribute("second", Integer.toString(creation_time_second));
            document_node.appendChild(node);
        }

        return document_node;
    }

    /**
    * 获取标准文本节点
    *
    * @return 获取标准文本节点的结果
     */
    public IIOMetadataNode getStandardTextNode() {
        int numEntries = tEXt_keyword.size() +
            iTXt_keyword.size() + zTXt_keyword.size();
        if (numEntries == 0) {
            return null;
        }

        IIOMetadataNode text_node = new IIOMetadataNode("Text");
 // scratch 节点
 // 空;
        IIOMetadataNode node = null;

        for (int i = 0; i < tEXt_keyword.size(); i++) {
            node = new IIOMetadataNode("TextEntry");
            node.setAttribute("keyword", tEXt_keyword.get(i));
            node.setAttribute("value", tEXt_text.get(i));
            node.setAttribute("encoding", "ISO-8859-1");
            node.setAttribute("compression", "none");

            text_node.appendChild(node);
        }

        for (int i = 0; i < iTXt_keyword.size(); i++) {
            node = new IIOMetadataNode("TextEntry");
            node.setAttribute("keyword", iTXt_keyword.get(i));
            node.setAttribute("value", iTXt_text.get(i));
            node.setAttribute("language",
                              iTXt_languageTag.get(i));
            if (iTXt_compressionFlag.get(i)) {
                node.setAttribute("compression", "zip");
            } else {
                node.setAttribute("compression", "none");
            }

            text_node.appendChild(node);
        }

        for (int i = 0; i < zTXt_keyword.size(); i++) {
            node = new IIOMetadataNode("TextEntry");
            node.setAttribute("keyword", zTXt_keyword.get(i));
            node.setAttribute("value", zTXt_text.get(i));
            node.setAttribute("compression", "zip");

            text_node.appendChild(node);
        }

        return text_node;
    }

    /**
    * 获取标准transparency节点
    *
    * @return 获取标准transparency节点的结果
     */
    public IIOMetadataNode getStandardTransparencyNode() {
        IIOMetadataNode transparency_node =
            new IIOMetadataNode("Transparency");
 // scratch 节点
 // 空;
        IIOMetadataNode node = null;

        node = new IIOMetadataNode("Alpha");
        boolean hasAlpha =
            (IHDR_colorType == PNG.PNG_COLOR_RGB_ALPHA) ||
            (IHDR_colorType == PNG.PNG_COLOR_GRAY_ALPHA) ||
            (IHDR_colorType == PNG.PNG_COLOR_PALETTE &&
             tRNS_present &&
             (tRNS_colorType == IHDR_colorType) &&
             (tRNS_alpha != null));
        node.setAttribute("value", hasAlpha ? "nonpremultipled" : "none");
        transparency_node.appendChild(node);

        if (tRNS_present) {
            node = new IIOMetadataNode("TransparentColor");
            if (tRNS_colorType == PNG.PNG_COLOR_RGB) {
                node.setAttribute("value",
                        tRNS_red + " " +
                                          tRNS_green + " " +
                                          tRNS_blue);
            } else if (tRNS_colorType == PNG.PNG_COLOR_GRAY) {
                node.setAttribute("value", Integer.toString(tRNS_gray));
            }
            transparency_node.appendChild(node);
        }

        return transparency_node;
    }

 // Shorthand for 抛出 an iioinvalid树异常
    /** Fatal */
    private static void fatal(Node node, String reason)
        throws IIOInvalidTreeException {
        throw new IIOInvalidTreeException(reason, node);
    }

 // 获取 an integer-值 attribute
    private static String getStringAttribute(Node node, String name,
                                      String defaultValue, boolean required)
        throws IIOInvalidTreeException {
        Node attr = node.getAttributes().getNamedItem(name);
        if (attr == null) {
            if (!required) {
                return defaultValue;
            } else {
                fatal(node, "Required attribute " + name + " not present!");
            }
        }
        return attr.getNodeValue();
    }


 // 获取 an integer-值 attribute
    private static int getIntAttribute(Node node, String name,
                                int defaultValue, boolean required)
        throws IIOInvalidTreeException {
        String value = getStringAttribute(node, name, null, required);
        if (value == null) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

 // 获取 a float-值 attribute
    private static float getFloatAttribute(Node node, String name,
                                    float defaultValue, boolean required)
        throws IIOInvalidTreeException {
        String value = getStringAttribute(node, name, null, required);
        if (value == null) {
            return defaultValue;
        }
        return Float.parseFloat(value);
    }

 // 获取 a required integer-值 attribute
    /** 获取intattribute */
    private static int getIntAttribute(Node node, String name)
        throws IIOInvalidTreeException {
        return getIntAttribute(node, name, -1, true);
    }

 // 获取 a required float-值 attribute
    /** 获取floatattribute */
    private static float getFloatAttribute(Node node, String name)
        throws IIOInvalidTreeException {
        return getFloatAttribute(node, name, -1.0F, true);
    }

 // 获取 a 布尔值-值 attribute
    private static boolean getBooleanAttribute(Node node, String name,
                                        boolean defaultValue,
                                        boolean required)
        throws IIOInvalidTreeException {
        Node attr = node.getAttributes().getNamedItem(name);
        if (attr == null) {
            if (!required) {
                return defaultValue;
            } else {
                fatal(node, "Required attribute " + name + " not present!");
            }
        }
        String value = attr.getNodeValue();
 // Allow 降低 大小写 布尔值 for backward compatibility, #5082756
        if (value.equals("TRUE") || value.equals("true")) {
            return true;
        } else if (value.equals("FALSE") || value.equals("false")) {
            return false;
        } else {
            fatal(node, "Attribute " + name + " must be 'TRUE' or 'FALSE'!");
            return false;
        }
    }

 // 获取 a required 布尔值-值 attribute
    /** 获取布尔值attribute */
    private static boolean getBooleanAttribute(Node node, String name)
        throws IIOInvalidTreeException {
        return getBooleanAttribute(node, name, false, true);
    }

 // 获取 an enumerated attribute as an 索引 into a 字符串 array
    private static int getEnumeratedAttribute(Node node,
                                       String name, String[] legalNames,
                                       int defaultValue, boolean required)
        throws IIOInvalidTreeException {
        Node attr = node.getAttributes().getNamedItem(name);
        if (attr == null) {
            if (!required) {
                return defaultValue;
            } else {
                fatal(node, "Required attribute " + name + " not present!");
            }
        }
        String value = attr.getNodeValue();
        for (int i = 0; i < legalNames.length; i++) {
            if (value.equals(legalNames[i])) {
                return i;
            }
        }

        fatal(node, "Illegal value for attribute " + name + "!");
        return -1;
    }

 // 获取 a required enumerated attribute as an 索引 into a 字符串 array
    private static int getEnumeratedAttribute(Node node,
                                       String name, String[] legalNames)
        throws IIOInvalidTreeException {
        return getEnumeratedAttribute(node, name, legalNames, -1, true);
    }

 // 获取 a 字符串-值 attribute
    private static String getAttribute(Node node, String name,
                                String defaultValue, boolean required)
        throws IIOInvalidTreeException {
        Node attr = node.getAttributes().getNamedItem(name);
        if (attr == null) {
            if (!required) {
                return defaultValue;
            } else {
                fatal(node, "Required attribute " + name + " not present!");
            }
        }
        return attr.getNodeValue();
    }

 // 获取 a required 字符串-值 attribute
    /** 获取Attribute */
    private static String getAttribute(Node node, String name)
        throws IIOInvalidTreeException {
            return getAttribute(node, name, null, true);
    }

 // 获取 an 字符串-值 attribute
    private static String getStringAttribute(Node node, String name,
                                             String defaultValue,
                                             boolean required,
                                             String[] range)
            throws IIOInvalidTreeException {
        Node attr = node.getAttributes().getNamedItem(name);
        if (attr == null) {
            if (!required) {
                return defaultValue;
            } else {
                fatal(node, "Required attribute " + name + " not present!");
            }
        }
        String value = attr.getNodeValue();

        if (range != null) {
            if (value == null) {
                fatal(node,
                        "Null value for "+node.getNodeName()+
                                " attribute "+name+"!");
            }
            boolean validValue = false;
            int len = range.length;
            for (int i = 0; i < len; i++) {
                if (value.equals(range[i])) {
                    validValue = true;
                    break;
                }
            }
            if (!validValue) {
                fatal(node,
                        "Bad value for "+node.getNodeName()+
                                " attribute "+name+"!");
            }
        }

        return value;
    }

 // 获取 an integer-值 attribute
    private static int getIntAttribute(Node node, String name,
                                       int defaultValue, boolean required,
                                       boolean bounded, int min, int max)
            throws IIOInvalidTreeException {
        String value = getStringAttribute(node, name, null, required, null);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }

        int intValue = defaultValue;
        try {
            intValue = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            fatal(node,
                    "Bad value for "+node.getNodeName()+
                            " attribute "+name+"!");
        }
        if (bounded && (intValue < min || intValue > max)) {
            fatal(node,
                    "Bad value for "+node.getNodeName()+
                            " attribute "+name+"!");
        }
        return intValue;
    }

    /** 合并树 */
    public void mergeTree(String formatName, Node root)
        throws IIOInvalidTreeException {
        if (formatName.equals(nativeMetadataFormatName)) {
            if (root == null) {
                throw new IllegalArgumentException("root == null!");
            }
            mergeNativeTree(root);
        } else if (formatName.equals
                   (IIOMetadataFormatImpl.standardMetadataFormatName)) {
            if (root == null) {
                throw new IllegalArgumentException("root == null!");
            }
            mergeStandardTree(root);
        } else {
            throw new IllegalArgumentException("Not a recognized format!");
        }
    }

    /** 合并NAT树 */
    private void mergeNativeTree(Node root)
        throws IIOInvalidTreeException {
        Node node = root;
        if (!node.getNodeName().equals(nativeMetadataFormatName)) {
            fatal(node, "Root must be " + nativeMetadataFormatName);
        }

        node = node.getFirstChild();
        while (node != null) {
            String name = node.getNodeName();

            if (name.equals("IHDR")) {
                IHDR_width = getIntAttribute(node, "width");
                IHDR_height = getIntAttribute(node, "height");
                IHDR_bitDepth =
                        Integer.valueOf(IHDR_bitDepths[
                                getEnumeratedAttribute(node,
                                                    "bitDepth",
                                                    IHDR_bitDepths)]);
                IHDR_colorType = getEnumeratedAttribute(node, "colorType",
                                                        IHDR_colorTypeNames);
                IHDR_compressionMethod =
                    getEnumeratedAttribute(node, "compressionMethod",
                                           IHDR_compressionMethodNames);
                IHDR_filterMethod =
                    getEnumeratedAttribute(node,
                                           "filterMethod",
                                           IHDR_filterMethodNames);
                IHDR_interlaceMethod =
                    getEnumeratedAttribute(node, "interlaceMethod",
                                           IHDR_interlaceMethodNames);
                IHDR_present = true;
            } else if (name.equals("PLTE")) {
                byte[] red = new byte[256];
                byte[] green  = new byte[256];
                byte[] blue = new byte[256];
                int maxindex = -1;

                Node PLTE_entry = node.getFirstChild();
                if (PLTE_entry == null) {
                    fatal(node, "Palette has no entries!");
                }

                while (PLTE_entry != null) {
                    if (!PLTE_entry.getNodeName().equals("PLTEEntry")) {
                        fatal(node,
                              "Only a PLTEEntry may be a child of a PLTE!");
                    }

                    int index = getIntAttribute(PLTE_entry, "index");
                    if (index < 0 || index > 255) {
                        fatal(node,
                              "Bad value for PLTEEntry attribute index!");
                    }
                    if (index > maxindex) {
                        maxindex = index;
                    }
                    red[index] =
                        (byte)getIntAttribute(PLTE_entry, "red");
                    green[index] =
                        (byte)getIntAttribute(PLTE_entry, "green");
                    blue[index] =
                        (byte)getIntAttribute(PLTE_entry, "blue");

                    PLTE_entry = PLTE_entry.getNextSibling();
                }

                int numEntries = maxindex + 1;
                PLTE_red = new byte[numEntries];
                PLTE_green = new byte[numEntries];
                PLTE_blue = new byte[numEntries];
                System.arraycopy(red, 0, PLTE_red, 0, numEntries);
                System.arraycopy(green, 0, PLTE_green, 0, numEntries);
                System.arraycopy(blue, 0, PLTE_blue, 0, numEntries);
                PLTE_present = true;
            } else if (name.equals("bKGD")) {
 // Guard against 部分 overwrite
                // sent = false;
                bKGD_present = true;
                Node bKGD_node = node.getFirstChild();
                if (bKGD_node == null) {
                    fatal(node, "bKGD node has no children!");
                }
                String bKGD_name = bKGD_node.getNodeName();
                if (bKGD_name.equals("bKGD_Palette")) {
                    bKGD_index = getIntAttribute(bKGD_node, "index");
                    bKGD_colorType = PNG.PNG_COLOR_PALETTE;
                } else if (bKGD_name.equals("bKGD_Grayscale")) {
                    bKGD_gray = getIntAttribute(bKGD_node, "gray");
                    bKGD_colorType = PNG.PNG_COLOR_GRAY;
                } else if (bKGD_name.equals("bKGD_RGB")) {
                    bKGD_red = getIntAttribute(bKGD_node, "red");
                    bKGD_green = getIntAttribute(bKGD_node, "green");
                    bKGD_blue = getIntAttribute(bKGD_node, "blue");
                    bKGD_colorType = PNG.PNG_COLOR_RGB;
                } else {
                    fatal(node, "Bad child of a bKGD node!");
                }
                if (bKGD_node.getNextSibling() != null) {
                    fatal(node, "bKGD node has more than one child!");
                }

                bKGD_present = true;
            } else if (name.equals("cHRM")) {
                cHRM_whitePointX = getIntAttribute(node, "whitePointX");
                cHRM_whitePointY = getIntAttribute(node, "whitePointY");
                cHRM_redX = getIntAttribute(node, "redX");
                cHRM_redY = getIntAttribute(node, "redY");
                cHRM_greenX = getIntAttribute(node, "greenX");
                cHRM_greenY = getIntAttribute(node, "greenY");
                cHRM_blueX = getIntAttribute(node, "blueX");
                cHRM_blueY = getIntAttribute(node, "blueY");

                cHRM_present = true;
            } else if (name.equals("gAMA")) {
                gAMA_gamma = getIntAttribute(node, "value");
                gAMA_present = true;
            } else if (name.equals("hIST")) {
                char[] hist = new char[256];
                int maxindex = -1;

                Node hIST_entry = node.getFirstChild();
                if (hIST_entry == null) {
                    fatal(node, "hIST node has no children!");
                }

                while (hIST_entry != null) {
                    if (!hIST_entry.getNodeName().equals("hISTEntry")) {
                        fatal(node,
                              "Only a hISTEntry may be a child of a hIST!");
                    }

                    int index = getIntAttribute(hIST_entry, "index");
                    if (index < 0 || index > 255) {
                        fatal(node,
                              "Bad value for histEntry attribute index!");
                    }
                    if (index > maxindex) {
                        maxindex = index;
                    }
                    hist[index] =
                        (char)getIntAttribute(hIST_entry, "value");

                    hIST_entry = hIST_entry.getNextSibling();
                }

                int numEntries = maxindex + 1;
                hIST_histogram = new char[numEntries];
                System.arraycopy(hist, 0, hIST_histogram, 0, numEntries);

                hIST_present = true;
            } else if (name.equals("iCCP")) {
                iCCP_profileName = getAttribute(node, "profileName");
                iCCP_compressionMethod =
                        getEnumeratedAttribute(node, "compressionMethod",
                                iCCP_compressionMethodNames);
                Object compressedProfile =
                        ((IIOMetadataNode)node).getUserObject();
                if (compressedProfile == null) {
                    fatal(node, "No ICCP profile present in user object!");
                }
                if (!(compressedProfile instanceof byte[])) {
                    fatal(node, "User object not a byte array!");
                }

                iCCP_compressedProfile = ((byte[])compressedProfile).clone();

                iCCP_present = true;
            } else if (name.equals("cICP")) {
                cICP_colourPrimaries = getIntAttribute(node, "colourPrimaries");
                cICP_transferFunction = getIntAttribute(node, "transferFunction");
                cICP_matrixCoefficients = getIntAttribute(node, "matrixCoefficients");
                cICP_videoFullRangeFlag = getBooleanAttribute(node, "videoFullRangeFlag");

                cICP_present = true;
            } else if (name.equals("eXIf")) {
                Object exifData = ((IIOMetadataNode)node).getUserObject();
                if (exifData == null) {
                    fatal(node, "No Exif data in user object!");
                }
                if (!(exifData instanceof byte[])) {
                    fatal(node, "User object not a byte array!");
                }

                eXIf_data = ((byte[])exifData).clone();

                eXIf_present = true;
            } else if (name.equals("iTXt")) {
                Node iTXt_node = node.getFirstChild();
                while (iTXt_node != null) {
                    if (!iTXt_node.getNodeName().equals("iTXtEntry")) {
                        fatal(node,
                              "Only an iTXtEntry may be a child of an iTXt!");
                    }

                    String keyword = getAttribute(iTXt_node, "keyword");
                    if (isValidKeyword(keyword)) {
                        iTXt_keyword.add(keyword);

                        boolean compressionFlag =
                            getBooleanAttribute(iTXt_node, "compressionFlag");
                        iTXt_compressionFlag.add(Boolean.valueOf(compressionFlag));

                        String compressionMethod =
                            getAttribute(iTXt_node, "compressionMethod");
                        iTXt_compressionMethod.add(Integer.valueOf(compressionMethod));

                        String languageTag =
                            getAttribute(iTXt_node, "languageTag");
                        iTXt_languageTag.add(languageTag);

                        String translatedKeyword =
                            getAttribute(iTXt_node, "translatedKeyword");
                        iTXt_translatedKeyword.add(translatedKeyword);

                        String text = getAttribute(iTXt_node, "text");
                        iTXt_text.add(text);

 // 检查 if the 文本 chunk contains 镜像 创建 时间
                        if (keyword.equals(PNGMetadata.tEXt_creationTimeKey)) {
 // 更新 标准/文档/镜像创建时间
                            int index = iTXt_text.size()-1;
                            decodeImageCreationTimeFromTextChunk(
                                    iTXt_text.listIterator(index));
                        }
                    }
 // silently 跳过 invalid 文本 entry

                    iTXt_node = iTXt_node.getNextSibling();
                }
            } else if (name.equals("pHYs")) {
                pHYs_pixelsPerUnitXAxis =
                    getIntAttribute(node, "pixelsPerUnitXAxis");
                pHYs_pixelsPerUnitYAxis =
                    getIntAttribute(node, "pixelsPerUnitYAxis");
                pHYs_unitSpecifier =
                    getEnumeratedAttribute(node, "unitSpecifier",
                                           unitSpecifierNames);

                pHYs_present = true;
            } else if (name.equals("sBIT")) {
 // Guard against 部分 overwrite
                // sent = false;
                sBIT_present = true;
                Node sBIT_node = node.getFirstChild();
                if (sBIT_node == null) {
                    fatal(node, "sBIT node has no children!");
                }
                String sBIT_name = sBIT_node.getNodeName();
                if (sBIT_name.equals("sBIT_Grayscale")) {
                    sBIT_grayBits = getIntAttribute(sBIT_node, "gray");
                    sBIT_colorType = PNG.PNG_COLOR_GRAY;
                } else if (sBIT_name.equals("sBIT_GrayAlpha")) {
                    sBIT_grayBits = getIntAttribute(sBIT_node, "gray");
                    sBIT_alphaBits = getIntAttribute(sBIT_node, "alpha");
                    sBIT_colorType = PNG.PNG_COLOR_GRAY_ALPHA;
                } else if (sBIT_name.equals("sBIT_RGB")) {
                    sBIT_redBits = getIntAttribute(sBIT_node, "red");
                    sBIT_greenBits = getIntAttribute(sBIT_node, "green");
                    sBIT_blueBits = getIntAttribute(sBIT_node, "blue");
                    sBIT_colorType = PNG.PNG_COLOR_RGB;
                } else if (sBIT_name.equals("sBIT_RGBAlpha")) {
                    sBIT_redBits = getIntAttribute(sBIT_node, "red");
                    sBIT_greenBits = getIntAttribute(sBIT_node, "green");
                    sBIT_blueBits = getIntAttribute(sBIT_node, "blue");
                    sBIT_alphaBits = getIntAttribute(sBIT_node, "alpha");
                    sBIT_colorType = PNG.PNG_COLOR_RGB_ALPHA;
                } else if (sBIT_name.equals("sBIT_Palette")) {
                    sBIT_redBits = getIntAttribute(sBIT_node, "red");
                    sBIT_greenBits = getIntAttribute(sBIT_node, "green");
                    sBIT_blueBits = getIntAttribute(sBIT_node, "blue");
                    sBIT_colorType = PNG.PNG_COLOR_PALETTE;
                } else {
                    fatal(node, "Bad child of an sBIT node!");
                }
                if (sBIT_node.getNextSibling() != null) {
                    fatal(node, "sBIT node has more than one child!");
                }

                sBIT_present = true;
            } else if (name.equals("sPLT")) {
                sPLT_paletteName = getAttribute(node, "name");
                sPLT_sampleDepth = getIntAttribute(node, "sampleDepth");

                int[] red = new int[256];
                int[] green  = new int[256];
                int[] blue = new int[256];
                int[] alpha = new int[256];
                int[] frequency = new int[256];
                int maxindex = -1;

                Node sPLT_entry = node.getFirstChild();
                if (sPLT_entry == null) {
                    fatal(node, "sPLT node has no children!");
                }

                while (sPLT_entry != null) {
                    if (!sPLT_entry.getNodeName().equals("sPLTEntry")) {
                        fatal(node,
                              "Only an sPLTEntry may be a child of an sPLT!");
                    }

                    int index = getIntAttribute(sPLT_entry, "index");
                    if (index < 0 || index > 255) {
                        fatal(node,
                              "Bad value for PLTEEntry attribute index!");
                    }
                    if (index > maxindex) {
                        maxindex = index;
                    }
                    red[index] = getIntAttribute(sPLT_entry, "red");
                    green[index] = getIntAttribute(sPLT_entry, "green");
                    blue[index] = getIntAttribute(sPLT_entry, "blue");
                    alpha[index] = getIntAttribute(sPLT_entry, "alpha");
                    frequency[index] =
                        getIntAttribute(sPLT_entry, "frequency");

                    sPLT_entry = sPLT_entry.getNextSibling();
                }

                int numEntries = maxindex + 1;
                sPLT_red = new int[numEntries];
                sPLT_green = new int[numEntries];
                sPLT_blue = new int[numEntries];
                sPLT_alpha = new int[numEntries];
                sPLT_frequency = new int[numEntries];
                System.arraycopy(red, 0, sPLT_red, 0, numEntries);
                System.arraycopy(green, 0, sPLT_green, 0, numEntries);
                System.arraycopy(blue, 0, sPLT_blue, 0, numEntries);
                System.arraycopy(alpha, 0, sPLT_alpha, 0, numEntries);
                System.arraycopy(frequency, 0,
                                 sPLT_frequency, 0, numEntries);

                sPLT_present = true;
            } else if (name.equals("sRGB")) {
                sRGB_renderingIntent =
                    getEnumeratedAttribute(node, "renderingIntent",
                                           renderingIntentNames);

                sRGB_present = true;
            } else if (name.equals("tEXt")) {
                Node tEXt_node = node.getFirstChild();
                while (tEXt_node != null) {
                    if (!tEXt_node.getNodeName().equals("tEXtEntry")) {
                        fatal(node,
                              "Only an tEXtEntry may be a child of an tEXt!");
                    }

                    String keyword = getAttribute(tEXt_node, "keyword");
                    tEXt_keyword.add(keyword);

                    String text = getAttribute(tEXt_node, "value");
                    tEXt_text.add(text);

 // 检查 if the 文本 chunk contains 镜像 创建 时间
                    if (keyword.equals(PNGMetadata.tEXt_creationTimeKey)) {
 // 更新 标准/文档/镜像创建时间
                        int index = tEXt_text.size()-1;
                        decodeImageCreationTimeFromTextChunk(
                                tEXt_text.listIterator(index));
                    }
                    tEXt_node = tEXt_node.getNextSibling();
                }
            } else if (name.equals("tIME")) {
                tIME_year = getIntAttribute(node, "year");
                tIME_month = getIntAttribute(node, "month");
                tIME_day = getIntAttribute(node, "day");
                tIME_hour = getIntAttribute(node, "hour");
                tIME_minute = getIntAttribute(node, "minute");
                tIME_second = getIntAttribute(node, "second");

                tIME_present = true;
            } else if (name.equals("tRNS")) {
 // Guard against 部分 overwrite
                // sent = false;
                tRNS_present = true;
                Node tRNS_node = node.getFirstChild();
                if (tRNS_node == null) {
                    fatal(node, "tRNS node has no children!");
                }
                String tRNS_name = tRNS_node.getNodeName();
                if (tRNS_name.equals("tRNS_Palette")) {
                    byte[] alpha = new byte[256];
                    int maxindex = -1;

                    Node tRNS_paletteEntry = tRNS_node.getFirstChild();
                    if (tRNS_paletteEntry == null) {
                        fatal(node, "tRNS_Palette node has no children!");
                    }
                    while (tRNS_paletteEntry != null) {
                        if (!tRNS_paletteEntry.getNodeName().equals(
                                                        "tRNS_PaletteEntry")) {
                            fatal(node,
                 "Only a tRNS_PaletteEntry may be a child of a tRNS_Palette!");
                        }
                        int index =
                            getIntAttribute(tRNS_paletteEntry, "index");
                        if (index < 0 || index > 255) {
                            fatal(node,
                           "Bad value for tRNS_PaletteEntry attribute index!");
                        }
                        if (index > maxindex) {
                            maxindex = index;
                        }
                        alpha[index] =
                            (byte)getIntAttribute(tRNS_paletteEntry,
                                                  "alpha");

                        tRNS_paletteEntry =
                            tRNS_paletteEntry.getNextSibling();
                    }

                    int numEntries = maxindex + 1;
                    tRNS_alpha = new byte[numEntries];
                    tRNS_colorType = PNG.PNG_COLOR_PALETTE;
                    System.arraycopy(alpha, 0, tRNS_alpha, 0, numEntries);
                } else if (tRNS_name.equals("tRNS_Grayscale")) {
                    tRNS_gray = getIntAttribute(tRNS_node, "gray");
                    tRNS_colorType = PNG.PNG_COLOR_GRAY;
                } else if (tRNS_name.equals("tRNS_RGB")) {
                    tRNS_red = getIntAttribute(tRNS_node, "red");
                    tRNS_green = getIntAttribute(tRNS_node, "green");
                    tRNS_blue = getIntAttribute(tRNS_node, "blue");
                    tRNS_colorType = PNG.PNG_COLOR_RGB;
                } else {
                    fatal(node, "Bad child of a tRNS node!");
                }
                if (tRNS_node.getNextSibling() != null) {
                    fatal(node, "tRNS node has more than one child!");
                }

                tRNS_present = true;
            } else if (name.equals("zTXt")) {
                Node zTXt_node = node.getFirstChild();
                while (zTXt_node != null) {
                    if (!zTXt_node.getNodeName().equals("zTXtEntry")) {
                        fatal(node,
                              "Only an zTXtEntry may be a child of an zTXt!");
                    }

                    String keyword = getAttribute(zTXt_node, "keyword");
                    zTXt_keyword.add(keyword);

                    int compressionMethod =
                        getEnumeratedAttribute(zTXt_node, "compressionMethod",
                                               zTXt_compressionMethodNames);
                    zTXt_compressionMethod.add(compressionMethod);

                    String text = getAttribute(zTXt_node, "text");
                    zTXt_text.add(text);

 // 检查 if the 文本 chunk contains 镜像 创建 时间
                    if (keyword.equals(PNGMetadata.tEXt_creationTimeKey)) {
 // 更新 标准/文档/镜像创建时间
                        int index = zTXt_text.size()-1;
                        decodeImageCreationTimeFromTextChunk(
                                zTXt_text.listIterator(index));
                    }
                    zTXt_node = zTXt_node.getNextSibling();
                }
            } else if (name.equals("acTL")) {
                acTL_num_frames = getIntAttribute(node, "num_frames");
                acTL_num_plays = getIntAttribute(node, "num_plays");

                acTL_present = true;
            } else if (name.equals("fcTL")) {
                fcTL_sequence_number = getIntAttribute(node,
                        "sequence_number",
                        -1, true,
                        true, 0, 2147483647);

                fcTL_width = getIntAttribute(node,
                        "width",
                        -1, true,
                        true, 1, 2147483647);

                fcTL_height = getIntAttribute(node,
                        "height",
                        -1, true,
                        true, 1, 2147483647);

                fcTL_x_offset = getIntAttribute(node,
                        "x_offset",
                        -1, true,
                        true, 0, 2147483647);

                fcTL_y_offset = getIntAttribute(node,
                        "y_offset",
                        -1, true,
                        true, 0, 2147483647);

                fcTL_delay_num = getIntAttribute(node,
                        "delay_num",
                        -1, true,
                        true, 0, 65535);

                fcTL_delay_den = getIntAttribute(node,
                        "delay_den",
                        -1, true,
                        true, 0, 65535);

                fcTL_dispose_op = getEnumeratedAttribute(node,
                        "dispose_op",
                        fcTL_disposalOperatorNames);

                fcTL_blend_op = getEnumeratedAttribute(node,
                        "blend_op",
                        fcTL_blendOperatorNames);


            } else if (name.equals("fdAT")) {
                fcTL_sequence_number = getIntAttribute(node,
                        "sequence_number",
                        -1, true,
                        true, 0, 2147483647);
            } else if (name.equals("UnknownChunks")) {
                Node unknown_node = node.getFirstChild();
                while (unknown_node != null) {
                    if (!unknown_node.getNodeName().equals("UnknownChunk")) {
                        fatal(node,
                   "Only an UnknownChunk may be a child of an UnknownChunks!");
                    }
                    String chunkType = getAttribute(unknown_node, "type");
                    Object chunkData =
                        ((IIOMetadataNode)unknown_node).getUserObject();

                    if (chunkType.length() != 4) {
                        fatal(unknown_node,
                              "Chunk type must be 4 characters!");
                    }
                    if (chunkData == null) {
                        fatal(unknown_node,
                              "No chunk data present in user object!");
                    }
                    if (!(chunkData instanceof byte[])) {
                        fatal(unknown_node,
                              "User object not a byte array!");
                    }
                    unknownChunkType.add(chunkType);
                    unknownChunkData.add(((byte[])chunkData).clone());

                    unknown_node = unknown_node.getNextSibling();
                }
            } else {
                fatal(node, "Unknown child of root node!");
            }

            node = node.getNextSibling();
        }
    }

    /*
    * Accrding 转为 PNG spec, keywords are restricted 转为 1 转为 79 bytes
    * 入 长度. Keywords shall contain only printable Latin-1 characters
    * 和 spaces; 转为 reduce the chances for human misreading 的 a keyword,
    * 铅 spaces, trailing spaces, 和 consecutive spaces are not
    * permitted 入 keywords.
    *
    * 参见: http://www.w3.org/TR/PNG/#11keywords
     */
    /**
    * 是否validkeyword。
    * @param s s
    * @return 是否validkeyword的结果
     */
    private boolean isValidKeyword(String s) {
        int len = s.length();
        if (len < 1 || len >= 80) {
            return false;
        }
        if (s.startsWith(" ") || s.endsWith(" ") || s.contains("  ")) {
            return false;
        }
        return isISOLatin(s, false);
    }

    /*
      * According 转为 PNG spec, keyword shall contain only printable
      * Latin-1 [ISO-8859-1] characters 和 spaces; that 是否, only
      * character 编码 32-126 和 161-255 decimal are allowed.
      * For Latin-1 值 字段 the 0x10 (linefeed) control
      * character 是否 aloowed too.
     *
      * 参见: http://www.w3.org/TR/PNG/#11keywords
     */
    /**
    * 是否isolatin。
    * @param s s
    * @param isLineFeedAllowed 是否线feedallowed
    * @return 是否isolatin的结果
     */
    private boolean isISOLatin(String s, boolean isLineFeedAllowed) {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 255 || (c > 126 && c < 161)) {
 // not printable. 检查 whether this 是否 an allowed
                // control char
                if (!isLineFeedAllowed || c != 0x10) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 合并标准树 */
    private void mergeStandardTree(Node root)
        throws IIOInvalidTreeException {
        Node node = root;
        if (!node.getNodeName()
            .equals(IIOMetadataFormatImpl.standardMetadataFormatName)) {
            fatal(node, "Root must be " +
                  IIOMetadataFormatImpl.standardMetadataFormatName);
        }

        node = node.getFirstChild();
        while (node != null) {
            String name = node.getNodeName();

            if (name.equals("Chroma")) {
                Node child = node.getFirstChild();
                while (child != null) {
                    String childName = child.getNodeName();
                    if (childName.equals("Gamma")) {
                        float gamma = getFloatAttribute(child, "value");
                        gAMA_present = true;
                        gAMA_gamma = (int)(gamma*100000 + 0.5);
                    } else if (childName.equals("Palette")) {
                        byte[] red = new byte[256];
                        byte[] green = new byte[256];
                        byte[] blue = new byte[256];
                        int maxindex = -1;

                        Node entry = child.getFirstChild();
                        while (entry != null) {
                            int index = getIntAttribute(entry, "index");
                            if (index >= 0 && index <= 255) {
                                red[index] =
                                    (byte)getIntAttribute(entry, "red");
                                green[index] =
                                    (byte)getIntAttribute(entry, "green");
                                blue[index] =
                                    (byte)getIntAttribute(entry, "blue");
                                if (index > maxindex) {
                                    maxindex = index;
                                }
                            }
                            entry = entry.getNextSibling();
                        }

                        int numEntries = maxindex + 1;
                        PLTE_red = new byte[numEntries];
                        PLTE_green = new byte[numEntries];
                        PLTE_blue = new byte[numEntries];
                        System.arraycopy(red, 0, PLTE_red, 0, numEntries);
                        System.arraycopy(green, 0, PLTE_green, 0, numEntries);
                        System.arraycopy(blue, 0, PLTE_blue, 0, numEntries);
                        PLTE_present = true;
                    } else if (childName.equals("BackgroundIndex")) {
                        bKGD_present = true;
                        bKGD_colorType = PNG.PNG_COLOR_PALETTE;
                        bKGD_index = getIntAttribute(child, "value");
                    } else if (childName.equals("BackgroundColor")) {
                        int red = getIntAttribute(child, "red");
                        int green = getIntAttribute(child, "green");
                        int blue = getIntAttribute(child, "blue");
                        if (red == green && red == blue) {
                            bKGD_colorType = PNG.PNG_COLOR_GRAY;
                            bKGD_gray = red;
                        } else {
                            bKGD_colorType = PNG.PNG_COLOR_RGB;
                            bKGD_red = red;
                            bKGD_green = green;
                            bKGD_blue = blue;
                        }
                        bKGD_present = true;
                    }
//                  } else if (childName.equals("ColorSpaceType")) {
//                  } else if (childName.equals("NumChannels")) {

                    child = child.getNextSibling();
                }
            } else if (name.equals("Compression")) {
                Node child = node.getFirstChild();
                while (child != null) {
                    String childName = child.getNodeName();
                    if (childName.equals("NumProgressiveScans")) {
                        // Use Adam7 if NumProgressiveScans > 1
                        int scans = getIntAttribute(child, "value");
                        IHDR_interlaceMethod = (scans > 1) ? 1 : 0;
//                  } else if (childName.equals("CompressionTypeName")) {
//                  } else if (childName.equals("Lossless")) {
//                  } else if (childName.equals("BitRate")) {
                    }
                    child = child.getNextSibling();
                }
            } else if (name.equals("Data")) {
                Node child = node.getFirstChild();
                while (child != null) {
                    String childName = child.getNodeName();
                    if (childName.equals("BitsPerSample")) {
                        String s = getAttribute(child, "value");
                        StringTokenizer t = new StringTokenizer(s);
                        int maxBits = -1;
                        while (t.hasMoreTokens()) {
                            int bits = Integer.parseInt(t.nextToken());
                            if (bits > maxBits) {
                                maxBits = bits;
                            }
                        }
                        if (maxBits < 1) {
                            maxBits = 1;
                        }
                        if (maxBits == 3) {
                            maxBits = 4;
                        }
                        if (maxBits > 4 || maxBits < 8) {
                            maxBits = 8;
                        }
                        if (maxBits > 8) {
                            maxBits = 16;
                        }
                        IHDR_bitDepth = maxBits;
                    } else if (childName.equals("SignificantBitsPerSample")) {
                        String s = getAttribute(child, "value");
                        StringTokenizer t = new StringTokenizer(s);
                        int numTokens = t.countTokens();
                        if (numTokens == 1) {
                            sBIT_colorType = PNG.PNG_COLOR_GRAY;
                            sBIT_grayBits = Integer.parseInt(t.nextToken());
                        } else if (numTokens == 2) {
                            sBIT_colorType =
                              PNG.PNG_COLOR_GRAY_ALPHA;
                            sBIT_grayBits = Integer.parseInt(t.nextToken());
                            sBIT_alphaBits = Integer.parseInt(t.nextToken());
                        } else if (numTokens == 3) {
                            sBIT_colorType = PNG.PNG_COLOR_RGB;
                            sBIT_redBits = Integer.parseInt(t.nextToken());
                            sBIT_greenBits = Integer.parseInt(t.nextToken());
                            sBIT_blueBits = Integer.parseInt(t.nextToken());
                        } else if (numTokens == 4) {
                            sBIT_colorType =
                              PNG.PNG_COLOR_RGB_ALPHA;
                            sBIT_redBits = Integer.parseInt(t.nextToken());
                            sBIT_greenBits = Integer.parseInt(t.nextToken());
                            sBIT_blueBits = Integer.parseInt(t.nextToken());
                            sBIT_alphaBits = Integer.parseInt(t.nextToken());
                        }
                        if (numTokens >= 1 && numTokens <= 4) {
                            sBIT_present = true;
                        }
//                      } else if (childName.equals("PlanarConfiguration")) {
//                      } else if (childName.equals("SampleFormat")) {
//                      } else if (childName.equals("SampleMSB")) {
                    }
                    child = child.getNextSibling();
                }
            } else if (name.equals("Dimension")) {
                boolean gotWidth = false;
                boolean gotHeight = false;
                boolean gotAspectRatio = false;

                float width = -1.0F;
                float height = -1.0F;
                float aspectRatio = -1.0F;

                Node child = node.getFirstChild();
                while (child != null) {
                    String childName = child.getNodeName();
                    if (childName.equals("PixelAspectRatio")) {
                        aspectRatio = getFloatAttribute(child, "value");
                        gotAspectRatio = true;
                    } else if (childName.equals("HorizontalPixelSize")) {
                        width = getFloatAttribute(child, "value");
                        gotWidth = true;
                    } else if (childName.equals("VerticalPixelSize")) {
                        height = getFloatAttribute(child, "value");
                        gotHeight = true;
//                  } else if (childName.equals("ImageOrientation")) {
//                  } else if
//                      (childName.equals("HorizontalPhysicalPixelSpacing")) {
//                  } else if
//                      (childName.equals("VerticalPhysicalPixelSpacing")) {
//                  } else if (childName.equals("HorizontalPosition")) {
//                  } else if (childName.equals("VerticalPosition")) {
                    } if (childName.equals("HorizontalPixelOffset")) {
                        fcTL_x_offset = getIntAttribute(child,
                                "value",
                                -1, true,
                                true, 0, 2147483647);
                    } else if (childName.equals("VerticalPixelOffset")) {
                        fcTL_y_offset = getIntAttribute(child,
                                "value",
                                -1, true,
                                true, 0, 2147483647);
                    }
                    child = child.getNextSibling();
                }

                if (gotWidth && gotHeight) {
                    pHYs_present = true;
                    pHYs_unitSpecifier = 1;
                    pHYs_pixelsPerUnitXAxis = (int)(width*1000 + 0.5F);
                    pHYs_pixelsPerUnitYAxis = (int)(height*1000 + 0.5F);
                } else if (gotAspectRatio) {
                    pHYs_present = true;
                    pHYs_unitSpecifier = 0;

 // 查找 a ReasonMLML 理性的 approximation
                    int denom = 1;
                    for (; denom < 100; denom++) {
                        int num = (int)(aspectRatio*denom);
                        if (Math.abs(num/denom - aspectRatio) < 0.001) {
                            break;
                        }
                    }
                    pHYs_pixelsPerUnitXAxis = (int)(aspectRatio*denom);
                    pHYs_pixelsPerUnitYAxis = denom;
                }
            } else if (name.equals("Document")) {
                Node child = node.getFirstChild();
                while (child != null) {
                    String childName = child.getNodeName();
                    if (childName.equals("ImageModificationTime")) {
                        tIME_present = true;
                        tIME_year = getIntAttribute(child, "year");
                        tIME_month = getIntAttribute(child, "month");
                        tIME_day = getIntAttribute(child, "day");
                        tIME_hour =
                            getIntAttribute(child, "hour", 0, false);
                        tIME_minute =
                            getIntAttribute(child, "minute", 0, false);
                        tIME_second =
                            getIntAttribute(child, "second", 0, false);
//                  } else if (childName.equals("SubimageInterpretation")) {
                    } else if (childName.equals("ImageCreationTime")) {
 // Extract the 创建 时间 值
                        int year  = getIntAttribute(child, "year");
                        int month = getIntAttribute(child, "month");
                        int day   = getIntAttribute(child, "day");
                        int hour  = getIntAttribute(child, "hour", 0, false);
                        int mins  = getIntAttribute(child, "minute", 0, false);
                        int sec   = getIntAttribute(child, "second", 0, false);

                        /*
    * 更新 标准/文档/镜像创建时间 和 encode
    * the same 入 the 最后一个 decoded 文本 chunk with 创建
    * 时间
                         */
                        initImageCreationTime(year, month, day, hour, mins, sec);
                        encodeImageCreationTimeToTextChunk();
                    }
                    child = child.getNextSibling();
                }
            } else if (name.equals("Text")) {
                Node child = node.getFirstChild();
                while (child != null) {
                    String childName = child.getNodeName();
                    if (childName.equals("TextEntry")) {
                        String keyword =
                            getAttribute(child, "keyword", "", false);
                        String value = getAttribute(child, "value");
                        String language =
                            getAttribute(child, "language", "", false);
                        String compression =
                            getAttribute(child, "compression", "none", false);

                        if (!isValidKeyword(keyword)) {
 // Just ignore this 节点, PNG requires keywords
                        } else if (isISOLatin(value, true)) {
                            if (compression.equals("zip")) {
 // Use a ztxt 节点
                                zTXt_keyword.add(keyword);
                                zTXt_text.add(value);
                                zTXt_compressionMethod.add(Integer.valueOf(0));
                            } else {
 // Use a 文本 节点
                                tEXt_keyword.add(keyword);
                                tEXt_text.add(value);
                            }
                        } else {
 // Use an itxt 节点
                            iTXt_keyword.add(keyword);
                            iTXt_compressionFlag.add(Boolean.valueOf(compression.equals("zip")));
                            iTXt_compressionMethod.add(Integer.valueOf(0));
                            iTXt_languageTag.add(language);
                            // fake it
                            // atedKeyword.add(keyword);
                            // fake it
                            iTXt_translatedKeyword.add(keyword);
                            iTXt_text.add(value);
                        }
                    }
                    child = child.getNextSibling();
                }
//          } else if (name.equals("Transparency")) {
//              Node child = node.getFirstChild();
//              while (child != null) {
//                  String childName = child.getNodeName();
//                  if (childName.equals("Alpha")) {
//                  } else if (childName.equals("TransparentIndex")) {
//                  } else if (childName.equals("TransparentColor")) {
//                  } else if (childName.equals("TileTransparencies")) {
//                  } else if (childName.equals("TileOpacities")) {
//                  }
//                  child = child.getNextSibling();
//              }
//          } else {
//              // fatal(node, "Unknown child of root node!");
            }

            node = node.getNextSibling();
        }
    }

    void initImageCreationTime(OffsetDateTime offsetDateTime) {
 // 检查 for 收入 参数
        if (offsetDateTime != null) {
 // 设置 值 that make up 标准/文档/镜像创建时间
            creation_time_present = true;
            creation_time_year    = offsetDateTime.getYear();
            creation_time_month   = offsetDateTime.getMonthValue();
            creation_time_day     = offsetDateTime.getDayOfMonth();
            creation_time_hour    = offsetDateTime.getHour();
            creation_time_minute  = offsetDateTime.getMinute();
            creation_time_second  = offsetDateTime.getSecond();
            creation_time_offset  = offsetDateTime.getOffset();
        }
    }

    void initImageCreationTime(int year, int month, int day,
            int hour, int min,int second) {
        /*
          * 虽然本地日期时间足以存储标准/文档/
          * 镜像创建时间，但我们还需要时区偏移量来将该时间按 RFC1123 格式
          * 编码到文本块中。
         */
        LocalDateTime locDT = LocalDateTime.of(year, month, day, hour, min, second);
        ZoneOffset offset = ZoneId.systemDefault()
                                  .getRules()
                                  .getOffset(locDT);
        OffsetDateTime offDateTime = OffsetDateTime.of(locDT,offset);
        initImageCreationTime(offDateTime);
    }

    void decodeImageCreationTimeFromTextChunk(ListIterator<String> iterChunk) {
 // 检查 for 收入 参数
        if (iterChunk != null && iterChunk.hasNext()) {
            /*
              * 保存 the 迭代器 转为 mark the 最后一个 decoded 文本 chunk with
              * 创建 时间. The 内容 的 this chunk will be 更新 When.js
              * 用户 provides 创建 时间 by 合并 a 标准 树 with
              * 标准/文档/镜像创建时间.
             */
            setCreationTimeChunk(iterChunk);

 // 解析 encoded 时间 和 设置 标准/文档/镜像创建时间.
            String encodedTime = getEncodedTime();
            initImageCreationTime(parseEncodedTime(encodedTime));
        }
    }

    void encodeImageCreationTimeToTextChunk() {
 // 检查 if 标准/文档/镜像创建时间 exists.
        if (creation_time_present) {
 // 检查 if a 文本 chunk with 创建 时间 exists.
            if (!tEXt_creation_time_present) {
 // No 文本 chunk exists with 镜像 创建 时间. 添加 an entry.
                this.tEXt_keyword.add(tEXt_creationTimeKey);
                this.tEXt_text.add("Creation Time Place Holder");

 // 更新 the 迭代器
                int index = tEXt_text.size() - 1;
                setCreationTimeChunk(tEXt_text.listIterator(index));
            }

 // Encode 镜像 创建 时间 with RFC1123 formatter
            OffsetDateTime offDateTime = OffsetDateTime.of(creation_time_year,
                    creation_time_month, creation_time_day,
                    creation_time_hour, creation_time_minute,
                    creation_time_second, 0, creation_time_offset);
            DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
            String encodedTime = offDateTime.format(formatter);
            setEncodedTime(encodedTime);
        }
    }

    /**
    * 设置创建时间chunk
    *
    * @param iter iter
     */
    private void setCreationTimeChunk(ListIterator<String> iter) {
        // Check for iterator's valid state
        if (iter != null && iter.hasNext()) {
            tEXt_creation_time_iter = iter;
            tEXt_creation_time_present = true;
        }
    }

    /**
    * 设置encoded时间
    *
    * @param encodedTime encoded时间
     */
    private void setEncodedTime(String encodedTime) {
        if (tEXt_creation_time_iter != null
                && tEXt_creation_time_iter.hasNext()
                && encodedTime != null) {
 // 设置 the 值 at the 迭代器 和 reset its 状态
            tEXt_creation_time_iter.next();
            tEXt_creation_time_iter.set(encodedTime);
            tEXt_creation_time_iter.previous();
        }
    }

    /**
    * 获取encoded时间
    *
    * @return 获取encoded时间的结果
     */
    private String getEncodedTime() {
        String encodedTime = null;
        if (tEXt_creation_time_iter != null
                && tEXt_creation_time_iter.hasNext()) {
 // 获取 the 值 at 迭代器 和 reset its 状态
            encodedTime = tEXt_creation_time_iter.next();
            tEXt_creation_time_iter.previous();
        }
        return encodedTime;
    }

    /**
    * 解析encoded时间
    *
    * @param encodedTime encoded时间
    * @return 解析encoded时间的结果
     */
    private OffsetDateTime parseEncodedTime(String encodedTime) {
        OffsetDateTime retVal = null;
        boolean timeDecoded = false;

        /*
          * PNG specification recommends that 镜像 编码器 use RFC1123 格式化
          * 转为 represent 时间 入 字符串 but doesn't mandate. 编码器 could
          * use 任意 convenient 格式化. Hence, we extract 时间 provided the
          * encoded 时间 complies with either RFC1123 或 ISO 标准.
         */
        try {
 // 检查 if the encoded 时间 complies with RFC1123
            retVal = OffsetDateTime.parse(encodedTime,
                                          DateTimeFormatter.RFC_1123_DATE_TIME);
            timeDecoded = true;
        } catch (DateTimeParseException exception) {
 // No Op. Encoded 时间 did not comply with RFC1123 标准.
        }

        if (!timeDecoded) {
            try {
 // 检查 if the encoded 时间 complies with ISO 标准.
                DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                TemporalAccessor dt = formatter.parseBest(encodedTime,
                        OffsetDateTime::from, LocalDateTime::from);

                if (dt instanceof OffsetDateTime) {
 // 编码的时间包含日期、时间和时区偏移量
                    retVal = (OffsetDateTime) dt;
                } else if (dt instanceof LocalDateTime locDT) {
                    /*
                      * 编码的时间仅包含日期和时间。由于时区
                      * 偏移量信息不可用，我们将其设置为默认
                     */
                    retVal = OffsetDateTime.of(locDT, ZoneOffset.UTC);
                }
            }  catch (DateTimeParseException exception) {
 // No Op. Encoded 时间 did not comply with ISO 标准.
            }
        }
        return retVal;
    }

    boolean hasTransparentColor() {
        return tRNS_present &&
               (tRNS_colorType == PNG.PNG_COLOR_RGB ||
               tRNS_colorType == PNG.PNG_COLOR_GRAY);
    }

 // Reset 全部 instance 变量 转为 their initial 状态
    /** 重置 */
    public void reset() {
        IHDR_present = false;
        PLTE_present = false;
        bKGD_present = false;
        cHRM_present = false;
        gAMA_present = false;
        hIST_present = false;
        iCCP_present = false;
        cICP_present = false;
        eXIf_present = false;
        iTXt_keyword = new ArrayList<String>();
        iTXt_compressionFlag = new ArrayList<Boolean>();
        iTXt_compressionMethod = new ArrayList<Integer>();
        iTXt_languageTag = new ArrayList<String>();
        iTXt_translatedKeyword = new ArrayList<String>();
        iTXt_text = new ArrayList<String>();
        pHYs_present = false;
        sBIT_present = false;
        sPLT_present = false;
        sRGB_present = false;
        tEXt_keyword = new ArrayList<String>();
        tEXt_text = new ArrayList<String>();
 // 时间 chunk with 镜像 修改 时间
        tIME_present = false;
 // 文本 chunk with 镜像 创建 时间
        tEXt_creation_time_present = false;
        tEXt_creation_time_iter = null;
        creation_time_present = false;
        tRNS_present = false;
        zTXt_keyword = new ArrayList<String>();
        zTXt_compressionMethod = new ArrayList<Integer>();
        zTXt_text = new ArrayList<String>();
        acTL_present = false;
        fcTL_present = false;
        fdAT_present = false;
        unknownChunkType = new ArrayList<String>();
        unknownChunkData = new ArrayList<byte[]>();
    }
}
