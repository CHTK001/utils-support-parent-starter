package com.chua.cad.support.dwg;

import java.util.Map;

/**
 * DWG 文件头信息。
 *
 * <p>只解析 DWG 头部可公开读取的字段，<b>不解析图元几何</b>。DWG 是 Autodesk
 * 私有二进制格式，其数据段（object map / handles / 图元）结构随版本变化且
 * 未经公开文档完整描述，纯 Java 无法可靠还原。本记录的价值在于回答
 * 「这个文件是什么、什么版本、多大范围、多少图层、单位是什么」，
 * 而不是「图画的是什么」。</p>
 *
 * <p>可读取的字段与版本的关系（依据 Autodesk 官方
 * <a href="https://www.autodesk.com/support/technical/article/caas/sfdcarticles/sfdcarticles/drawing-version-codes-for-autocad.html">
 * 图纸版本代码文档</a>）：</p>
 * <ul>
 *   <li>前 6 字节恒为版本代码（{@code AC1015} 等），所有版本一致</li>
 *   <li>第 7 字节为「maintenance version」，仅部分版本有意义</li>
 *   <li>图形范围（extents）需按版本定位，且部分版本存于压缩数据段内，
 *       本实现仅在可明确定位时填充，否则留空——<b>不猜测</b></li>
 * </ul>
 *
 * @param versionCode   版本代码原文（如 {@code AC1015}）；无法识别时为空串
 * @param versionLabel  版本可读名称（如 {@code AutoCAD 2000/2000i/2002}）
 * @param maintenanceVersion 第 7 字节的维护版本号；无意义时为 -1
 * @param acadRelease   AutoCAD 发行号（如 {@code R15}）；无法确定时为空串
 * @param fileSize      文件字节数
 * @param estimated     是否为「估算」结果（true 表示字段不完整，仅头部可信）
 * @author CH
 * @since 4.0.0.42
 */
public record DwgHeaderInfo(
        String versionCode,
        String versionLabel,
        int maintenanceVersion,
        String acadRelease,
        long fileSize,
        boolean estimated) {
}
