package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 代码 / 文本预览提供者：js、CSS、xml、json、yaml、Java、py 等 → 语法高亮 HTML。
 *
 * <p>注意：html/htm 已由 HtmlPreviewProvider 接管，csv 已由 CsvPreviewProvider 接管，
 * md 已由 markdownpreview提供者 接管，SVG 已由 SVGpreview提供者 接管。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-code")
public class CodePreviewProvider implements FileStoragePreviewProvider {

    /**
     * 编码_exts
    */
    private static final Set<String> CODE_EXTS = Set.of(
            "js", "ts", "jsx", "tsx", "css", "scss", "less",
            "xml", "json", "yaml", "yml", "toml",
            "java", "kt", "groovy", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp",
            "sql", "sh", "bash", "zsh", "ps1",
            "properties", "cfg", "conf", "ini", "env"
    );

    /**
     * 已有专门 SPI 提供者抢跑的扩展名，编码preview提供者 不应匹配
    */
    private static final Set<String> BYPASS_EXTS = Set.of("html", "htm", "csv", "md", "svg");

    /**
     * highlight.js 主库路径（由宿主服务从同源 /preview-vendor/hljs 提供，避免依赖公网 CDN）
     */
    private static final String HIGHLIGHT_JS = "/preview-vendor/hljs/highlight.min.js";

    /**
     * atom-one-dark 主题样式表路径
     */
    private static final String HIGHLIGHT_CSS = "/preview-vendor/hljs/atom-one-dark.min.css";

    @Override
    /**
     * 支持
    */
    public boolean supports(String extension, String mimeType) {
        // 排除已有专门 SPI 提供者的扩展名
        if (extension != null && BYPASS_EXTS.contains(extension.toLowerCase(Locale.ENGLISH))) {
            return false;
        }
        // 排除已有专门 SPI 提供者的 MIME 类型
        if (mimeType != null) {
            if ("text/csv".equals(mimeType) || "text/markdown".equals(mimeType)
                    || "text/html".equals(mimeType)) {
                return false;
            }
        }
        if (mimeType != null && (mimeType.startsWith("text/")
                || "application/json".equals(mimeType)
                || "application/xml".equals(mimeType)
                || "application/javascript".equals(mimeType))) {
            return true;
        }
        return extension != null && CODE_EXTS.contains(extension.toLowerCase(Locale.ENGLISH));
    }

    @Override
    /**
     * Preview
    */
    public PreviewResult preview(byte[] content, String extension, String mimeType) throws IOException {
        String code = new String(content, StandardCharsets.UTF_8);
        String lang = extension != null ? extension.toLowerCase(Locale.ENGLISH) : "txt";
        String css = "body{margin:0;background:#1e1e1e;color:#d4d4d4;"
                + "font-family:'Cascadia Code','Fira Code',Consolas,monospace;font-size:13px;line-height:1.5}"
                + "pre{margin:0;padding:16px;overflow:auto;tab-size:4}"
                + ".lang-badge{position:sticky;top:0;display:inline-block;padding:4px 12px;font-size:12px;"
                + "background:#007acc;color:#fff;border-radius:0 0 6px 0;font-family:sans-serif}"
                + ".hljs{padding:0!important;background:transparent!important}";
        String html = "<div class=\"lang-badge\">" + escapeHtml(lang) + "</div><pre><code class=\"hljs language-"
                + escapeHtml(lang) + "\">" + escapeHtml(code) + "</code></pre>"
                + "<link rel=\"stylesheet\" href=\"" + HIGHLIGHT_CSS + "\">"
                + "<script src=\"" + HIGHLIGHT_JS + "\"></script>"
                + "<script>hljs.highlightAll()</script>";
        return PreviewResult.builder().htmlContent(html).embeddedCss(css).build();
    }

    /**
     * escapehtml
     *
     * @param s s
     * @return escapeHtml的结果
     */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
