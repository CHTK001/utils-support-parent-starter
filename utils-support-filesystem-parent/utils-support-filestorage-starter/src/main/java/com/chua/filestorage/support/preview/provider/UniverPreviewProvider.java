package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.util.Base64;
import java.util.Set;

/**
 * Univer Office 预览提供器，支持 Excel / Word / PowerPoint 及其模板格式的在线预览。
 * <p>SPI 类型：{@code preview-univer}。表格走 LuckyExcel，文档走 Univer importDOCXToSnapshotAsync。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-univer")
public class UniverPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的 Office 扩展名（小写）
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of(
            // Excel
            "xlsx", "xls", "xlsb", "xlt", "xltx", "xltm", "xlam", "xlsxml",
            // Word
            "docx", "doc", "dotx", "dotm",
            // PowerPoint
            "pptx", "ppt", "potx", "potm"
    );

    /**
     * 表格类扩展名（需要 LuckyExcel 解析）
     */
    private static final Set<String> SHEET_EXTS = Set.of(
            "xlsx", "xls", "xlsb", "xlt", "xltx", "xltm", "xlam", "xlsxml"
    );

    /**
     * 文档类扩展名
     */
    private static final Set<String> DOC_EXTS = Set.of(
            "docx", "doc", "dotx", "dotm"
    );

    /**
     * @param ext  文件扩展名
     * @param mime MIME 类型（当前忽略）
     * @return true 表示支持预览
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase());
    }

    @Override
    /**
     * Preview
     * @param content content
     * @param ext ext
     * @param mime mime
     */
    public PreviewResult preview(byte[] content, String ext, String mime) {
        String b64 = Base64.getEncoder().encodeToString(content);
        String type = ext.toLowerCase();
        boolean isSheet = SHEET_EXTS.contains(type);
        boolean isDoc = DOC_EXTS.contains(type);

        String html = "<div id=\"app\" style=\"height:100vh;width:100%\"></div>";
        String css = "html,body,#app{margin:0;padding:0;height:100%;width:100%;overflow:hidden}";

        String initScript = buildScript(b64, type, isSheet, isDoc);

        return PreviewResult.builder()
                .htmlContent(html)
                .embeddedCss(css)
                .cssUrls(new String[]{
                        "https://unpkg.com/@univerjs/preset-sheets-core/lib/index.css",
                        "https://unpkg.com/@univerjs/preset-docs-core/lib/index.css"
                })
                .jsUrls(new String[]{
                        "https://unpkg.com/@univerjs/presets/lib/umd/index.js",
                        "https://unpkg.com/@univerjs/preset-sheets-core/lib/umd/index.js",
                        "https://unpkg.com/@univerjs/preset-sheets-core/locales/zh-CN.js",
                        "https://unpkg.com/@univerjs/preset-docs-core/lib/umd/index.js",
                        "https://unpkg.com/@univerjs/preset-docs-core/locales/zh-CN.js",
                        "https://cdn.jsdelivr.net/npm/@zwight/luckyexcel/dist/luckyexcel.umd.min.js"
                })
                .embeddedJs(initScript)
                .build();
    }

    /**
     * 构建Script
     * @param b64 b64
     * @param ext ext
     * @param isSheet isSheet
     * @param isDoc isDoc
     */
    private String buildScript(String b64, String ext, boolean isSheet, boolean isDoc) {
        String s = "(function(){";
        s += "var B64='" + b64 + "';";
        s += "var EXT='" + ext + "';";
        s += "function b64ToFile(b,n,m){var a=atob(b),l=a.length,bs=new Uint8Array(l);";
        s += "for(var i=0;i<l;i++)bs[i]=a.charCodeAt(i);return new File([bs],n,{type:m})}";
        s += "function init(){";
        if (isSheet) {
            s += "var f=b64ToFile(B64,'file.'+EXT,'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');";
            s += "LuckyExcel.transformExcelToUniver(f,function(j){";
            s += "if(j&&j.sheets&&j.sheets.length>0){";
            s += "var r=UniverPresets.createUniver({locale:UniverCore.LocaleType.ZH_CN,";
            s += "locales:{[UniverCore.LocaleType.ZH_CN]:UniverCore.mergeLocales(UniverPresetSheetsCoreZhCN)},";
            s += "presets:[UniverPresetSheetsCore.UniverSheetsCorePreset({container:'app'})]});";
            s += "r.univerAPI.createWorkbook(j)}else{";
            s += "document.getElementById('app').innerHTML='<p style=\"padding:40px;color:#666\">无法解析文件</p>'}})";
            s += "},function(e){";
            s += "document.getElementById('app').innerHTML='<p style=\"padding:40px;color:#c00\">加载失败: '+(e&&e.message||e)+'</p>'})";
        } else if (isDoc) {
            s += "var f=b64ToFile(B64,'file.'+EXT,'application/vnd.openxmlformats-officedocument.wordprocessingml.document');";
            s += "var r=UniverPresets.createUniver({locale:UniverCore.LocaleType.ZH_CN,";
            s += "locales:{[UniverCore.LocaleType.ZH_CN]:UniverCore.mergeLocales(UniverPresetDocsCoreZhCN)},";
            s += "presets:[UniverPresetDocsCore.UniverDocsCorePreset({container:'app'})]});";
            s += "r.univerAPI.importDOCXToSnapshotAsync(f).then(function(sn){";
            s += "r.univerAPI.createUniverDoc(sn)}).catch(function(e){";
            s += "document.getElementById('app').innerHTML='<p style=\"padding:40px;color:#c00\">文档加载失败</p>'});";
        } else {
            s += "document.getElementById('app').innerHTML='<p style=\"padding:40px;color:#666\">幻灯片预览加载中...</p>';";
        }
        s += "}if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',init)}else{init()}})();";
        return s;
    }
}
