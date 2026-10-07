package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 相机 RAW 照片预览提供器。
 *
 * <p>SPI 类型：{@code preview-raw}。RAW（nef/arw/cr2/dng/raf/orf/rw2/…）浏览器无法原生解码，
 * 本提供器给出「参数 + 内嵌预览图」的真实预览，而非一张「不支持」提示卡：</p>
 *
 * <ol>
 *   <li><b>参数</b>：metadata-extractor 2.19 解析 EXIF/TIFF IFD（机型、镜头、曝光三要素、
 *       拍摄时间、尺寸等），按白名单取字段，避免把数百行技术标签倒给用户；</li>
 *   <li><b>预览图</b>：扫描文件内嵌 JPEG（SOI..EOI；EXIF IFD1 缩略图、机内预览、实时取景帧
 *       都是其中一段），从后往前试解码，最后一段通常是完整大图；再不行才尝试 ImageIO 解码
 *       全图（带尺寸守卫防 OOM）。拿到哪级算哪级，并如实标注图的来源与尺寸；</li>
 *   <li>全都拿不到时，仍有参数表可看，并说明原因。</li>
 * </ol>
 *
 * <p><b>为什么不做全画幅解码</b>：RAW 全量反马赛克需要 dcraw/LibRaw 量级的实现，
 * 引 JNI 库得不偿失；机内预览已是厂商自己解好的成图，质量与可用性都足够预览场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-raw")
public class RawPreviewProvider implements FileStoragePreviewProvider {

    /** 支持的 RAW 扩展名（均为相机厂商私有格式，浏览器一概不能解码） */
    private static final Set<String> SUPPORTED_EXTS = Set.of(
            "raw", "3fr", "arw", "cap", "cr2", "cr3", "crw", "dcr", "dng", "erf",
            "fff", "k25", "kdc", "mef", "mos", "mrw", "nef", "nrw", "orf",
            "pef", "raf", "rw2", "rwl", "sr2", "srf", "srw", "x3f");

    /** 最大可预览文件（再大也不过是取参数与内嵌图，限的是读入内存的量） */
    private static final long MAX_FILE_SIZE = 256L * 1024 * 1024;

    /** 内嵌预览图上限（超过就不值得整块 base64 进 HTML） */
    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;

    /** 兜底 ImageIO 解码的像素数守卫（1600 万像素 ≈ 64 MB 堆） */
    private static final long MAX_DECODE_PIXELS = 16_000_000L;

    /** 展示的预览图最长边 */
    private static final int PREVIEW_MAX_DIM = 2000;

    /** 参数白名单（标签名 → 展示名）。不在表里的一律不展示，避免刷屏。 */
    private static final Map<String, String> FIELD_LABELS = Wraps.linkedMap();

