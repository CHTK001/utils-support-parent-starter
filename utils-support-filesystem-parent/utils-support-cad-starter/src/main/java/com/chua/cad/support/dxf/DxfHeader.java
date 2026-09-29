package com.chua.cad.support.dxf;

/**
 * DXF 文件头（{@code HEADER} 段中与预览相关的字段）。
 *
 * @param acadVersion    DXF 版本声明（{@code $ACADVER}）
 * @param unitsCode      单位代码（{@code $INSUNITS}）
 * @param unitsLabel     单位中文名称
 * @param extMinX        图纸范围左下 X
 * @param extMinY        图纸范围左下 Y
 * @param extMaxX        图纸范围右上 X
 * @param extMaxY        图纸范围右上 Y
 * @author CH
 * @since 4.0.0.42
 */
public record DxfHeader(
        String acadVersion,
        int unitsCode,
        String unitsLabel,
        double extMinX,
        double extMinY,
        double extMaxX,
        double extMaxY) {
}
