package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;


/**
 * 马赛克艺术（贴图拼接）滤镜
 * <p>
 * 将原始图像按网格切分，每个网格用目录中的一张"贴图"（tile）替换，
 * 生成"贴图马赛克艺术"效果 —— 类似于用贴纸/表情包/像素块拼出一幅画。
 * <p>
 * 典型效果：整张图片由许多小图（如 emoji、贴纸、像素图案）拼成，
 * 但每个位置的贴图颜色与原图该区域的色调基本一致，因此能"看出"原图轮廓。
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>扫描贴图目录（{@link #tileDirectory}），加载所有图片并计算每张图的平均色</li>
 *   <li>将原图按 {@link #tileSize} × {@link #tileSize} 网格切分</li>
 *   <li>对每个网格，计算原图该区域的平均色</li>
 *   <li>根据策略选择一个贴图替换该网格：
 *       <ul>
 *         <li>{@link SelectMode#COLOR_MATCH}：选择平均色最接近的贴图（需要贴图数量 ≥ 10 才有良好观感）</li>
 *         <li>{@link SelectMode#RANDOM}：随机选择贴图</li>
 *         <li>{@link SelectMode#SEQUENTIAL}：按文件顺序循环选择</li>
 *       </ul>
 *   </li>
 *   <li>将选中的贴图缩放到网格大小后绘制到输出图</li>
 * </ol>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 1. 准备一个目录，里面放几十到几百张小图（16x16 ~ 64x64 为宜，PNG 可带透明）
 * //    例如 D:\tiles\emoji\*.png
 *
 * // 2. 创建滤镜
 * MosaicArtImageFilter filter = new MosaicArtImageFilter()
 *         .setTileDirectory("D:/tiles/emoji")
 *         .setTileSize(32)              // 网格大小（像素）
 *         .setSelectMode(MosaicArtImageFilter.SelectMode.COLOR_MATCH)
 *         .setBackground(new Color(0, 0, 0, 0));  // 透明背景（PNG 贴图常用）
 *
 * // 3. 转换
 * BufferedImage source = ImageIO.read(new File("photo.png"));
 * BufferedImage result = filter.converter(source);
 * ImageIO.write(result, "png", new File("mosaic.png"));
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>tileDirectory</b>（必填）：贴图目录的绝对路径或相对路径。
 *       目录中的文件按 <b>文件名升序</b>加载，保证 {@link SelectMode#SEQUENTIAL} 模式的结果可复现。
 *       子目录中的图片<b>不会</b>被加载（仅扫描一级目录）。</li>
 *   <li><b>tileSize</b>（默认 32）：每个网格的边长（像素）。
 *       值越大拼贴越"粗犷"，值越小越精细。
 *       建议与贴图原始尺寸一致或接近，避免过度缩放造成模糊。</li>
 *   <li><b>selectMode</b>（默认 COLOR_MATCH）：贴图选择策略，见 {@link SelectMode}。</li>
 *   <li><b>extensions</b>（默认 png,jpg,jpeg,bmp,gif,webp）：加载的贴图文件扩展名（不含点，小写匹配）。
 *       不支持的扩展名文件会被跳过。</li>
 *   <li><b>background</b>（默认白色）：输出图的底色。
 *       当贴图为透明 PNG 时，底色会透过透明区域显示。
 *       想让拼贴图本身也是透明的，请传 {@code new Color(0,0,0,0)} 并把输出格式设为 PNG。</li>
 *   <li><b>seed</b>（默认 null=每次不同）：随机数种子，仅影响 {@link SelectMode#RANDOM} 模式。
 *       设置固定值可复现随机拼贴结果。</li>
 *   <li><b>smooth</b>（默认 false）：贴图缩放到网格大小时是否使用双线性平滑。
 *       设为 true 时大图缩小更平滑；设为 false（最近邻）时小图放大呈像素块效果。</li>
 *   <li><b>maxTileDimension</b>（默认 256）：加载贴图时的最大边长。
 *       超过该尺寸的图片会被等比缩小到该尺寸，控制内存占用。设为 0 表示不限制。</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>贴图目录至少包含 1 张可读图片，否则抛出 {@link IllegalStateException}。</li>
 *   <li>{@link SelectMode#COLOR_MATCH} 模式下，贴图数量越多，颜色还原越精细。
 *       建议 ≥ 30 张；少于 10 张时观感较差（相邻网格会重复同一贴图）。</li>
 *   <li>输出图像尺寸与原图一致（网格可能不足一个完整网格时，边缘网格会被裁剪绘制）。</li>
 *   <li>贴图为透明 PNG 且 background 为透明时，输出为 TYPE_INT_ARGB；
 *       其余情况输出为 TYPE_INT_RGB（不支持透明）。</li>
 *   <li>本滤镜不支持流式 {@link #converter(java.io.InputStream)} 的 GIF 动画，
 *       流式输入会逐帧按静态图处理。</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("mosaic-art")
@SpiDescribe("贴图马赛克艺术滤镜")
@Accessors(chain = true)
public class MosaicArtImageFilter extends AbstractImageFilter {

    /**
     * 贴图目录路径（必填）
     */
    private String tileDirectory;

    /**
     * 网格大小（像素），默认 32
     */
    private int tileSize = 32;

    /**
     * 贴图绘制目标尺寸（宽=高，像素）。0 表示与网格大小一致（默认行为）。
     * <p>
     * 例如 tileSize=32、tileDrawSize=128 时，每个 32×32 的网格格子
     * 会被一张 128×128 的贴图覆盖（贴图超出网格的部分裁剪，网格之外的部分
     * 由相邻格子自然叠盖，形成"放大贴图"效果）。
     */
    private int tileDrawSize = 0;

    /**
     * 贴图选择策略，默认颜色匹配
     */
    private SelectMode selectMode = SelectMode.COLOR_MATCH;

    /**
     * 支持的贴图文件扩展名（不含点，小写），默认 png,jpg,jpeg,bmp,gif,webp
     */
    private List<String> extensions = new ArrayList<>(
            Arrays.asList("png", "jpg", "jpeg", "bmp", "gif", "webp"));

    /**
     * 输出图底色，默认白色
     */
    private Color background = Color.WHITE;

    /**
     * 随机种子（null 表示每次运行随机不同），仅影响 RANDOM 模式
     */
    private Long seed;

    /**
     * 贴图缩放是否平滑，默认 false（最近邻，像素块风格）
     */
    private boolean smooth = false;

    /**
     * 加载贴图时的最大边长（像素），默认 256；0 表示不限制
     */
    private int maxTileDimension = 256;

    /**
     * 贴图选择策略
     */
    public enum SelectMode {
        /**
         * 按颜色匹配：选择与原图该网格平均色最接近的贴图
         */
        COLOR_MATCH,
        /**
         * 随机选择：从所有贴图中随机取一张
         */
        RANDOM,
        /**
         * 顺序循环：按文件加载顺序依次取贴图（可复现）
         */
        SEQUENTIAL
    }

    /**
     * 内部贴图记录：图片 + 平均色
     */
    private static final class TileRecord {
        private final BufferedImage image;
        private final int avgR;
        private final int avgG;
        private final int avgB;

        TileRecord(BufferedImage image, int avgR, int avgG, int avgB) {
            this.image = image;
            this.avgR = avgR;
            this.avgG = avgG;
            this.avgB = avgB;
        }
    }

    /**
     * 已加载的贴图列表（懒加载缓存）
     */
    private volatile List<TileRecord> loadedTiles;

    /**
     * 执行贴图马赛克艺术滤镜
     * <p>
     * 流程：加载贴图 → 网格切分 → 每格选贴图 → 缩放绘制 → 输出
     *
     * @param src 源图像
     * @param dst 目标图像（此参数未使用，内部创建新图）
     * @return 贴图马赛克艺术图
     * @throws IllegalStateException 贴图目录未设置、目录不存在或加载不到任何贴图时
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        List<TileRecord> tiles = loadTiles();

        int width = src.getWidth();
        int height = src.getHeight();
        boolean hasAlpha = (background.getAlpha() == 0);
        BufferedImage out = new BufferedImage(width, height, hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

        // 填充底色
        Graphics2D g = out.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, width, height);

        // 贴图绘制模式
        if (smooth) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        } else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }

        Random random = (seed == null) ? new Random() : new Random(seed);
        int cellCount = 0;
        int drawSize = (tileDrawSize > 0) ? tileDrawSize : tileSize;

        // 按网格遍历
        for (int y = 0; y < height; y += tileSize) {
            int cellH = Math.min(tileSize, height - y);
            for (int x = 0; x < width; x += tileSize) {
                int cellW = Math.min(tileSize, width - x);

                // 计算原图该网格的平均色（坐标钳制，避免 tileDrawSize 放大后越界）
                int cx = Math.min(x, Math.max(0, width - 1));
                int cy = Math.min(y, Math.max(0, height - 1));
                int[] avg = averageColor(src, cx, cy, cellW, cellH);
                int avgR = avg[0], avgG = avg[1], avgB = avg[2];

                // 选择贴图
                TileRecord chosen;
                switch (selectMode) {
                    case COLOR_MATCH:
                        chosen = closestByColor(tiles, avgR, avgG, avgB);
                        break;
                    case RANDOM:
                        chosen = tiles.get(random.nextInt(tiles.size()));
                        break;
                    case SEQUENTIAL:
                    default:
                        chosen = tiles.get(cellCount % tiles.size());
                        break;
                }
                cellCount++;

                // 将贴图缩放到绘制尺寸并绘制（drawSize 可大于/小于网格大小）
                int dw = drawSize;
                int dh = drawSize;
                g.drawImage(chosen.image, x, y, dw, dh, null);
            }
        }

        g.dispose();
        return out;
    }

    /**
     * 懒加载并缓存贴图列表
     *
     * @return 贴图列表（至少 1 张）
     * @throws IllegalStateException 目录不存在 / 无贴图文件 / 所有文件均读取失败时
     */
    private List<TileRecord> loadTiles() {
        if (tileDirectory == null || tileDirectory.isEmpty()) {
            throw new IllegalStateException("tileDirectory 未设置，请调用 setTileDirectory(dir)");
        }
        List<TileRecord> cached = this.loadedTiles;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (loadedTiles != null) {
                return loadedTiles;
            }
            File dir = new File(tileDirectory);
            if (!dir.isDirectory()) {
                throw new IllegalStateException("贴图目录不存在或不是目录: " + dir.getAbsolutePath());
            }
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                throw new IllegalStateException("贴图目录为空: " + dir.getAbsolutePath());
            }
            // 文件名升序排序，保证 SEQUENTIAL 模式可复现
            Arrays.sort(files);

            List<TileRecord> list = new ArrayList<>();
            for (File file : files) {
                String name = file.getName().toLowerCase();
                int dot = name.lastIndexOf('.');
                if (dot < 0) {
                    continue;
                }
                String ext = name.substring(dot + 1);
                if (!extensions.contains(ext)) {
                    continue;
                }
                try {
                    BufferedImage img = ImageIO.read(file);
                    if (img == null) {
                        continue; // 该扩展名没有对应 ImageIO 读取器
                    }
                    // 限制最大边长，控制内存
                    if (maxTileDimension > 0) {
                        int w = img.getWidth();
                        int h = img.getHeight();
                        int maxSide = Math.max(w, h);
                        if (maxSide > maxTileDimension) {
                            double ratio = (double) maxTileDimension / maxSide;
                            w = (int) (w * ratio);
                            h = (int) (h * ratio);
                            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                            Graphics2D gs = scaled.createGraphics();
                            gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                    smooth ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                                           : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                            gs.drawImage(img, 0, 0, w, h, null);
                            gs.dispose();
                            img = scaled;
                        }
                    }
                    int[] avg = averageColorOfImage(img);
                    list.add(new TileRecord(img, avg[0], avg[1], avg[2]));
                } catch (IOException ignored) {
                    // 跳过无法读取的文件
                }
            }
            if (list.isEmpty()) {
                throw new IllegalStateException("目录中加载不到任何贴图: " + dir.getAbsolutePath());
            }
            loadedTiles = list;
            return list;
        }
    }

    /**
     * 计算图像指定区域的平均色（RGB 分量）
     * <p>
     * 采样策略：区域过大时按 1/8 网格采样（最多 16x16 个采样点），控制性能。
     *
     * @param src  源图像
     * @param x    区域左上角 x
     * @param y    区域左上角 y
     * @param w    区域宽
     * @param h    区域高
     * @return int[3] {r, g, b}，均为 0-255
     */
    private int[] averageColor(BufferedImage src, int x, int y, int w, int h) {
        // 采样步长：区域小于等于 16 像素时逐像素，否则 1/8 网格
        int stepX = Math.max(1, w / 8);
        int stepY = Math.max(1, h / 8);
        int srcW = src.getWidth();
        int srcH = src.getHeight();

        long sumR = 0, sumG = 0, sumB = 0, count = 0;
        for (int sy = y; sy < y + h; sy += stepY) {
            if (sy >= srcH) {
                break;
            }
            for (int sx = x; sx < x + w; sx += stepX) {
                if (sx >= srcW) {
                    break;
                }
                int argb = src.getRGB(sx, sy);
                sumR += (argb >> 16) & 0xff;
                sumG += (argb >> 8) & 0xff;
                sumB += argb & 0xff;
                count++;
            }
        }
        if (count == 0) {
            return new int[]{0, 0, 0};
        }
        return new int[]{(int) (sumR / count), (int) (sumG / count), (int) (sumB / count)};
    }

    /**
     * 计算整张图像的平均色（贴图加载时使用）
     *
     * @param img 图像
     * @return int[3] {r, g, b}
     */
    private int[] averageColorOfImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int stepX = Math.max(1, w / 16);
        int stepY = Math.max(1, h / 16);

        long sumR = 0, sumG = 0, sumB = 0, count = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int argb = img.getRGB(x, y);
                sumR += (argb >> 16) & 0xff;
                sumG += (argb >> 8) & 0xff;
                sumB += argb & 0xff;
                count++;
            }
        }
        if (count == 0) {
            return new int[]{0, 0, 0};
        }
        return new int[]{(int) (sumR / count), (int) (sumG / count), (int) (sumB / count)};
    }

    /**
     * 在贴图列表中找出平均色与目标色最接近的一张
     *
     * @param tiles 贴图列表
     * @param r     目标红色分量
     * @param g     目标绿色分量
     * @param b     目标蓝色分量
     * @return 最接近的贴图
     */
    private TileRecord closestByColor(List<TileRecord> tiles, int r, int g, int b) {
        TileRecord best = tiles.get(0);
        int bestDist = Integer.MAX_VALUE;
        for (TileRecord t : tiles) {
            int dr = r - t.avgR;
            int dg = g - t.avgG;
            int db = b - t.avgB;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = t;
            }
        }
        return best;
    }

    /**
     * 清除贴图缓存（更换目录或扩展名后调用，强制下次重新加载）
     *
     * @return 当前实例（支持链式）
     */
    public MosaicArtImageFilter clearCache() {
        this.loadedTiles = null;
        return this;
    }
}
