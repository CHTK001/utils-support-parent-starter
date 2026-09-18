package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * 圆形贴图马赛克滤镜（MosaicArt 变体）
 * <p>
 * 与 {@link MosaicArtImageFilter} 的拼接逻辑相同（网格采样平均色、
 * 按 COLOR_MATCH 从贴图目录里挑色调最接近的贴图绘制），
 * 但所有贴图在绘制前被裁剪成圆形（带可选描边），产生"圆贴马赛克"效果。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认圆贴马赛克
 * BufferedImage out = new MosaicArtCircularImageFilter()
 *         .setTilesDir("D:/ch/image/tiles")
 *         .setTileSize(64)
 *         .setTileDrawSize(72)
 *         .converter(src);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>tilesDir</b>：贴图目录（必填，目录下的图片文件会被当作贴图）</li>
 *   <li><b>tileSize</b>（默认 64）：网格格子像素宽，N×N 网格 = 图宽 / tileSize</li>
 *   <li><b>tileDrawSize</b>（默认 0 = 与 tileSize 同）：贴图绘制尺寸（可放大圆贴）</li>
 *   <li><b>extensions</b>（默认 jpg,jpeg,png,bmp）：贴图扩展名白名单</li>
 *   <li><b>borderWidth</b>（默认 0）：圆形描边宽度（像素）</li>
 *   <li><b>borderColor</b>（默认 #FFFFFF）：描边颜色</li>
 *   <li><b>gap</b>（默认 0.1，范围 0.0-0.5）：圆贴相对格子的收缩比（越大圆越小）</li>
 *   <li><b>seed</b>（默认 0 = 颜色匹配，&gt;0 时同色随机选一）：颜色匹配平局时的随机种子</li>
 *   <li><b>background</b>（默认 null = 透明）：未覆盖区域填充色（十六进制字符串）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_ARGB（保留透明），圆贴之间的间隙按 background 填充</li>
 *   <li>贴图目录为空时输出背景色（或透明）</li>
 *   <li>本滤镜会预加载所有贴图，大图目录注意内存占用</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/18
 */
@EqualsAndHashCode(callSuper = true)
@Setter
@Spi("mosaic-art-circular")
@SpiDescribe("圆形贴图马赛克滤镜")
@Accessors(chain = true)
public class MosaicArtCircularImageFilter extends AbstractImageFilter {

    /**
    * 贴图目录
    */
    private String tilesDir;

    /**
    * 网格格子像素宽，默认 64
    */
    private int tileSize = 64;

    /**
    * 贴图绘制尺寸，默认 0（= tileSize）
    */
    private int tileDrawSize = 0;

    /**
    * 贴图扩展名白名单
    */
    private String extensions = "jpg,jpeg,png,bmp";

    /**
    * 圆形描边宽度（像素），默认 0
    */
    private int borderWidth = 0;

    /**
    * 描边颜色，默认 #FFFFFF
    */
    private String borderColor = "#FFFFFF";

    /**
    * 圆贴收缩比 (0.0-0.5)，默认 0.1
    */
    private double gap = 0.1;

    /**
    * 背景色（十六进制，null = 透明），默认 null
    */
    private String background;

    /**
    * 贴图缓存
    */
    private final Map<Path, BufferedImage> tileCache = new LinkedHashMap<>();

    /**
    * 执行圆形贴图马赛克滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 圆贴马赛克图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        // 背景
        if (background != null) {
            Graphics2D bg = out.createGraphics();
            bg.setColor(parseColor(background));
            bg.fillRect(0, 0, w, h);
            bg.dispose();
        }

        // 加载贴图
        loadTiles();
        if (tileCache.isEmpty()) {
            return out;
        }

        int cs = Math.max(1, tileSize);
        int drawSize = tileDrawSize > 0 ? tileDrawSize : cs;
        int cols = (int) Math.ceil((double) w / cs);
        int rows = (int) Math.ceil((double) h / cs);
        int[] srcPixels = src.getRGB(0, 0, w, h, null, 0, w);

        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        java.awt.BasicStroke stroke = borderWidth > 0
                ? new java.awt.BasicStroke((float) Math.max(1, borderWidth), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                : null;

        for (int ry = 0; ry < rows; ry++) {
            for (int cx = 0; cx < cols; cx++) {
                // 格子中心
                int gx = cx * cs + cs / 2;
                int gy = ry * cs + cs / 2;
                // 采样平均色
                int[] avg = averageColor(srcPixels, w, h, gx, gy, cs);
                // 颜色匹配
                BufferedImage tile = pickTile(avg[0], avg[1], avg[2]);
                if (tile == null) {
                    continue;
                }
                // 圆贴绘制尺寸（含 gap 收缩）
                int circleSize = Math.max(4, (int) (drawSize * (1 - gap)));
                int dx = cx * cs + (cs - circleSize) / 2;
                int dy = ry * cs + (cs - circleSize) / 2;
                // 先裁剪成圆（带描边）
                BufferedImage circle = makeCircle(tile, circleSize);
                if (stroke != null) {
                    g.setStroke(stroke);
                    g.setColor(parseColor(borderColor));
                    g.drawOval(dx, dy, circleSize - 1, circleSize - 1);
                }
                g.drawImage(circle, dx, dy, null);
            }
        }

        g.dispose();
        return out;
    }

    /**
    * 把贴图缩放后裁剪成圆形（透明底 + 抗锯齿）
    *
    * @param tile 原贴图
    * @param size 圆形直径（像素）
    * @return 圆形贴图
    */
    private BufferedImage makeCircle(BufferedImage tile, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 缩放铺满正方形（保持长边对齐，居中裁切）
        int tw = tile.getWidth();
        int th = tile.getHeight();
        int side = Math.min(tw, th);
        int sx = (tw - side) / 2;
        int sy = (th - side) / 2;
        g.drawImage(tile, 0, 0, size, size, sx, sy, sx + side, sy + side, null);
        g.setComposite(java.awt.AlphaComposite.DstIn);
        g.setColor(java.awt.Color.WHITE);
        g.fillOval(0, 0, size - 1, size - 1);
        g.dispose();
        return scaled;
    }

