package com.chua.cad.support.dxf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DXF 文件解析结果。
 *
 * <p>DXF 是纯文本的分组码（group code）格式，结构公开、跨版本稳定，
 * 因此纯 Java 即可完整解析图元几何，<b>不依赖任何外部 CAD 库</b>。</p>
 *
 * @param header       文件头（版本、单位等）
 * @param entities     图元列表（顺序即文件中的绘制顺序）
 * @param layers       图层名 → 该图层的图元数量
 * @param minX         图形范围左边界
 * @param minY         图形范围下边界
 * @param maxX         图形范围右边界
 * @param maxY         图形范围上边界
 * @param unsupportedEnts 解析器不认识的实体类型（已跳过）
 * @author CH
 * @since 4.0.0.42
 */
public record DxfDocument(
        DxfHeader header,
        List<DxfEntity> entities,
        Map<String, Integer> layers,
        double minX,
        double minY,
        double maxX,
        double maxY,
        List<String> unsupportedEnts) {

    /**
     * 紧凑构造器：图元清单与图层表做防御性拷贝，
     * 避免调用方拿到内部可变集合后污染解析结果。
     *
     * @param header          文件头
     * @param entities        图元列表
     * @param layers          图层统计
     * @param minX            左边界
     * @param minY            下边界
     * @param maxX            右边界
     * @param maxY            上边界
     * @param unsupportedEnts 不支持的实体类型
     */
    public DxfDocument {
        entities = entities == null ? List.of() : List.copyOf(entities);
        layers = layers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(layers));
        unsupportedEnts = unsupportedEnts == null
                ? List.of()
                : List.copyOf(new ArrayList<>(unsupportedEnts));
    }

    /**
     * 图形范围是否有效（至少有一个图元且范围非退化为一点）
     *
     * @return true 表示范围可用于视图适配
     */
    public boolean hasValidBounds() {
        return !entities.isEmpty() && (maxX > minX || maxY > minY);
    }

    /**
     * 图形宽度
     *
     * @return 宽度
     */
    public double width() {
        return maxX - minX;
    }

    /**
     * 图形高度
     *
     * @return 高度
     */
    public double height() {
        return maxY - minY;
    }
}
