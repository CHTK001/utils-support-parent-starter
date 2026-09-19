package com.chua.pdf.support.file.impl;

import com.chua.common.support.file.builder.ReadBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDF 文件读取构建器。
 *
 * <p>基于 PDFBox 实现 PDF 文档的文本提取。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PdfReadBuilder extends ReadBuilder {

    /**
     * 开始页
    */
    private int startPage = 1;
    /**
     * 结束页
    */
    private int endPage = Integer.MAX_VALUE;

    /**
     * 创建 pdf读取构建器 实例
     * @param file 文件
     */
    public PdfReadBuilder(File file) {
        super(file);
    }

    /**
     * 起始页（从 1 开始）
     * @param page page
     * @return 启动page的结果
     */
    public PdfReadBuilder startPage(int page) {
        this.startPage = page;
        return this;
    }

    /**
     * 结束页
     * @param page page
     * @return 结束page的结果
     */
    public PdfReadBuilder endPage(int page) {
        this.endPage = page;
        return this;
    }

    @Override
    /**
     * with字符集
    */
    public PdfReadBuilder withCharset(String charset) {
        super.withCharset(charset);
        return this;
    }

    /**
     * 提取 PDF 文档的全部文本内容
     * @return 文本的结果
     */
    public String text() {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(startPage);
            stripper.setEndPage(Math.min(endPage, doc.getNumberOfPages()));
            String result = stripper.getText(doc);
            if (callback != null) {
                callback.onBody(result);
                callback.onComplete(1);
            }
            return result;
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 按页读取文本
     * @return pages的结果
     */
    public List<String> pages() {
        List<String> result = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int total = doc.getNumberOfPages();
            int from = Math.max(1, startPage);
            int to = Math.min(endPage, total);
            for (int i = from; i <= to; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(doc);
                result.add(pageText);
                if (callback != null) {
                    callback.onBody(pageText);
                }
            }
        } catch (IOException ignored) {}
        if (callback != null) {
            callback.onComplete(result.size());
        }
        return result;
    }

    @Override
    /**
     * 读取
    */
    public Object read() {
        return text();
    }

    /**
     * 元数据
     * @return metadata的结果
     */
    public PDDocumentInformation metadata() {
        try (PDDocument doc = Loader.loadPDF(file)) {
            return doc.getDocumentInformation();
        } catch (IOException e) { return null; }
    }

    /**
     * 标题
     * @return title的结果
     */
    public String title() {
        var info = metadata();
        return info != null ? info.getTitle() : null;
    }

    /**
     * 页数
     * @return page数量的结果
     */
    public int pageCount() {
        try (PDDocument doc = Loader.loadPDF(file)) {
            return doc.getNumberOfPages();
        } catch (IOException e) { return 0; }
    }

    @Override
    /**
     * as线
    */
    public List<String> asLines() {
         return List.of(text().split("\\n")); 
    }

    @Override
    /**
     * as字符串
    */
    public String asString() {
         return text(); 
    }
}