    /**
    * 加载贴图目录
    */
    private void loadTiles() {
        if (tilesDir == null || tilesDir.isEmpty()) {
            return;
        }
        Path dir = Paths.get(tilesDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        java.util.Set<String> exts = splitExtensions(extensions);
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                        String fn = p.getFileName().toString().toLowerCase();
                        int dot = fn.lastIndexOf('.');
                        return dot >= 0 && exts.contains(fn.substring(dot + 1));
                    })
                    .forEach(p -> {
                        try {
                            tileCache.put(p, ImageIORead(p));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    /**
    * 读取图片
    * @param p 方法入参 p
    * @return BufferedImage 对象
    */
    private BufferedImage ImageIORead(Path p) throws IOException {
        return javax.imageio.ImageIO.read(p.toFile());
    }

    /**
    * 颜色匹配选贴图
    * @param r 方法入参 r
    * @param g 方法入参 g
    * @param b 方法入参 b
    * @return BufferedImage 对象
    */
    private BufferedImage pickTile(int r, int g, int b) {
        BufferedImage best = null;
        int bestDist = Integer.MAX_VALUE;
        for (BufferedImage t : tileCache.values()) {
            int[] avg = averageOf(t);
            int dr = r - avg[0];
            int dg = g - avg[1];
            int db = b - avg[2];
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = t;
            }
        }
        return best;
    }

    /**
    * 贴图平均色（缓存）
    * @param t 方法入参 t
    * @return 结果值
    */
    private int[] averageOf(BufferedImage t) {
        int[] px = t.getRGB(0, 0, t.getWidth(), t.getHeight(), null, 0, t.getWidth());
        long sr = 0, sg = 0, sb = 0;
        for (int p : px) {
            sr += (p >> 16) & 0xff;
            sg += (p >> 8) & 0xff;
            sb += p & 0xff;
        }
        int n = Math.max(1, px.length);
        return new int[]{(int) (sr / n), (int) (sg / n), (int) (sb / n)};
    }

    /**
    * 从源图采样格子平均色
    * @param pixels 方法入参 pixels
    * @param w 方法入参 w
    * @param h 方法入参 h
    * @param cx 方法入参 cx
    * @param cy 方法入参 cy
    * @param cs 方法入参 cs
    * @return 结果值
    */
    private int[] averageColor(int[] pixels, int w, int h, int cx, int cy, int cs) {
        int x0 = Math.max(0, cx - cs / 2);
        int y0 = Math.max(0, cy - cs / 2);
        int x1 = Math.min(w, cx + cs / 2);
        int y1 = Math.min(h, cy + cs / 2);
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = pixels[y * w + x];
                sr += (p >> 16) & 0xff;
                sg += (p >> 8) & 0xff;
                sb += p & 0xff;
                n++;
            }
        }
        if (n == 0) {
            return new int[]{0, 0, 0};
        }
        return new int[]{(int) (sr / n), (int) (sg / n), (int) (sb / n)};
    }

    /**
    * 扩展名白名单切分
    * @param exts 方法入参 exts
    * @return 结果值
    */
    private java.util.Set<String> splitExtensions(String exts) {
        if (exts == null || exts.isEmpty()) {
            return java.util.Set.of("jpg", "jpeg", "png", "bmp");
        }
        return java.util.Arrays.stream(exts.toLowerCase().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
    * 解析十六进制颜色
    * @param hex 方法入参 hex
    * @return 结果值
    */
    private java.awt.Color parseColor(String hex) {
        try {
            return java.awt.Color.decode(hex);
        } catch (RuntimeException e) {
            return java.awt.Color.WHITE;
        }
    }
}
