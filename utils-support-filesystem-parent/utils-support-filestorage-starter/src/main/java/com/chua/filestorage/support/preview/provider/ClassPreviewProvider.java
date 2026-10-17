package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import lombok.extern.slf4j.Slf4j;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Java {@code .class} 反编译预览提供者：用 CFR 把字节码还原成 Java 源码后做语法高亮。
 *
 * <p><b>为什么需要</b>：{@code .class} 此前只在前端「二进制检视」面板里看十六进制，
 * 看不出类名、方法签名与逻辑。本提供者把 {@code render=server} 通道接上反编译，
 * 使 {@code .class} 也能像代码文件一样阅读。</p>
 *
 * <p><b>依赖</b>：{@code org.benf:cfr}（MIT，单 jar、无传递依赖），版本由父 POM 的
 * {@code dependencyManagement} 统一管理，模块内不写版本号。</p>
 *
 * <p><b>CFR 需要文件路径</b>：{@link CfrDriver} 只接受路径列表，故先落临时文件
 * 再删除。单个 {@code .class} 缺依赖类时 CFR 仍能产出源码，只是可能出现
 * 「无法解析」的行内注释——这正是我们要展示的信息，不因此判失败。</p>
 *
 * <p><b>边界</b>：只接管 {@code .class}；{@code .jar} / {@code .war} 等容器仍走
 * 压缩包预览。超过 {@link #MAX_BYTES} 直接报错，避免大文件拖垮转换。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("preview-class")
public class ClassPreviewProvider implements FileStoragePreviewProvider {

    /** 本提供者接管的扩展名（不含点） */
    private static final String EXT = "class";

    /** 反编译源码的渲染语言（hljs 语言名） */
    private static final String LANG = "java";

    /** 在线反编译体积上限：超过即引导下载（默认 16 MB） */
    private static final long MAX_BYTES = 16L * 1024 * 1024;

    /** highlight.js 主库路径（与 CodePreviewProvider 同源，避免依赖公网 CDN） */
    private static final String HIGHLIGHT_JS = "/preview-vendor/hljs/highlight.min.js";

    /** atom-one-dark 主题样式表路径 */
    private static final String HIGHLIGHT_CSS = "/preview-vendor/hljs/atom-one-dark.min.css";

    @Override
    public boolean supports(String extension, String mimeType) {
        // 只认扩展名：.class 的 MIME 多为 application/octet-stream 或空，
        // 一旦按 text/* 放宽就会抢走 CodePreviewProvider 的文本通道。
        return extension != null && EXT.equals(extension.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String extension, String mimeType) throws IOException {
        if (content == null || content.length == 0) {
            throw new IOException("文件内容为空，无法反编译");
        }
        if (content.length > MAX_BYTES) {
            throw new IOException("文件过大（超过 " + (MAX_BYTES / 1024 / 1024) + "MB），请下载后用本地工具反编译");
        }
        String source = decompile(content);
        if (source == null || source.isBlank()) {
            throw new IOException("反编译未产出源码，该文件可能不是有效的 Java 字节码");
        }
        log.debug("[ClassPreviewProvider] 反编译完成：{} 字节 -> {} 字符", content.length, source.length());
        return PreviewResult.builder()
                .htmlContent(html(source))
                .embeddedCss(css())
                .build();
    }

    /**
     * 用 CFR 把 {@code .class} 字节还原为 Java 源码。
     *
     * @param content .class 字节
     * @return Java 源码文本；失败时抛出 {@link IOException}
     */
    private String decompile(byte[] content) throws IOException {
        Path tmp = Files.createTempFile("fv-class-", "." + EXT);
        try {
            Files.write(tmp, content);
            StringBuilder source = new StringBuilder();
            OutputSinkFactory sinkFactory = new OutputSinkFactory() {
                @Override
                public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                    // 只要字符串 sink，避免 CFR 把字节码/摘要另写一份
                    return Collections.singletonList(SinkClass.STRING);
                }

                @Override
                public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                    return value -> {
                        // 只收集 JAVA 正文：进度/摘要/异常走这里会被丢弃
                        if (SinkType.JAVA == sinkType && value != null) {
                            source.append(value);
                        }
                    };
                }
            };
            CfrDriver driver = new CfrDriver.Builder().withOutputSink(sinkFactory).build();
            driver.analyse(Collections.singletonList(tmp.toAbsolutePath().toString()));
            return source.toString();
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            // CFR 对畸形字节码会抛运行时异常，统一转成可读 IO 异常交给上层降级
            throw new IOException("反编译失败：文件可能已损坏", e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                log.warn("[ClassPreviewProvider] 临时文件删除失败：{}", tmp, e);
            }
        }
    }

    /** 预览页内联样式（与 CodePreviewProvider 同款深色代码底） */
    private String css() {
        return "body{margin:0;background:#1e1e1e;color:#d4d4d4;"
                + "font-family:'Cascadia Code','Fira Code',Consolas,monospace;font-size:13px;line-height:1.5}"
                + "pre{margin:0;padding:16px;overflow:auto;tab-size:4}"
                + ".lang-badge{position:sticky;top:0;display:inline-block;padding:4px 12px;font-size:12px;"
                + "background:#007acc;color:#fff;border-radius:0 0 6px 0;font-family:sans-serif}"
                + ".note{padding:0 16px 12px;font-size:12px;color:#9aa7b4;font-family:sans-serif}"
                + ".hljs{padding:0!important;background:transparent!important}";
    }

    /** 预览页 HTML：语言徽章 + 高亮源码 + 同源 highlight.js */
    private String html(String source) {
        return "<div class=\"lang-badge\">" + escapeHtml(LANG) + "（CFR 反编译）</div>"
                + "<pre><code class=\"hljs language-" + escapeHtml(LANG) + "\">" + escapeHtml(source) + "</code></pre>"
                + "<link rel=\"stylesheet\" href=\"" + HIGHLIGHT_CSS + "\">"
                + "<script src=\"" + HIGHLIGHT_JS + "\"></script>"
                + "<script>hljs.highlightAll()</script>";
    }

    /** HTML 转义（反编译输出里几乎必然含尖括号，必须转义后再拼进 HTML） */
    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}