    static {
        FIELD_LABELS.put("Make", "厂商");
        FIELD_LABELS.put("Model", "机型");
        FIELD_LABELS.put("Lens Model", "镜头");
        FIELD_LABELS.put("Lens Make", "镜头厂牌");
        FIELD_LABELS.put("Exposure Time", "快门");
        FIELD_LABELS.put("F Number", "光圈");
        FIELD_LABELS.put("ISO Speed Ratings", "ISO");
        FIELD_LABELS.put("ISO", "ISO");
        FIELD_LABELS.put("Focal Length", "焦距");
        FIELD_LABELS.put("Focal Length In 35mm Film", "等效焦距");
        FIELD_LABELS.put("Exposure Program", "曝光模式");
        FIELD_LABELS.put("Shutter Speed Value", "快门值");
        FIELD_LABELS.put("Exposure Bias Value", "曝光补偿");
        FIELD_LABELS.put("Metering Mode", "测光");
        FIELD_LABELS.put("Flash", "闪光灯");
        FIELD_LABELS.put("DateTime Original", "拍摄时间");
        FIELD_LABELS.put("Create Date", "拍摄时间");
        FIELD_LABELS.put("Orientation", "方向");
        FIELD_LABELS.put("Image Width", "宽");
        FIELD_LABELS.put("Image Height", "高");
        FIELD_LABELS.put("Color Space", "色彩空间");
        FIELD_LABELS.put("Software", "固件/软件");
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
                    .htmlContent(unavailableHtml("RAW 解析失败: " + escape(e.getMessage()))).build();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 解析主流程                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 解析 RAW：参数表 + 内嵌预览图。
     *
     * @param content 文件字节
     * @return 完整 HTML
     * @throws IOException 读取失败
     */
    private String parse(byte[] content) throws IOException {
        Map<String, String> fields = readFields(content);
        PreviewImage image = findPreview(content);
        return buildHtml(content.length, fields, image);
    }

    /**
     * 用 metadata-extractor 取参数（按白名单，失败则空表——RAW 千奇百怪，解析不了不算错）。
     *
     * @param content 文件字节
     * @return 标签 → 描述
     */
    private static Map<String, String> readFields(byte[] content) {
        Map<String, String> out = Wraps.linkedMap();
        try {
            Metadata meta = ImageMetadataReader.readMetadata(new ByteArrayInputStream(content));
            if (meta == null) {
                return out;
            }
            for (Directory dir : meta.getDirectories()) {
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
        } catch (ImageProcessingException | IOException | RuntimeException ignored) {
            // 参数拿不到不影响出图；预览图仍走下面的内嵌扫描
        }
        return out;
    }

    /**
     * 找可展示的预览图：内嵌 JPEG 扫描（含 EXIF IFD1 缩略图——它本身就是文件内的一段 JPEG）
     * → ImageIO 解码兜底。
     *
     * @param content 文件字节
     * @return 预览图；全失败返回 null（页面仍出参数表）
     */
    private PreviewImage findPreview(byte[] content) {
        // 1) 扫描内嵌 JPEG（SOI..EOI），从后往前试解码——最后一段通常是完整大图
        List<int[]> ranges = jpegRanges(content);
        for (int i = ranges.size() - 1; i >= 0 && i > ranges.size() - 9; i--) {
            int[] r = ranges.get(i);
            byte[] cand = new byte[r[1] - r[0]];
            System.arraycopy(content, r[0], cand, 0, cand.length);
            PreviewImage img = decodePngJpeg(cand);
            if (img != null) {
                img.source = "机内预览图";
                return img;
            }
        }
        // 2) 兜底：交给 ImageIO（若容器里有能读该格式的 reader）
        return decodeViaImageIo(content);
    }

    /**
     * 线性扫描内嵌 JPEG（SOI..EOI），返回候选区间（按出现顺序）。
     *
     * <p>RAW 容器（CR2/NEF/RAF/ORF/RW2…）里通常埋着 1~N 张 JPEG：机内预览、实时取景帧、
     * 方向标定图等。候选可能含解不出的残段，由调用方**从后往前**试解码，通常最后一段才是
     * 完整大图。</p>
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
     * @return EOI 结束位置（含 2 字节）；-1 未找到
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
     * 解码字节并转成 PNG（等比压到 {@link #PREVIEW_MAX_DIM}），返回 base64。
     *
     * @param bytes JPEG/PNG 字节
     * @return 预览图；解不出返回 null
     */
    private static PreviewImage decodePngJpeg(byte[] bytes) {
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
                out.source = "全图解码";
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
     * 构建预览 HTML（预览图 + 参数表）。
     *
     * @param size   文件字节
     * @param fields EXIF 参数
     * @param image  预览图（可为 null）
     * @return 完整 HTML
     */
    private String buildHtml(long size, Map<String, String> fields, PreviewImage image) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
                .append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}")
                .append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}")
                .append("h1{margin:0 0 12px;font-size:20px;font-weight:600}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:8px;font-size:13px}")
                .append(".k{color:#9ca3af}.v{color:#1f2937;word-break:break-all}")
                .append(".shot{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:16px;text-align:center;margin-bottom:20px}")
                .append(".shot img{max-width:100%;max-height:75vh;height:auto;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,.12)}")
                .append(".cap{margin-top:10px;font-size:12px;color:#6b7280}")
                .append(".notice{padding:8px 12px;background:#fffbeb;border:1px solid #fde68a;border-radius:8px;color:#92400e;font-size:13px}")
                .append(".empty{color:#6b7280;text-align:center;padding:32px;background:#fff;border:1px dashed #d1d5db;border-radius:12px}")
                .append("</style></head><body>")
                .append("<div class=\"header\"><h1>RAW 照片预览</h1><div class=\"grid\">");
        fields.forEach((k, v) -> sb.append("<div><span class=\"k\">").append(escape(k))
                .append("</span><br><span class=\"v\">").append(escape(v)).append("</span></div>"));
        sb.append("<div><span class=\"k\">文件大小</span><br><span class=\"v\">").append(humanSize(size)).append("</span></div>");
        if (image != null && image.height > 0) {
            sb.append("<div><span class=\"k\">原图尺寸</span><br><span class=\"v\">")
                    .append(image.width).append(" × ").append(image.height).append("</span></div>");
        }
        sb.append("</div></div>");
        if (image != null) {
            sb.append("<div class=\"shot\"><img alt=\"preview\" src=\"data:image/png;base64,")
                    .append(image.base64).append("\">")
                    .append("<div class=\"cap\">来源：").append(escape(image.source))
                    .append(" · 已等比缩放至最长边 ").append(PREVIEW_MAX_DIM).append(" px</div></div>");
        } else {
            sb.append("<div class=\"empty\">未能取得可展示的图像：本机没有能解该 RAW 格式的 ImageReader，")
                    .append("且文件内未找到 EXIF 缩略图或机内预览 JPEG。</div>");
        }
        if (fields.isEmpty()) {
            sb.append("<div class=\"notice\">未能读取 EXIF 参数（格式未识别或文件被剥离元数据）。</div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 构建不可预览页面。
     *
     * @param message 提示信息
     * @return 完整 HTML
     */
    private String unavailableHtml(String message) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><style>"
                + "body{margin:0;background:#f8f9fa;color:#666;display:flex;justify-content:center;align-items:center;min-height:100vh;font-family:sans-serif}"
                + ".box{text-align:center;padding:40px;border:2px dashed #ddd;border-radius:12px}"
                + "</style></head><body><div class=\"box\">" + escape(message) + "</div></body></html>";
    }

    /**
     * HTML 转义。
     *
     * @param text 原始文本
     * @return 转义后文本
     */
    private String escape(String text) {
        return text == null ? "" : StringUtils.escapeHtml(text);
    }

    /**
     * 可读文件大小。
     *
     * @param bytes 字节数
     * @return 可读大小
     */
    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
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
        /** 来源说明 */
        String source = "";
    }
}
