package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.util.Base64;
import java.util.Set;

/**
 * 办公室文档预览提供器，支持 Excel / Word / powerpoint 及其模板格式的在线预览。
 *
 * <p>SPI 类型：{@code preview-univer}。表格走 LuckyExcel + 开源 Univer 表格预设渲染；
 * Word 文档走 Mammoth 将 docx 转为语义化 HTML 渲染。</p>
 *
 * <p>说明：高版本 Univer（0.25.x）已将 docx/xlsx 导入能力迁移至商业 Pro 包，
 * 开源 UMD 不再提供 {@code importDOCXToSnapshotAsync}，因此 Word 预览改用
 * 完全开源的 Mammoth.js，保证内网离线且无授权风险。</p>
 *
 * <p>前端依赖由宿主服务从同源路径 {@code /preview-vendor/} 提供，
 * 不依赖公网 CDN，保证内网部署可用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-univer")
public class UniverPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的 办公室 扩展名（小写）
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of(
            // Excel
            "xlsx", "xls", "xlsb", "xlt", "xltx", "xltm", "xlam", "xlsxml",
            // Word
            "docx", "doc", "dotx", "dotm",
            // powerpoint
            "pptx", "ppt", "potx", "potm"
    );

    /**
     * 表格类扩展名（需要 LuckyExcel 解析）
     */
    private static final Set<String> SHEET_EXTS = Set.of(
            "xlsx", "xls", "xlsb", "xlt", "xltx", "xltm", "xlam", "xlsxml"
    );

    /**
     * 文档类扩展名（Mammoth 仅支持 OOXML 格式，旧版二进制 doc 无法解析）
     */
    private static final Set<String> DOC_EXTS = Set.of(
            "docx", "dotx"
    );

    /**
     * Univer 本地资源根路径（由宿主服务以 classpath:/static/preview-vendor 同源提供）
     */
    private static final String UNIVER_BASE = "/preview-vendor/univer/";

    /**
     * Mammoth 本地资源根路径
     */
    private static final String MAMMOTH_BASE = "/preview-vendor/mammoth/";

    /**
     * 表格核心样式表
     */
    private static final String SHEETS_CORE_CSS = UNIVER_BASE + "sheets-core.css";

    /**
     * React 18 UMD（Univer UMD 内置 polyfill 会立即校验全局 React，必须最先加载）
     */
    private static final String REACT_JS = UNIVER_BASE + "react.production.min.js";

    /**
     * ReactDOM 18 UMD
     */
    private static final String REACT_DOM_JS = UNIVER_BASE + "react-dom.production.min.js";

    /**
     * RxJS UMD（提供全局 rxjs 及 rxjs.operators，必须早于 Univer 主包）
     */
    private static final String RXJS_JS = UNIVER_BASE + "rxjs.umd.min.js";

    /**
     * Univer 协议层 UMD（全局 UniverProtocol）
     */
    private static final String PROTOCOL_JS = UNIVER_BASE + "protocol.umd.js";

    /**
     * Univer 主题包 UMD（全局 UniverThemes）
     */
    private static final String THEMES_JS = UNIVER_BASE + "themes.umd.js";

    /**
     * Univer 预设主包（UMD）
     */
    private static final String PRESETS_JS = UNIVER_BASE + "presets.umd.js";

    /**
     * 表格核心包（UMD）
     */
    private static final String SHEETS_CORE_JS = UNIVER_BASE + "sheets-core.umd.js";

    /**
     * 表格中文语言包（UMD）
     */
    private static final String SHEETS_CORE_LOCALE_JS = UNIVER_BASE + "sheets-core.zh-CN.js";

    /**
     * LuckyExcel 解析包（xlsx 转 Univer 快照）
     */
    private static final String LUCKYEXCEL_JS = UNIVER_BASE + "luckyexcel.umd.min.js";

    /**
     * Mammoth 浏览器包（docx 转 HTML，全局 mammoth）
     */
    private static final String MAMMOTH_JS = MAMMOTH_BASE + "mammoth.browser.min.js";

    /**
     * @param ext  文件扩展名
     * @param mime MIME 类型（当前忽略）
     * @return true 表示支持预览
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase());
    }

    /**
     * 执行预览转换。
     *
     * @param content 文件原始字节
     * @param ext     文件扩展名（小写）
     * @param mime    MIME 类型（当前忽略）
     * @return 预览结果
     */
    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) {
        String b64 = Base64.getEncoder().encodeToString(content);
        String type = ext.toLowerCase();
        boolean isSheet = SHEET_EXTS.contains(type);
        boolean isDoc = DOC_EXTS.contains(type);

        String html = "<div id=\"app\" style=\"min-height:100vh;width:100%\"></div>";

        if (isDoc) {
            return buildDocPreview(b64, html);
        }
        if (isSheet) {
            return buildSheetPreview(b64, type, html);
        }
        return buildSlidesPlaceholder(html);
    }

    /**
     * 构建 Word 文档预览（Mammoth docx 转 HTML）。
     *
     * @param b64  文件字节的 Base64
     * @param html 容器 HTML
     * @return 预览结果
     */
    private PreviewResult buildDocPreview(String b64, String html) {
        String css = "html,body{margin:0;padding:0;background:#f0f2f5}"
                + "#app{padding:24px}"
                + ".docx-page{background:#fff;max-width:820px;margin:0 auto;padding:48px 56px;"
                + "box-shadow:0 2px 8px rgba(0,0,0,.12);font-family:'Times New Roman',SimSun,serif;"
                + "font-size:14px;line-height:1.7;color:#333;word-break:break-word}"
                + ".docx-page table{border-collapse:collapse}"
                + ".docx-page table td,.docx-page table th{border:1px solid #999;padding:4px 8px}"
                + ".docx-page img{max-width:100%}";
        String script = buildDocScript(b64);
        return PreviewResult.builder()
                .htmlContent(html)
                .embeddedCss(css)
                .jsUrls(new String[]{MAMMOTH_JS})
                .embeddedJs(script)
                .build();
    }

    /**
     * 构建 Word 文档初始化脚本。
     *
     * @param b64 文件字节的 Base64
     * @return JavaScript 脚本
     */
    private String buildDocScript(String b64) {
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){");
        sb.append("var B64='").append(b64).append("';");
        sb.append("function b64ToBytes(b){var a=atob(b),l=a.length,bs=new Uint8Array(l);");
        sb.append("for(var i=0;i<l;i++){bs[i]=a.charCodeAt(i)}return bs}");
        sb.append("function init(){");
        sb.append("var app=document.getElementById('app');");
        sb.append("mammoth.convertToHtml({arrayBuffer:b64ToBytes(B64).buffer}).then(function(r){");
        sb.append("var v=r.value&&r.value.trim();");
        sb.append("app.innerHTML='<div class=\"docx-page\">'+(v||'<p>文档内容为空</p>')+'</div>';");
        sb.append("}).catch(function(e){");
        sb.append("app.innerHTML='<p style=\"padding:40px;color:#c00\">文档加载失败: '+(e&&e.message||e)+'</p>';});}");
        sb.append("if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',init)}");
        sb.append("else{init()}})();");
        return sb.toString();
    }

    /**
     * 构建表格预览（LuckyExcel 解析 + Univer 表格预设渲染）。
     *
     * @param b64  文件字节的 Base64
     * @param type 文件扩展名
     * @param html 容器 HTML
     * @return 预览结果
     */
    private PreviewResult buildSheetPreview(String b64, String type, String html) {
        String css = "html,body,#app{margin:0;padding:0;height:100%;width:100%;overflow:hidden}";
        String script = buildSheetScript(b64, type);
        return PreviewResult.builder()
                .htmlContent(html)
                .embeddedCss(css)
                .cssUrls(new String[]{SHEETS_CORE_CSS})
                .jsUrls(new String[]{
                        // React 必须最先加载，Univer UMD 内置 polyfill 会立即校验全局 React/ReactDOM
                        REACT_JS,
                        REACT_DOM_JS,
                        // Univer 核心外部依赖（rxjs/protocol/themes），必须早于 Univer 主包
                        RXJS_JS,
                        PROTOCOL_JS,
                        THEMES_JS,
                        PRESETS_JS,
                        SHEETS_CORE_JS,
                        SHEETS_CORE_LOCALE_JS,
                        LUCKYEXCEL_JS
                })
                .embeddedJs(script)
                .build();
    }

    /**
     * 构建表格初始化脚本。
     *
     * @param b64  文件字节的 Base64
     * @param type 文件扩展名
     * @return JavaScript 脚本
     */
    private String buildSheetScript(String b64, String type) {
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){");
        sb.append("var B64='").append(b64).append("';");
        sb.append("var EXT='").append(type).append("';");
        sb.append("function b64ToFile(b,n,m){var a=atob(b),l=a.length,bs=new Uint8Array(l);");
        sb.append("for(var i=0;i<l;i++){bs[i]=a.charCodeAt(i)}return new File([bs],n,{type:m})}");
        sb.append("function init(){");
        sb.append("var f=b64ToFile(B64,'file.'+EXT,");
        sb.append("'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');");
        sb.append("LuckyExcel.transformExcelToUniver(f,function(j){");
        // 新版 LuckyExcel 输出 Univer IWorkbookData：sheets 为按 sheetId 键控的对象，sheetOrder 为有序 id 数组
        sb.append("var sheetCount=j&&j.sheets?Object.keys(j.sheets).length:0;");
        sb.append("if(sheetCount>0){");
        sb.append("var r=UniverPresets.createUniver({locale:UniverCore.LocaleType.ZH_CN,");
        sb.append("locales:{[UniverCore.LocaleType.ZH_CN]:UniverCore.mergeLocales(UniverPresetSheetsCoreZhCN)},");
        sb.append("presets:[UniverPresetSheetsCore.UniverSheetsCorePreset({container:'app'})]});");
        sb.append("r.univerAPI.createWorkbook(j);}else{");
        sb.append("document.getElementById('app').innerHTML=");
        // 括号顺序：先关闭 else 块、成功回调函数体，再以逗号声明 LuckyExcel 的失败回调，最后由统一右括号闭合方法调用
        sb.append("'<p style=\"padding:40px;color:#666\">无法解析文件</p>';}},function(e){");
        sb.append("document.getElementById('app').innerHTML=");
        sb.append("'<p style=\"padding:40px;color:#c00\">加载失败: '+(e&&e.message||e)+'</p>';});}");
        sb.append("if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',init)}");
        sb.append("else{init()}})();");
        return sb.toString();
    }

    /**
     * 构建幻灯片不支持预览的占位结果。
     *
     * @param html 容器 HTML
     * @return 预览结果
     */
    private PreviewResult buildSlidesPlaceholder(String html) {
        String script = "(function(){var app=document.getElementById('app');"
                + "app.innerHTML='<div style=\"padding:40px;text-align:center;color:#666;font-family:sans-serif\">"
                + "暂不支持该格式在线预览，请下载后查看</div>';})();";
        return PreviewResult.builder()
                .htmlContent(html)
                .embeddedJs(script)
                .build();
    }
}
