package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import com.drew.lang.ByteArrayReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifReader;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HEIC / HEIF / AVIF 图片预览提供器（ISOBMFF 容器结构 + EXIF + 内嵌图）。
 *
 * <p>SPI 类型：{@code preview-heic}。HEIC 主图是 HEVC/AV1 压缩帧，纯 Java 没有解码器；
 * 引原生库（imageio-heif → 需系统安装 libheif + libde265/dav1d，测试机与部署机都得装）
 * 风险高，故本提供器走「结构 + 元数据 + 内嵌图」：</p>
 *
 * <ol>
 *   <li><b>容器结构</b>：按 ISO/IEC 14496-12 (ISOBMFF) 递归走 box 树，读 ftyp 品牌、
 *       pitm 主项、iinf/infe 项表、iloc 数据布局、iprp/ipco/ispe 空间尺寸、ipma 属性关联；</li>
 *   <li><b>EXIF</b>：定位类型为 {@code Exif} 的 item，按 iloc 偏移取出 TIFF 数据，
 *       交给 metadata-extractor（白名单字段，与 RAW 同口径）；</li>
 *   <li><b>图</b>：扫描文件内嵌 JPEG（HEIC 常带 JPEG 缩略图/预览帧）→ ImageIO 解码兜底，
 *       拿到哪级算哪级并如实标注；</li>
 *   <li>拿不到图时仍给出结构树与 EXIF，并明说主图是 HEVC/AV1 编码、需要原生解码库。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-heic")
public class HeicPreviewProvider implements FileStoragePreviewProvider {

    /** 支持的扩展名 */
    private static final Set<String> SUPPORTED_EXTS = Set.of("heic", "heif", "avif");

    /** 最大可预览文件（结构与 EXIF 都是轻量读取，限的是读入内存的量） */
    private static final long MAX_FILE_SIZE = 256L * 1024 * 1024;

    /** 内嵌图上限（超过就不值得整块 base64 进 HTML） */
    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;

    /** ImageIO 兜底解码的像素守卫（1600 万像素 ≈ 64 MB 堆） */
    private static final long MAX_DECODE_PIXELS = 16_000_000L;

    /** 展示的预览图最长边 */
    private static final int PREVIEW_MAX_DIM = 2000;

    /** box 树最多展示的节点数（防畸形文件刷屏） */
    private static final int MAX_TREE_NODES = 400;

    /** EXIF 字段白名单（标签名 → 展示名），与 RAW 同口径 */
    private static final Map<String, String> FIELD_LABELS = Wraps.linkedMap();

    /** 有子 box 的容器型 box（体内跟的是子 box 序列）；iref 不走容器——其子 box 语义不按普通 box 解析 */
    private static final Set<String> CONTAINER_TYPES = Set.of(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "udta", "dinf", "meta",
            "iprp", "ipco", "iinf");

    /** FullBox 容器（容器 4 字节 version+flags） */
    private static final Set<String> FULLBOX_TYPES = Set.of("meta", "iinf");

