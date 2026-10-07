package com.chua.cad.support.dxf;
import com.chua.common.support.collection.wrap.Wraps;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DXF → SVG 渲染器（纯 Java，零外部依赖）。
 *
 * <p>把 {@link DxfDocument} 渲染成单张 SVG。输出的是<b>矢量</b>图形，
 * 前端可无限放大不失真，也便于叠加图层高亮、测量线等交互。</p>
 *
 * <p><b>坐标系</b>：DXF 的 Y 轴<b>向上</b>，SVG 的 Y 轴<b>向下</b>。
 * 渲染时统一用一个 {@code transform="translate(...) scale(1,-1)"} 把 DXF
 * 空间翻到 SVG 空间，这样图元坐标可以原样输出，不必逐点取负——逐点取负
 * 在圆弧的起止角上极易出错。</p>
 *
 * <p><b>配色</b>：刻意使用中性描边（#303133 一类的深灰），不引入品牌色。
 * 图纸背景与线条对比度由 koi 主题的暗色模式在<b>前端</b>决定，
 * 服务端只负责几何——这样暗色模式切换时不需要服务端配合。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DxfSvgRenderer {

    /**
     * 画布边距（图纸单位），避免图形贴边
     */
    private static final double PADDING_RATIO = 0.04;

    /**
     * 线宽相对图形尺寸的比例（图纸单位的千分之一）
     *
     * <p>取图纸对角线的千分之一：无论图纸是 1 米见方还是 1000 米见方，
     * 线条在屏幕上都是同样的视觉粗细。</p>
     */
    private static final double STROKE_RATIO = 0.001;

    /**
     * 线宽上下限（图纸单位），防止极端图纸出现「线粗如墙」或「线细如发」
     */
    private static final double MIN_STROKE_UNITS = 0.01;

    /**
     * 线宽绝对上限（图纸单位）
     */
    private static final double MAX_STROKE_UNITS = 5;

    /**
     * 按图形尺寸计算线宽
     *
     * @param doc 解析结果
     * @return 线宽（图纸单位）
     */
    private double strokeWidth(DxfDocument doc) {
        double diagonal = Math.hypot(doc.width(), doc.height());
        if (diagonal <= 0) {
            return 1;
        }
        double w = diagonal * STROKE_RATIO;
        return Math.min(MAX_STROKE_UNITS, Math.max(MIN_STROKE_UNITS, w));
    }

    /**
     * 单个 SVG 内联 <text> 的最大字符数（超长文本会撑爆版面）
     */
    private static final int MAX_TEXT_CHARS = 200;

    /**
     * 圆弧路径点采样数：圆弧按多段折线逼近，越多越平滑
     */
    private static final int ARC_SEGMENTS = 48;

    /**
     * 渲染为内联 SVG。
     *
     * @param doc   解析结果
     * @param width 画布宽（像素）
     * @return SVG 字符串
     */
    public String render(DxfDocument doc, int width) {
        double w = doc.width();
        double h = doc.height();
        // 退化范围（无图元 / 单点）给一个默认视窗，避免除零产出 NaN 变换
        if (w <= 0 && h <= 0) {
            w = 100;
            h = 100;
        } else if (w <= 0) {
            w = Math.max(h, 1);
        } else if (h <= 0) {
            h = Math.max(w, 1);
        }
        double pad = Math.max(w, h) * PADDING_RATIO;
        double viewW = w + pad * 2;
        double viewH = h + pad * 2;
        // SVG 高度按图形宽高比换算，保证不变形
        int heightPx = Math.max(1, (int) Math.round(width * viewH / viewW));

        StringBuilder body = new StringBuilder();
        for (DxfEntity e : doc.entities()) {
            appendEntity(body, e);
        }
        if (body.isEmpty()) {
            // 提示文字放在外层（不做 Y 翻转），否则上下颠倒
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + fmt(viewW) + " "
                    + fmt(viewH) + "\" width=\"" + width + "\" height=\"" + heightPx + "\""
                    + " preserveAspectRatio=\"xMidYMid meet\" role=\"img\""
                    + " aria-label=\"CAD 图纸预览\">"
                    + "<text x=\"" + fmt(viewW / 2) + "\" y=\"" + fmt(viewH / 2)
                    + "\" text-anchor=\"middle\" dominant-baseline=\"middle\""
                    + " font-size=\"" + fmt(viewH / 20) + "\" fill=\"currentColor\""
                    + " fill-opacity=\"0.5\">该图纸没有可渲染的图元</text></svg>";
        }

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\""
                + fmt(-(doc.minX() - pad)) + " " + fmt(-(doc.maxY() + pad)) + " "
                + fmt(viewW) + " " + fmt(viewH) + "\""
                + " width=\"" + width + "\" height=\"" + heightPx + "\""
                + " preserveAspectRatio=\"xMidYMid meet\""
                + " role=\"img\" aria-label=\"CAD 图纸预览\">"
                // 描边色用 currentColor：koi 暗色模式只需改容器的 color，
                // SVG 本身不用重新渲染，服务端也就不需要知道前端主题
                //
                // 线宽按「图纸单位」给出，配合 viewBox 自适应缩放：一条 100 单位的
                // 直线在任何缩放比例下视觉粗细一致。这里<b>不能</b>用
                // vector-effect="non-scaling-stroke"——它会把线宽锁定为设备像素，
                // 而本 SVG 常被放大十几倍（1200px 画布 / 几十个图纸单位），
                // 结果线宽被按倍数放大，图纸变成一堆粗黑棒。
                + "<g transform=\"scale(1,-1)\" fill=\"none\" stroke=\"currentColor\""
                + " stroke-width=\"" + fmt(strokeWidth(doc)) + "\""
                + " stroke-linejoin=\"round\" stroke-linecap=\"round\">"
                + body
                + "</g></svg>";
    }

    /**
     * 追加单个图元的 SVG 片段
     *
     * @param sb     输出缓冲
     * @param entity 图元
     */
    private void appendEntity(StringBuilder sb, DxfEntity e) {
        switch (e.type()) {
            case "LINE" -> {
                sb.append("<line x1=\"").append(fmt(e.startX()))
                        .append("\" y1=\"").append(fmt(e.startY()))
                        .append("\" x2=\"").append(fmt(e.endX()))
                        .append("\" y2=\"").append(fmt(e.endY())).append("\"/>");
            }
            case "POINT" -> {
                sb.append("<circle cx=\"").append(fmt(e.startX()))
                        .append("\" cy=\"").append(fmt(e.startY()))
                        .append("\" r=\"").append(fmt(e.height() > 0 ? e.height() / 20 : 0.5))
                        .append("\" fill=\"currentColor\" stroke=\"none\"/>");
            }
            case "CIRCLE" -> {
                sb.append("<circle cx=\"").append(fmt(e.startX()))
                        .append("\" cy=\"").append(fmt(e.startY()))
                        .append("\" r=\"").append(fmt(e.radiusX())).append("\"/>");
            }
            case "ARC" -> appendArc(sb, e);
            case "ELLIPSE" -> appendEllipse(sb, e);
            case "LWPOLYLINE", "POLYLINE" -> appendPolyline(sb, e);
            case "TEXT", "MTEXT" -> appendText(sb, e);
            default -> {
                // 未支持类型不输出
            }
        }
    }

    /**
     * 追加圆弧：按多段折线逼近
     *
     * @param sb 输出缓冲
     * @param e  圆弧图元
     */
    private void appendArc(StringBuilder sb, DxfEntity e) {
        double r = e.radiusX();
        if (r <= 0) {
            return;
        }
        double start = e.startAngle();
        double end = e.endAngle();
        // DXF 圆弧逆时针 0→360 为整圆；若 end < start 表示跨过了 0 度，需补 360
        double sweep = end - start;
        if (sweep <= 0) {
            sweep += 360;
        }
        if (sweep >= 360) {
            sb.append("<circle cx=\"").append(fmt(e.startX()))
                    .append("\" cy=\"").append(fmt(e.startY()))
                    .append("\" r=\"").append(fmt(r)).append("\"/>");
            return;
        }
        int steps = Math.max(2, (int) Math.ceil(sweep / (360.0 / ARC_SEGMENTS)));
        StringBuilder path = new StringBuilder();
        for (int i = 0; i <= steps; i++) {
            double deg = start + sweep * i / steps;
            double rad = Math.toRadians(deg);
            double x = e.startX() + r * Math.cos(rad);
            double y = e.startY() + r * Math.sin(rad);
            path.append(i == 0 ? "M" : "L").append(fmt(x)).append(" ").append(fmt(y));
            if (i < steps) {
                path.append(" ");
            }
        }
        sb.append("<path d=\"").append(path).append("\"/>");
    }

    /**
     * 追加椭圆（含主轴旋转）
     *
     * @param sb 输出缓冲
     * @param e  椭圆图元
     */
    private void appendEllipse(StringBuilder sb, DxfEntity e) {
        double rx = e.radiusX();
        double ry = e.radiusY() > 0 ? e.radiusY() : rx;
        if (rx <= 0) {
            return;
        }
        double rot = e.rotation();
        // SVG 的 ry 方向与数学坐标相反，翻转后角度取负
        sb.append("<ellipse cx=\"").append(fmt(e.startX()))
                .append("\" cy=\"").append(fmt(e.startY()))
                .append("\" rx=\"").append(fmt(rx))
                .append("\" ry=\"").append(fmt(ry))
                .append("\" transform=\"rotate(").append(fmt(-rot)).append(' ')
                .append(fmt(e.startX())).append(' ').append(fmt(e.startY())).append(")\"/>");
    }

    /**
     * 追加折线 / 多段线
     *
     * @param sb 输出缓冲
     * @param e  折线图元
     */
    private void appendPolyline(StringBuilder sb, DxfEntity e) {
        List<double[]> pts = e.points();
        if (pts.size() < 2) {
            return;
        }
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < pts.size(); i++) {
            path.append(i == 0 ? "M" : "L").append(fmt(pts.get(i)[0]))
                    .append(" ").append(fmt(pts.get(i)[1]));
            if (i < pts.size() - 1) {
                path.append(" ");
            }
        }
        if (e.closed()) {
            path.append(" Z");
        }
        sb.append("<path d=\"").append(path).append("\"/>");
    }

    /**
     * 追加文字
     *
     * @param sb 输出缓冲
     * @param e  文字图元
     */
    private void appendText(StringBuilder sb, DxfEntity e) {
        String text = e.text();
        if (text == null || text.isBlank()) {
            return;
        }
        String safe = escapeXml(text);
        if (safe.length() > MAX_TEXT_CHARS) {
            safe = safe.substring(0, MAX_TEXT_CHARS) + "…";
        }
        // 用 transform 做 Y 翻转的补偿：外层 scale(1,-1) 会把文字上下颠倒，
        // 再对每个文字单独 scale(1,-1) 翻回来
        sb.append("<text transform=\"translate(").append(fmt(e.startX())).append(' ')
                .append(fmt(e.startY())).append(") scale(1,-1)\"")
                .append(" font-size=\"").append(fmt(e.height()))
                .append("\" fill=\"currentColor\" stroke=\"none\"")
                .append(" xml:space=\"preserve\">").append(safe).append("</text>");
    }

    /**
     * 数字格式化：保留最多 4 位小数，去掉多余的 0，减小 SVG 体积
     *
     * @param v 数值
     * @return 格式化文本
     */
    private String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e12) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ENGLISH, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * XML 转义（文字内容必须转义，否则 & < > 会破坏 SVG 结构）
     *
     * @param s 原始文本
     * @return 转义后文本
     */
    private String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * 统计各类图元数量（供前端信息条展示）
     *
     * @param doc 解析结果
     * @return 图元类型 → 数量
     */
    public Map<String, Integer> countByType(DxfDocument doc) {
        Map<String, Integer> counts = Wraps.linkedMap();
        List<String> order = Wraps.list(doc.entities().stream()
                .map(DxfEntity::type).distinct().toList());
        for (String type : order) {
            long n = doc.entities().stream().filter(e -> e.type().equals(type)).count();
            counts.put(type, (int) n);
        }
        return counts;
    }

    /**
     * 创建渲染器
     *
     * @return 渲染器实例
     */
    public static DxfSvgRenderer create() {
        return new DxfSvgRenderer();
    }

    /**
     * 私有构造，请改用 {@link #create()}
     */
    private DxfSvgRenderer() {
    }
}
