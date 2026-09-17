package com.chua.common.support.file.txtractor;


/**
* 文本提取结果实体，包含文本内容及其在文档中的结构信息。
* <p>
* 每个 {@link TextExtractResult} 实例代表文档中的一个结构化片段，
* 可以是：
* <ul>
*   <li>一个章节/段落（含章节名）</li>
*   <li>一个页面的文本（含页码）</li>
*   <li>整个文档（无章节/页码信息时为单一结果）</li>
* </ul>
* </p>
*
* <pre>{@code
* List<TextExtractResult> results = TextExtractor.create("pdf").extractText(file);
* for (var r : results) {
*     System.out.printf("[第%d页][%s] %s%n", r.pageNumber(), r.section(), r.text());
* }
* }</pre>
*
* @param text       文本内容
* @param section    章节名称，无章节信息时为空字符串
* @param pageNumber 页码，从 1 开始；无页码信息时为 0
* @param sourceFile 来源文件路径（相对路径），可为空
* @author CH
* @since 4.0.0.42
 */
public record TextExtractResult(
        String text,
        String section,
        int pageNumber,
        String sourceFile
) {

    /**
    * 创建一个无极简结果（无章节、页码、文件信息）。
    *
    * @param text 文本内容
    */
    public TextExtractResult(String text) {
        this(text, "", 0, "");
    }

    /**
    * 创建一个带章节信息的结果。
    *
    * @param text    文本内容
    * @param section 章节名称
    */
    public TextExtractResult(String text, String section) {
        this(text, section, 0, "");
    }

    /**
    * 创建一个带页码的结果。
    *
    * @param text       文本内容
    * @param pageNumber 页码
    */
    public TextExtractResult(String text, int pageNumber) {
        this(text, "", pageNumber, "");
    }

    /**
    * 当前结果是否包含有效的结构化信息（有章节或页码）。
    *
    * @return true 如果有章节名或页码
    */
    public boolean hasStructure() {
        return (section != null && !section.isEmpty()) || pageNumber > 0;
    }
}
