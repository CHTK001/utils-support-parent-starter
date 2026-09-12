package com.chua.filestorage.support.preview;

import lombok.Builder;
import lombok.Value;

/**
* 预览结果对象。
*
* <p>封装了文件转换为 HTML 预览的全部资源。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Value
@Builder
public class PreviewResult {

    /** HTML 主体内容（不含 &lt;HTML&gt;&lt;head&gt;） */
    String htmlContent;

    /** 内联 CSS（放 &lt;style&gt; 中） */
    String embeddedCss;

    /** 内联 JS（放 &lt;script&gt; 中） */
    String embeddedJs;

    /** 额外 CSS URL（放 &lt;链接&gt; 中） */
    String[] cssUrls;

    /** 额外 JS URL（放 &lt;script src&gt; 中） */
    String[] jsUrls;

    /** 是否需要 iframe 沙箱隔离 */
    boolean requiresSandbox;
}
