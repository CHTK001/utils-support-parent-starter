package com.chua.word.support.file.impl;

import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.file.template.TemplateFileSystem;
import com.chua.common.support.spi.ServiceProvider;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
* Word 文件写入构建器。
*
* <p>基于 Apache POI 实现 .docx 文档的文本写入。
* 支持延迟写入（多次 写入 + 饰面）和实时写入（写入和flush），
* 设置 {@link #withTemplate(File)} 后只走模板模式。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class WordWriteBuilder extends WriteBuilder {
    /**
    * 模板文件流
    */
    private InputStream templateStream;

    /**
    * 创建 word写入构建器 实例
    * @param file 文件
    */
    public WordWriteBuilder(File file) {
        super(file);
    }

    /**
    * 设置模板文件流（设置后只走模板）
    *
    * @param stream 模板文件流
    * @return 当前构建器
    */
    public WordWriteBuilder withTemplate(InputStream stream) {
        this.templateStream = stream;
        return this;
    }

    /**
    * 将文本行加入延迟写入队列。
    *
    * @param lines 文本行列表
    * @return 写入的结果
    */
    public WordWriteBuilder write(List<String> lines) {
        pending.add(lines);
        return this;
    }

    @Override
    /** 写入 */
    public WordWriteBuilder write(Object data) {
        pending.add(data);
        return this;
    }

    /**
    * 将 映射 数据加入延迟写入队列。
    *
    * @param rows 映射 数据列表
    * @return 写入映射的结果
    */
    public WordWriteBuilder writeMap(List<Map<String, Object>> rows) {
        pending.add(rows);
        return this;
    }

    /**
    * 实时写入文本行。
    *
    * @param lines 文本行列表
    */
    public void writeAndFlush(List<String> lines) {
        callback.onStart();
        callback.onBeginWrite();
        if (templateFile != null || templateStream != null) {
            resolveTemplate();
            callback.onComplete(true);
            return;
        }
        doWriteText(lines);
        callback.onComplete(true);
    }

    /**
    * 实时写入 映射 数据。
    *
    * @param rows 映射 数据列表
    */
    public void writeAndFlushMap(List<Map<String, Object>> rows) {
        callback.onStart();
        callback.onBeginWrite();
        if (templateFile != null || templateStream != null) {
            resolveTemplate();
            callback.onComplete(true);
            return;
        }
        doWriteMap(rows);
        callback.onComplete(true);
    }

    @Override
    /** 饰面 */
    public void finish() {
        callback.onStart();
        callback.onBeginWrite();
        if (templateFile != null || templateStream != null) {
            resolveTemplate();
            callback.onComplete(true);
            return;
        }
        try (XWPFDocument doc = new XWPFDocument()) {
            int written = 0;
            for (Object entry : pending) {
                if (entry instanceof List) {
                    for (Object item : (List<?>) entry) {
                        if (item instanceof String) {
                            XWPFParagraph paragraph = doc.createParagraph();
                            XWPFRun run = paragraph.createRun();
                            run.setText((String) item);
                            written += ((String) item).getBytes().length;
                        } else if (item instanceof Map) {
                            Map<String, Object> row = (Map<String, Object>) item;
                            for (Map.Entry<String, Object> e : row.entrySet()) {
                                String key = e.getKey();
                                if (columnMapping != null) {
                                    key = columnMapping.getOrDefault(key, key);
                                }
                                String line = key + ": " + (e.getValue() != null ? e.getValue().toString() : "");
                                XWPFParagraph p = doc.createParagraph();
                                XWPFRun run = p.createRun();
                                run.setText(line);
                                written += line.getBytes().length;
                            }
                        }
                    }
                }
            }
            callback.onProgress(written, written);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                doc.write(fos);
            }
            callback.onComplete(true);
        } catch (IOException e) {
            callback.onComplete(false);
            throw new RuntimeException("Word 写入失败", e);
        }
    }

    /**
    * 执行写入文本
    *
    * @param lines 线
    */
    private void doWriteText(List<String> lines) {
        try (XWPFDocument doc = new XWPFDocument()) {
            int written = 0;
            for (String line : lines) {
                XWPFParagraph paragraph = doc.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(line);
                written += line.getBytes().length;
            }
            callback.onProgress(written, written);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                doc.write(fos);
            }
        } catch (IOException e) {
            throw new RuntimeException("Word 写入失败", e);
        }
    }

    /**
    * 执行写入映射
    *
    * @param rows rows
    */
    private void doWriteMap(List<Map<String, Object>> rows) {
        try (XWPFDocument doc = new XWPFDocument()) {
            int written = 0;
            for (Map<String, Object> row : rows) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    String key = entry.getKey();
                    if (columnMapping != null) {
                        key = columnMapping.getOrDefault(key, key);
                    }
                    String line = key + ": " + (entry.getValue() != null ? entry.getValue().toString() : "");
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun run = p.createRun();
                    run.setText(line);
                    written += line.getBytes().length;
                }
            }
            callback.onProgress(written, written);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                doc.write(fos);
            }
        } catch (IOException e) {
            throw new RuntimeException("Word 写入失败", e);
        }
    }

    /** 解析Template */
    private void resolveTemplate() {
        try {
            TemplateFileSystem engine = ServiceProvider.of(TemplateFileSystem.class).getExtension("txt");
            if (engine == null) {
                throw new IllegalStateException("未找到 FileTemplateSystem 实现");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream tis = templateStream != null ? templateStream : new FileInputStream(templateFile)) {
                engine.resolve(tis, bos, templateData);
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bos.toByteArray());
            }
        } catch (IOException e) {
            throw new RuntimeException("Word 模板写入失败", e);
        }
    }
}
