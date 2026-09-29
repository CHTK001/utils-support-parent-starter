package com.chua.cad.support.dxf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DXF 图元（仅保留可渲染的几何信息）。
 *
 * <p>用扁平的可选字段承载各实体类型的差异，而非为每种实体建一个子类：
 * DXF 有上百种实体，真正需要几何绘制的只有十余种，平铺结构配合
 * {@link #type()} 分派渲染，代码量与可读性都更划算。</p>
 *
 * <p>各字段的含义随 {@link #type} 变化：</p>
 * <ul>
 *   <li>{@code LINE}：startX/startY → endX/endY</li>
 *   <li>{@code LWPOLYLINE} / {@code POLYLINE}：points 顶点序列，closed 决定是否闭合</li>
 *   <li>{@code CIRCLE}：centerX/centerY + radiusX</li>
 *   <li>{@code ARC}：centerX/centerY + radiusX + startAngle/endAngle（度）</li>
 *   <li>{@code ELLIPSE}：centerX/centerY + radiusX/radiusY + rotation</li>
 *   <li>{@code POINT}：startX/startY</li>
 *   <li>{@code TEXT} / {@code MTEXT}：startX/startY + height + text</li>
 * </ul>
 *
 * @param type       实体类型（大写，如 {@code LINE}）
 * @param layer      所属图层名
 * @param startX     起点/中心 X
 * @param startY     起点/中心 Y
 * @param endX       终点 X（LINE）或半径 Y（ELLIPSE）
 * @param endY       终点 Y
 * @param radiusX    半径 X（CIRCLE / ARC / ELLIPSE）
 * @param radiusY    半径 Y（ELLIPSE）
 * @param startAngle 起始角（度，ARC）
 * @param endAngle   终止角（度，ARC）
 * @param height     文字高度（TEXT / MTEXT）
 * @param rotation   旋转角（度，ELLIPSE / TEXT）
 * @param closed     是否闭合（LWPOLYLINE / POLYLINE）
 * @param points     顶点列表（LWPOLYLINE / POLYLINE），元素为 {@code double[]{x, y}}
 * @param text       文本内容（TEXT / MTEXT）
 * @author CH
 * @since 4.0.0.42
 */
public record DxfEntity(
        String type,
        String layer,
        double startX,
        double startY,
        double endX,
        double endY,
        double radiusX,
        double radiusY,
        double startAngle,
        double endAngle,
        double height,
        double rotation,
        boolean closed,
        List<double[]> points,
        String text) {

    /**
     * 构造无属性实体（POINT 等只需要坐标的场合）
     *
     * @param type    实体类型
     * @param layer   图层
     * @param startX  X 坐标
     * @param startY  Y 坐标
     * @return 实体
     */
    public static DxfEntity point(String type, String layer, double startX, double startY) {
        return new DxfEntity(type, layer, startX, startY, 0, 0, 0, 0, 0, 0, 0, 0, false,
                List.of(), "");
    }

    /**
     * 构造线段实体
     *
     * @param layer  图层
     * @param startX 起点 X
     * @param startY 起点 Y
     * @param endX   终点 X
     * @param endY   终点 Y
     * @return 实体
     */
    public static DxfEntity line(String layer, double startX, double startY, double endX, double endY) {
        return new DxfEntity("LINE", layer, startX, startY, endX, endY, 0, 0, 0, 0, 0, 0, false,
                List.of(), "");
    }

    /**
     * 构造顶点序列实体（LWPOLYLINE / POLYLINE）
     *
     * @param type   实体类型
     * @param layer  图层
     * @param points 顶点列表，每个元素为 {@code double[]{x, y}}
     * @param closed 是否闭合
     * @return 实体
     */
    public static DxfEntity polyline(String type, String layer, List<double[]> points, boolean closed) {
        List<double[]> safe = points == null ? List.of() : List.copyOf(points);
        return new DxfEntity(type, layer, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, closed, safe, "");
    }

    /**
     * 构造圆 / 圆弧实体
     *
     * @param type    实体类型（CIRCLE / ARC）
     * @param layer   图层
     * @param centerX 圆心 X
     * @param centerY 圆心 Y
     * @param radius  半径
     * @param startAngle 起始角（度），CIRCLE 传 0
     * @param endAngle   终止角（度），CIRCLE 传 360
     * @return 实体
     */
    public static DxfEntity arc(String type, String layer, double centerX, double centerY,
                                double radius, double startAngle, double endAngle) {
        return new DxfEntity(type, layer, centerX, centerY, 0, 0, radius, 0, startAngle, endAngle,
                0, 0, false, List.of(), "");
    }

    /**
     * 构造文字实体
     *
     * @param type   实体类型（TEXT / MTEXT）
     * @param layer  图层
     * @param x      插入点 X
     * @param y      插入点 Y
     * @param height 字高
     * @param text   文本内容
     * @return 实体
     */
    public static DxfEntity text(String type, String layer, double x, double y,
                                 double height, String text) {
        return new DxfEntity(type, layer, x, y, 0, 0, 0, 0, 0, 0, height, 0, false, List.of(), text);
    }

    /**
     * 紧凑构造器：顶点列表做防御性拷贝，文本为 null 时归一为空串。
     *
     * @param type        实体类型
     * @param layer       图层名
     * @param startX      起点/中心 X
     * @param startY      起点/中心 Y
     * @param endX        终点 X
     * @param endY        终点 Y
     * @param radiusX     半径 X
     * @param radiusY     半径 Y
     * @param startAngle  起始角
     * @param endAngle    终止角
     * @param height      文字高度
     * @param rotation    旋转角
     * @param closed      是否闭合
     * @param points      顶点列表
     * @param text        文本内容
     */
    public DxfEntity {
        layer = layer == null || layer.isBlank() ? "0" : layer;
        points = points == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(points));
        text = text == null ? "" : text;
    }
}
