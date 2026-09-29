package com.chua.cad.support.provider;

import com.chua.cad.support.dxf.DxfDocument;
import com.chua.cad.support.dxf.DxfParser;
import com.chua.cad.support.dxf.DxfSvgRenderer;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * DXF 预览提供器：纯 Java 解析 + SVG 渲染，零外部依赖。
 *
 * <p>DXF 是 Autodesk 公开的文本交换格式，图元几何可以被完整解析，因此
 * 本模块能给出<b>真实图形</b>，而不是信息卡。这是与 DWG 的本质差别。</p>
 *
 * <p>输出为内联 SVG：矢量、可无损放大、描边色走 {@code currentColor}，
 * 前端 koi 暗色模式只需改容器 color 即可自动适配。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-dxf")
public class DxfPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的 DXF 扩展名（小写）
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of("dxf");

    /**
     * 渲染基准宽度（像素）。SVG 用 viewBox 自适应，这里只影响初始渲染尺寸
     */
    private static final int BASE_WIDTH = 1200;

    /**
     * 判断是否支持 DXF 预览
     *
     * @param ext  文件扩展名
     * @param mime MIME 类型（忽略）
     * @return true 表示支持
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    /**
     * 解析并渲染 DXF
     *
     * @param content DXF 字节
     * @param ext     文件扩展名
     * @param mime    MIME 类型（忽略）
     * @return 预览结果（内联 SVG）
     * @throws IOException 解析失败
     */
    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        DxfDocument doc = DxfParser.create().parse(content);
        String svg = DxfSvgRenderer.create().render(doc, BASE_WIDTH);
        return PreviewResult.builder()
                .htmlContent(svg)
                .build();
    }

    /**
     * 暴露解析结果供 HTTP 层复用（走 JSON 通道时避免重复解析）
     *
     * @param content DXF 字节
     * @return 解析结果
     * @throws IOException 解析失败
     */
    public DxfDocument parse(byte[] content) throws IOException {
        return DxfParser.create().parse(content);
    }

    /**
     * 渲染为 SVG 字符串
     *
     * @param doc 解析结果
     * @return SVG
     */
    public String render(DxfDocument doc) {
        return DxfSvgRenderer.create().render(doc, BASE_WIDTH);
    }
}
