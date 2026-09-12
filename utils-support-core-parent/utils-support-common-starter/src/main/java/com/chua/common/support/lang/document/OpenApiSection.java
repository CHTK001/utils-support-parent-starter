package com.chua.common.support.lang.document;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
* OpenAPI 文档首页 / 附加 section（如快速入门 / 接入指南 / 变更日志）。
*
* <p>渲染时合并到侧边 tree 的"快速入门"分组, 章节内容采用 Markdown 文本。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
public class OpenApiSection {

    /**
    * 标题（侧边栏 & 页面 H2）。
     */
    private String title;

    /**
    * Markdown 文本内容（支持 p / h3 / ul / li / code / pre / table 标签）。
     */
    private String content;
}