    static {
        FIELD_LABELS.put("Make", "厂商");
        FIELD_LABELS.put("Model", "机型");
        FIELD_LABELS.put("Lens Model", "镜头");
        FIELD_LABELS.put("Exposure Time", "快门");
        FIELD_LABELS.put("F Number", "光圈");
        FIELD_LABELS.put("ISO Speed Ratings", "ISO");
        FIELD_LABELS.put("ISO", "ISO");
        FIELD_LABELS.put("Focal Length", "焦距");
        FIELD_LABELS.put("Focal Length In 35mm Film", "等效焦距");
        FIELD_LABELS.put("Exposure Program", "曝光模式");
        FIELD_LABELS.put("Exposure Bias Value", "曝光补偿");
        FIELD_LABELS.put("Metering Mode", "测光");
        FIELD_LABELS.put("Flash", "闪光灯");
        FIELD_LABELS.put("DateTime Original", "拍摄时间");
        FIELD_LABELS.put("Create Date", "拍摄时间");
        FIELD_LABELS.put("Orientation", "方向");
        FIELD_LABELS.put("Image Width", "宽");
        FIELD_LABELS.put("Image Height", "高");
        FIELD_LABELS.put("Color Space", "色彩空间");
        FIELD_LABELS.put("Software", "软件");
        FIELD_LABELS.put("Artist", "作者");
        FIELD_LABELS.put("Copyright", "版权");
        FIELD_LABELS.put("GPS Latitude", "GPS 纬度");
        FIELD_LABELS.put("GPS Longitude", "GPS 经度");
    }

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        if (content.length == 0) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件为空")).build();
        }
        if (content.length > MAX_FILE_SIZE) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件过大（超过 256 MB），暂不支持预览")).build();
        }
        try {
            return PreviewResult.builder().htmlContent(parse(content)).build();
        } catch (Exception e) {
            return PreviewResult.builder()
                    .htmlContent(unavailableHtml("HEIC 解析失败: " + escape(e.getMessage()))).build();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 解析主流程                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 解析 HEIC：结构树 + EXIF + 内嵌图。
     *
     * @param content 文件字节
     * @return 完整 HTML
     * @throws IOException 读取失败
     */
    private String parse(byte[] content) throws IOException {
        HeifBox root = parseHeif(content);
        if (root == null) {
            return unavailableHtml("不是有效的 HEIC/HEIF 文件（缺少 ftyp 或非 ISOBMFF 容器）");
        }
        Map<String, String> fields = readExif(content, root);
        PreviewImage image = findImage(content);
        return buildHtml(content.length, root, fields, image);
    }

    /**
     * 走 ISOBMFF box 树。
     *
     * <p>解析失败（结构不完整）不抛错——已解析出的部分照常展示，由页面如实标注。</p>
     *
     * @param content 文件字节
     * @return 根节点；第一个 box 不是 ftyp 时返回 null
     */
    private static HeifBox parseHeif(byte[] content) {
        if (content.length < 12) {
            return null;
        }
        String first = new String(content, 4, 4, StandardCharsets.ISO_8859_1);
        if (!"ftyp".equals(first)) {
            return null;
        }
        HeifBox root = new HeifBox("root", 0, content.length);
        walkBoxes(content, 0, content.length, root, 0);
        collectSemantics(root, content);
        return root;
    }

    /**
     * 递归解析 box 序列。
     *
     * @param content 文件字节
     * @param from    起始偏移
     * @param to      结束偏移（不含）
     * @param parent  父节点
     * @param depth   当前深度
     */
    private static void walkBoxes(byte[] content, int from, int to, HeifBox parent, int depth) {
        if (depth > 8 || parent.children.size() >= MAX_TREE_NODES) {
            return;
        }
        int pos = from;
        while (pos + 8 <= to && parent.children.size() < MAX_TREE_NODES) {
            long size = u32(content, pos);
            String typeName = str4(content, pos + 4);
            long header = 8;
            if (size == 1) {
                if (pos + 16 > to) {
                    break;
                }
                size = u64(content, pos + 8);
                header = 16;
            } else if (size == 0) {
                size = to - pos;
            }
            if (size < header || pos + size > to) {
                // 结构损坏/截断：记录残余并停止
                if (pos + 8 <= to) {
                    HeifBox bad = new HeifBox("«截断»", pos, to - pos);
                    bad.damage = true;
                    parent.children.add(bad);
                    parent.damage = true;
                }
                return;
            }
            int boxEnd = (int) Math.min(pos + size, Integer.MAX_VALUE);
            HeifBox box = new HeifBox(typeName, pos, boxEnd);
            parent.children.add(box);
            if (CONTAINER_TYPES.contains(typeName)) {
                int childFrom = (int) (pos + header);
                if (FULLBOX_TYPES.contains(typeName)) {
                    childFrom += 4;
                }
                if ("iinf".equals(typeName) && childFrom + 2 <= boxEnd) {
                    // entry_count：version>0 用 u32，否则 u16
                    int ver = content[childFrom - 4] & 0xFF;
                    childFrom += ver > 0 ? 4 : 2;
                }
                if (childFrom <= boxEnd) {
                    walkBoxes(content, childFrom, boxEnd, box, depth + 1);
                }
            }
            pos = boxEnd;
        }
    }

    /**
     * 收集语义信息（ftyp 品牌、pitm、项表、尺寸、iloc 数据布局、ipma 属性关联）。
     *
     * @param root    根节点
     * @param content 文件字节
     */
    private static void collectSemantics(HeifBox root, byte[] content) {
        for (HeifBox b : flatten(root)) {
            switch (b.name) {
                case "ftyp":
                    parseFtyp(b, content, root);
                    break;
                case "pitm":
                    parsePitm(b, content, root);
                    break;
                case "infe":
                    parseInfe(b, content, root);
                    break;
                case "ispe":
                    parseIspe(b, content, root);
                    break;
                case "iloc":
                    parseIloc(b, content, root);
                    break;
                case "ipma":
                    parseIpma(b, content, root);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * ftyp：主流品牌 + 兼容品牌列表。
     *
     * @param b       ftyp box
     * @param content 文件字节
     * @param root    根节点
     */
    private static void parseFtyp(HeifBox b, byte[] content, HeifBox root) {
        int p = b.start + 8;
        if (p + 8 > b.end) {
            return;
        }
        root.majorBrand = str4(content, p);
        p += 8;
        while (p + 4 <= b.end) {
            root.brands.add(str4(content, p));
            p += 4;
        }
    }

    /**
     * pitm：主项 ID。
     *
     * @param b       pitm box
     * @param content 文件字节
     * @param root    根节点
     */
    private static void parsePitm(HeifBox b, byte[] content, HeifBox root) {
        int p = b.start + 8;
        if (p + 4 > b.end) {
            return;
        }
        int ver = content[p] & 0xFF;
        p += 4;
        if (p + 2 <= b.end) {
            root.primaryItem = ver > 0 ? (int) u32(content, p) : (int) u16(content, p);
        }
    }

    /**
     * infe：项条目（v2 起才有 item_type）。
     *
     * @param b       infe box
     * @param content 文件字节
     * @param root    根节点
     */
    private static void parseInfe(HeifBox b, byte[] content, HeifBox root) {
        int p = b.start + 8;
        if (p + 4 > b.end) {
            return;
        }
        int ver = content[p] & 0xFF;
        p += 4;
        long itemId;
        if (ver <= 1) {
            if (p + 4 > b.end) {
                return;
            }
            itemId = u16(content, p);
            p += 4;
            b.itemName = cstring(content, p, b.end);
        } else if (ver == 2) {
            if (p + 6 > b.end) {
                return;
            }
            itemId = u16(content, p);
            p += 2;
            p += 2;
            b.itemType = str4(content, p);
            p += 4;
            b.itemName = cstring(content, p, b.end);
        } else {
            if (p + 8 > b.end) {
                return;
            }
            itemId = u32(content, p);
            p += 4;
            p += 2;
            b.itemType = str4(content, p);
            p += 4;
            b.itemName = cstring(content, p, b.end);
        }
        b.itemId = itemId;
        root.items.add(b);
    }

    /**
     * ispe：空间尺寸（宽高）。
     *
     * @param b       ispe box
     * @param content 文件字节
     * @param root    根节点
     */
    private static void parseIspe(HeifBox b, byte[] content, HeifBox root) {
        int p = b.start + 8;
        if (p + 12 > b.end) {
            return;
        }
        p += 4;
        b.width = (int) u32(content, p);
        b.height = (int) u32(content, p + 4);
        if (root.ispeW == 0) {
            root.ispeW = b.width;
            root.ispeH = b.height;
        }
    }

    /**
     * ipma：主项关联的属性索引（1-based，指向 ipco 子 box 序号）。
     *
     * @param b       ipma box
     * @param content 文件字节
     * @param root    根节点
     */
    private static void parseIpma(HeifBox b, byte[] content, HeifBox root) {
        int p = b.start + 8;
        if (p + 8 > b.end) {
            return;
        }
        int ver = content[p] & 0xFF;
        int flags = ((content[p] & 0xFF) << 16) | ((content[p + 1] & 0xFF) << 8) | (content[p + 2] & 0xFF);
        p += 4;
        long entries = u32(content, p);
        p += 4;
        for (long i = 0; i < entries && p + 3 <= b.end; i++) {
            long itemId;
            if (ver < 1) {
                if (p + 2 > b.end) {
                    return;
                }
                itemId = u16(content, p);
                p += 2;
            } else {
                if (p + 4 > b.end) {
                    return;
                }
                itemId = u32(content, p);
                p += 4;
            }
            int assoc = content[p++] & 0xFF;
            for (int j = 0; j < assoc && p < b.end; j++) {
                int v;
                if ((flags & 1) != 0) {
                    if (p + 2 > b.end) {
                        return;
                    }
                    v = (int) u16(content, p);
                    p += 2;
                } else {
                    v = content[p++] & 0xFF;
                }
                int idx = v & 0x7FFF;
                if (itemId == root.primaryItem && idx > 0) {
                    root.primaryProps.add(idx);
                }
            }
        }
    }

    /**
     * iloc：数据布局表（构造方法 0 = 文件内绝对偏移）。
     *
     * @param b       iloc box
     * @param content 文件字节
     * @param root    根节点
     */
    private static void parseIloc(HeifBox b, byte[] content, HeifBox root) {
        int p = b.start + 8;
        if (p + 4 > b.end) {
            return;
        }
        int ver = content[p] & 0xFF;
        p += 4;
        if (ver > 2 || p + 2 > b.end) {
            return;
        }
        int v = content[p++] & 0xFF;
        int offsetSize = v >> 4;
        int lengthSize = v & 0x0F;
        v = content[p++] & 0xFF;
        int baseSize = v >> 4;
        int indexSize = ver >= 1 ? (v & 0x0F) : 0;
        if (p + 2 > b.end) {
            return;
        }
        long itemCount = ver < 2 ? u16(content, p) : u32(content, p);
        p += ver < 2 ? 2 : 4;
        for (long i = 0; i < itemCount && p < b.end; i++) {
            long itemId;
            if (ver < 2) {
                if (p + 2 > b.end) {
                    return;
                }
                itemId = u16(content, p);
                p += 2;
            } else {
                if (p + 4 > b.end) {
                    return;
                }
                itemId = u32(content, p);
                p += 4;
            }
            int construction = 0;
            if (ver >= 1) {
                if (p + 2 > b.end) {
                    return;
                }
                construction = (int) (u16(content, p) & 0x0F);
                p += 2;
            }
            if (p + 2 > b.end) {
                return;
            }
            p += 2;
            long base = readSized(content, p, baseSize, b.end);
            p += baseSize;
            if (p + 2 > b.end) {
                return;
            }
            long extentCount = u16(content, p);
            p += 2;
            for (long e = 0; e < extentCount && p < b.end; e++) {
                if (indexSize > 0) {
                    p += indexSize;
                }
                if (p + offsetSize + lengthSize > b.end) {
                    return;
                }
                long off = readSized(content, p, offsetSize, b.end);
                p += offsetSize;
                long len = readSized(content, p, lengthSize, b.end);
                p += lengthSize;
                if (construction == 0) {
                    HeifLoc loc = new HeifLoc();
                    loc.itemId = itemId;
                    loc.offset = base + off;
                    loc.length = len;
                    root.locs.add(loc);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* EXIF / 图                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 读 EXIF：定位类型为 Exif 的 item → 取其 TIFF 数据 → metadata-extractor 白名单字段。
     *
     * @param content 文件字节
     * @param root    结构根
     * @return 标签 → 描述
     */
    private static Map<String, String> readExif(byte[] content, HeifBox root) {
        Map<String, String> out = Wraps.linkedMap();
        for (HeifBox item : root.items) {
            if (!"Exif".equals(item.itemType)) {
                continue;
            }
            HeifLoc loc = null;
            for (HeifLoc l : root.locs) {
                if (l.itemId == item.itemId) {
                    loc = l;
                    break;
                }
            }
            if (loc == null || loc.offset < 0 || loc.length < 8
                    || loc.offset + loc.length > content.length) {
                continue;
            }
            int start = (int) loc.offset;
            int end = (int) Math.min(content.length, start + loc.length);
            // HEIF Exif item：前 4 字节是到 TIFF 头的偏移；保险起见直接找 TIFF 魔数
            int tiff = indexOfTiffHeader(content, start, Math.min(end, start + 64));
            if (tiff < 0) {
                tiff = indexOfTiffHeader(content, start, end);
            }
            if (tiff < 0) {
                continue;
            }
            byte[] tiffBytes = new byte[end - tiff];
            System.arraycopy(content, tiff, tiffBytes, 0, tiffBytes.length);
            try {
                Metadata meta = new Metadata();
                new ExifReader().extract(new ByteArrayReader(tiffBytes), meta);
                mergeExif(meta, out);
            } catch (RuntimeException ignored) {
                // EXIF 拿不到不影响结构展示
            }
            break;
        }
        return out;
    }

    /**
     * 找 TIFF 头（{@code II*\\0} 或 {@code MM\\0*}）。
     *
     * @param content 文件字节
     * @param from    起始（含）
     * @param to      结束（不含）
     * @return 偏移；-1 未找到
     */
    private static int indexOfTiffHeader(byte[] content, int from, int to) {
        for (int i = Math.max(0, from); i + 4 <= to && i + 4 <= content.length; i++) {
            if (content[i] == 'I' && content[i + 1] == 'I' && content[i + 2] == 42 && content[i + 3] == 0) {
                return i;
            }
            if (content[i] == 'M' && content[i + 1] == 'M' && content[i + 2] == 0 && content[i + 3] == 42) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 合并 EXIF 字段（白名单，去重）。
     *
     * @param meta EXIF 元数据
     * @param out  目标表
     */
    private static void mergeExif(Metadata meta, Map<String, String> out) {
        if (meta == null) {
            return;
        }
        for (com.drew.metadata.Directory dir : meta.getDirectories()) {
            for (Tag tag : dir.getTags()) {
                String label = FIELD_LABELS.get(tag.getTagName());
                if (label == null || out.containsKey(label)) {
                    continue;
                }
                String desc = tag.getDescription();
                if (desc != null && !desc.isBlank() && desc.length() < 200) {
                    out.put(label, desc);
                }
            }
        }
    }

    /**
     * 找可展示的图：内嵌 JPEG 扫描（HEIC 常埋 JPEG 缩略图）→ ImageIO 解码兜底。
     *
     * @param content 文件字节
     * @return 预览图；全失败返回 null
     */
    private static PreviewImage findImage(byte[] content) {
        List<int[]> ranges = jpegRanges(content);
        for (int i = ranges.size() - 1; i >= 0 && i > ranges.size() - 9; i--) {
            int[] r = ranges.get(i);
            byte[] cand = new byte[r[1] - r[0]];
            System.arraycopy(content, r[0], cand, 0, cand.length);
            PreviewImage img = decodePng(cand);
            if (img != null) {
                img.source = "内嵌 JPEG 缩略图";
                return img;
            }
        }
        return decodeViaImageIo(content);
    }

    /**
     * 线性扫描内嵌 JPEG（SOI..EOI），返回候选区间。
     *
     * @param content 文件字节
     * @return 候选区间 [start,end) 列表；无则空
     */
    private static List<int[]> jpegRanges(byte[] content) {
        List<int[]> out = Wraps.list();
        if (content.length < 4) {
            return out;
        }
        int i = 0;
        while (i < content.length - 3 && out.size() < 64) {
            if (content[i] == (byte) 0xFF && content[i + 1] == (byte) 0xD8
                    && (content[i + 2] & 0xFF) == 0xFF) {
                int end = findEoi(content, i + 3);
                if (end > i + 4 && end - i <= MAX_PREVIEW_BYTES) {
                    out.add(new int[]{i, end});
                }
                i += 3;
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * 找 JPEG EOI（FF D9）。
     *
     * @param content 文件字节
     * @param from    起始下标
     * @return 结束位置（含 2 字节）；-1 未找到
     */
    private static int findEoi(byte[] content, int from) {
        for (int i = from; i < content.length - 1; i++) {
            if (content[i] == (byte) 0xFF && content[i + 1] == (byte) 0xD9) {
                return i + 2;
            }
        }
        return -1;
    }

    /**
     * 解码字节并转 PNG（等比压到 {@link #PREVIEW_MAX_DIM}），返回 base64。
     *
     * @param bytes JPEG/PNG 字节
     * @return 预览图；解不出返回 null
     */
    private static PreviewImage decodePng(byte[] bytes) {
        try {
            BufferedImage raw = ImageIO.read(new ByteArrayInputStream(bytes));
            if (raw == null) {
                return null;
            }
            BufferedImage img = scale(raw);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", bos)) {
                return null;
            }
            PreviewImage out = new PreviewImage();
            out.base64 = Base64.getEncoder().encodeToString(bos.toByteArray());
            out.width = raw.getWidth();
            out.height = raw.getHeight();
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 兜底解码：用当前 classpath 里可用的 ImageReader 读第一帧（带像素守卫）。
     *
     * @param content 文件字节
     * @return 预览图；不可读返回 null
     */
    private static PreviewImage decodeViaImageIo(byte[] content) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > MAX_DECODE_PIXELS) {
                    return null;
                }
                BufferedImage raw = reader.read(0);
                BufferedImage img = scale(raw);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (!ImageIO.write(img, "png", bos)) {
                    return null;
                }
                PreviewImage out = new PreviewImage();
                out.base64 = Base64.getEncoder().encodeToString(bos.toByteArray());
                out.width = w;
                out.height = h;
                out.source = "ImageIO 解码（" + reader.getFormatName() + "）";
                return out;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 等比缩放到最长边 {@link #PREVIEW_MAX_DIM}。
     *
     * @param src 原图
     * @return 缩放后的图（无需缩放时原样返回）
     */
    private static BufferedImage scale(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= PREVIEW_MAX_DIM) {
            return src;
        }
        double k = (double) PREVIEW_MAX_DIM / max;
        int nw = Math.max(1, (int) Math.round(w * k));
        int nh = Math.max(1, (int) Math.round(h * k));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /* ------------------------------------------------------------------ */
    /* 输出构建                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 构建预览 HTML（预览图 + EXIF + 项表 + box 结构树）。
     *
     * @param size   文件字节数
     * @param root   结构根
     * @param fields EXIF 字段
     * @param image  预览图（可 null）
     * @return 完整 HTML
     */
    private String buildHtml(long size, HeifBox root, Map<String, String> fields, PreviewImage image) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
                .append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}")
                .append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}")
                .append("h1{margin:0 0 12px;font-size:20px;font-weight:600}")
                .append("h2{margin:20px 0 8px;font-size:15px;font-weight:600}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:8px;font-size:13px}")
                .append(".k{color:#9ca3af}.v{color:#1f2937;word-break:break-all}")
                .append(".shot{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:16px;text-align:center;margin-bottom:20px}")
                .append(".shot img{max-width:100%;max-height:75vh;height:auto;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,.12)}")
                .append(".cap{margin-top:10px;font-size:12px;color:#6b7280}")
                .append(".notice{padding:8px 12px;background:#fffbeb;border:1px solid #fde68a;border-radius:8px;color:#92400e;font-size:13px;margin-bottom:16px}")
                .append(".empty{color:#6b7280;text-align:center;padding:32px;background:#fff;border:1px dashed #d1d5db;border-radius:12px}")
                .append("table{border-collapse:collapse;width:100%;font-size:13px;background:#fff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden}")
                .append("td,th{border-bottom:1px solid #f1f3f5;padding:6px 10px;text-align:left}")
                .append("th{background:#f8f9fa;color:#6b7280;font-weight:600}")
                .append("td.n{font-family:ui-monospace,Consolas,monospace;color:#6b7280;white-space:nowrap}")
                .append(".tree{font-family:ui-monospace,Consolas,monospace;font-size:12px;background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:12px;overflow:auto;line-height:1.7}")
                .append(".dim{color:#9ca3af}")
                .append("</style></head><body>");

        // 头部：结构要点 + EXIF
        sb.append("<div class=\"header\"><h1>HEIC / HEIF 预览</h1><div class=\"grid\">");
        sb.append(kv("文件大小", humanSize(size)));
        if (!root.brands.isEmpty()) {
            sb.append(kv("品牌", root.majorBrand + (root.brands.size() > 1
                    ? "（兼容: " + String.join(" ", root.brands) + "）" : "")));
        }
        if (root.primaryItem > 0) {
            sb.append(kv("主项 (pitm)", "#" + root.primaryItem));
        }
        if (root.ispeW > 0 && root.ispeH > 0) {
            sb.append(kv("空间尺寸 (ispe)", root.ispeW + " × " + root.ispeH));
        } else if (image != null && image.height > 0) {
            sb.append(kv("尺寸", image.width + " × " + image.height));
        }
        sb.append(kv("项数 (iinf)", String.valueOf(root.items.size())));
        if (root.damage) {
            sb.append(kv("结构", "存在截断/损坏的 box"));
        }
        fields.forEach((k, v) -> sb.append(kv(k, v)));
        sb.append("</div></div>");

        // 预览图
        if (image != null) {
            sb.append("<div class=\"shot\"><img alt=\"preview\" src=\"data:image/png;base64,")
                    .append(image.base64).append("\">")
                    .append("<div class=\"cap\">来源：").append(escape(image.source))
                    .append(" · 已等比缩放至最长边 ").append(PREVIEW_MAX_DIM).append(" px</div></div>");
        } else {
            sb.append("<div class=\"empty\">未能取得可展示的图像：主图为 HEVC (hvc1) / AV1 (av01) 压缩帧，")
                    .append("纯 Java 无解码器；文件内也未找到可解的内嵌 JPEG。</div>");
        }
        sb.append("<div class=\"notice\">说明：HEIC 主图编码为 HEVC/AV1，本预览展示容器结构、")
                .append("EXIF 参数与内嵌缩略图；如需完整渲染主图，请安装原生解码库（libheif + libde265/dav1d）。</div>");

        // 项表
        if (!root.items.isEmpty()) {
            sb.append("<h2>项表（iinf / infe）</h2><table><tr><th>ID</th><th>类型</th><th>名称</th>")
                    .append("<th>数据偏移</th><th>长度</th></tr>");
            int shown = 0;
            for (HeifBox item : root.items) {
                if (shown++ >= 64) {
                    sb.append("<tr><td colspan=\"5\" class=\"dim\">… 其余 ")
                            .append(root.items.size() - 64).append(" 项省略</td></tr>");
                    break;
                }
                long off = -1;
                long len = -1;
                for (HeifLoc l : root.locs) {
                    if (l.itemId == item.itemId) {
                        off = l.offset;
                        len = l.length;
                        break;
                    }
                }
                sb.append("<tr><td class=\"n\">#").append(item.itemId).append("</td>")
                        .append("<td class=\"n\">")
                        .append(escape(item.itemType.isEmpty() ? "-" : item.itemType)).append("</td>")
                        .append("<td>").append(escape(item.itemName == null ? "" : item.itemName)).append("</td>")
                        .append("<td class=\"n\">").append(off < 0 ? "-" : off).append("</td>")
                        .append("<td class=\"n\">").append(len < 0 ? "-" : len).append("</td></tr>");
            }
            sb.append("</table>");
        }

        // box 结构树
        sb.append("<h2>box 结构树</h2><div class=\"tree\">");
        renderTree(sb, root, root, 0);
        sb.append("</div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 渲染 box 树（缩进 + 大小 + 语义补充）。
     *
     * @param sb    输出
     * @param box   节点
     * @param root  根节点（语义字段挂根上）
     * @param depth 深度
     */
    private static void renderTree(StringBuilder sb, HeifBox box, HeifBox root, int depth) {
        if (depth > 0) {
            sb.append("<div>").append("│ ".repeat(Math.max(0, depth - 1)))
                    .append("├ ").append(escape(box.name))
                    .append(" <span class=\"dim\">").append(box.end - box.start).append(" B</span>");
            String extra = treeExtra(box, root);
            if (!extra.isEmpty()) {
                sb.append(" <span class=\"dim\">").append(escape(extra)).append("</span>");
            }
            sb.append("</div>");
        }
        for (HeifBox child : box.children) {
            renderTree(sb, child, root, depth + 1);
        }
    }

    /**
     * box 树节点的语义补充（仅关键节点）。
     *
     * @param box  节点
     * @param root 根节点
     * @return 补充文本；无则空
     */
    private static String treeExtra(HeifBox box, HeifBox root) {
        switch (box.name) {
            case "ftyp":
                return "brand=" + root.majorBrand;
            case "ispe":
                return box.width + "×" + box.height;
            case "infe":
                return "#" + box.itemId + (box.itemType.isEmpty() ? "" : " " + box.itemType);
            case "iloc":
                return "layouts=" + root.locs.size();
            default:
                return "";
        }
    }

    /**
     * 构建不可预览页面。
     *
     * @param message 提示信息
     * @return 完整 HTML
     */
    private String unavailableHtml(String message) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><style>"
                + "body{margin:0;background:#f8f9fa;color:#666;display:flex;justify-content:center;"
                + "align-items:center;min-height:100vh;font-family:sans-serif}"
                + ".box{text-align:center;padding:40px;border:2px dashed #ddd;border-radius:12px}"
                + "</style></head><body><div class=\"box\">" + escape(message) + "</div></body></html>";
    }

    /**
     * 键值格。
     *
     * @param k 键
     * @param v 值
     * @return HTML 片段
     */
    private static String kv(String k, String v) {
        return "<div><span class=\"k\">" + k + "</span><br><span class=\"v\">" + escape(v) + "</span></div>";
    }

    /**
     * HTML 转义。
     *
     * @param text 原始文本
     * @return 转义后文本
     */
    private static String escape(String text) {
        return text == null ? "" : StringUtils.escapeHtml(text);
    }

    /**
     * 可读文件大小。
     *
     * @param bytes 字节数
     * @return 可读大小
     */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /* ------------------------------------------------------------------ */
    /* 字节读取辅助                                                        */
    /* ------------------------------------------------------------------ */

    private static long u16(byte[] b, int p) {
        return ((b[p] & 0xFFL) << 8) | (b[p + 1] & 0xFFL);
    }

    private static long u32(byte[] b, int p) {
        return ((b[p] & 0xFFL) << 24) | ((b[p + 1] & 0xFFL) << 16)
                | ((b[p + 2] & 0xFFL) << 8) | (b[p + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int p) {
        long hi = u32(b, p);
        long lo = u32(b, p + 4);
        long v = (hi << 32) | lo;
        return Math.max(v, 0);
    }

    /**
     * 按指定字节数读无符号整数（0/1/2/4/8）。
     *
     * @param b     字节
     * @param p     起点
     * @param size  字节数
     * @param limit 上界（防止越界）
     * @return 值；size 非法返回 0
     */
    private static long readSized(byte[] b, int p, int size, int limit) {
        if (size <= 0 || p < 0 || p + size > b.length || p + size > limit) {
            return 0;
        }
        long v = 0;
        for (int i = 0; i < size; i++) {
            v = (v << 8) | (b[p + i] & 0xFFL);
        }
        return v;
    }

    /**
     * 读 4 字节 ASCII。
     *
     * @param b 字节
     * @param p 起点
     * @return 字符串
     */
    private static String str4(byte[] b, int p) {
        if (p < 0 || p + 4 > b.length) {
            return "";
        }
        return new String(b, p, 4, StandardCharsets.ISO_8859_1);
    }

    /**
     * 读 C 字符串（到 nul 或上界）。
     *
     * @param b     字节
     * @param p     起点
     * @param limit 上界
     * @return 字符串
     */
    private static String cstring(byte[] b, int p, int limit) {
        if (p >= limit || p >= b.length) {
            return "";
        }
        int end = p;
        int max = Math.min(limit, b.length);
        while (end < max && b[end] != 0) {
            end++;
        }
        String s = new String(b, p, end - p, StandardCharsets.UTF_8);
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /**
     * 深度优先摊平（排除 root 自身）。
     *
     * @param root 根
     * @return 节点列表
     */
    private static List<HeifBox> flatten(HeifBox root) {
        List<HeifBox> out = Wraps.list();
        collectBoxes(root, out);
        return out;
    }

    /**
     * 递归收集（含 root）。
     *
     * @param box 节点
     * @param out 输出
     */
    private static void collectBoxes(HeifBox box, List<HeifBox> out) {
        out.add(box);
        for (HeifBox c : box.children) {
            collectBoxes(c, out);
        }
    }

    /**
     * box 节点。
     */
    private static final class HeifBox {
        /** box 类型名 */
        final String name;
        /** 起始偏移（含） */
        final int start;
        /** 结束偏移（不含） */
        final int end;
        /** 子 box */
        final List<HeifBox> children = Wraps.list();
        /** 子项（infe 解析结果，仅 infe 节点使用） */
        final List<HeifBox> items = Wraps.list();
        /** 数据布局（仅 root 使用） */
        final List<HeifLoc> locs = Wraps.list();
        /** 兼容品牌 */
        final Set<String> brands = Wraps.linkedSet();
        /** 主项关联的属性索引（仅 root 使用） */
        final Set<Integer> primaryProps = Wraps.linkedSet();
        /** 是否存在截断/损坏 */
        boolean damage;
        /** 主流品牌 */
        String majorBrand = "";
        /** 主项 ID */
        long primaryItem;
        /** ispe 尺寸 */
        int ispeW;
        /** ispe 尺寸 */
        int ispeH;
        /** ispe：宽（仅 ispe 节点） */
        int width;
        /** ispe：高（仅 ispe 节点） */
        int height;
        /** infe：项 ID */
        long itemId;
        /** infe：项类型（4CC） */
        String itemType = "";
        /** infe：项名称 */
        String itemName = "";

        HeifBox(String name, int start, int end) {
            this.name = name;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * iloc 数据布局。
     */
    private static final class HeifLoc {
        /** 项 ID */
        long itemId;
        /** 文件内绝对偏移 */
        long offset;
        /** 字节长度 */
        long length;
    }

    /**
     * 内嵌预览图载体。
     */
    private static final class PreviewImage {
        /** base64 PNG */
        String base64;
        /** 原始像素宽 */
        int width;
        /** 原始像素高 */
        int height;
        /** 图的来源描述 */
        String source = "内嵌图";
    }
}
