package com.chua.common.support.file.txtractor;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CSV 文本提取器 SPI 实现，从 CSV 文件中提取纯文本表格内容。
 * <p>
 * 纯 JDK 实现，无外部依赖。支持 UTF-8 BOM 自动检测与跳过。
 * 每行以换行分隔，每个字段以制表符分隔。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"csv", "tsv"})
public class CsvTextExtractor implements TextExtractor {

    @Override
    /** ExtractText */
    public List<TextExtractResult> extractText(File file) {
        StringBuilder sb = new StringBuilder();

        try (InputStream is = new BufferedInputStream(new FileInputStream(file));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(skipBom(is), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                // CSV 字段用制表符拼接，便于阅读
                String[] fields = parseLine(line, ',');
                for (int i = 0; i < fields.length; i++) {
                    if (i > 0) {
                        sb.append("\t");
                    }
                    sb.append(fields[i].trim());
                }
                sb.append("\n");
            }
        } catch (IOException e) {
            log.warn("提取 CSV 文本失败: {}", file.getAbsolutePath(), e);
        }

        return Collections.singletonList(new TextExtractResult(sb.toString(), "", 0, file.getName()));
    }

    @Override
    /** Type */
    public String type() {
        return "csv";
    }

    /**
     * 跳过 UTF-8 BOM 字节 (EF BB BF)。
     */
    private InputStream skipBom(InputStream in) throws IOException {
        in.mark(3);
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if (b1 != 0xEF || b2 != 0xBB || b3 != 0xBF) {
            in.reset();
        }
        return in;
    }

    /**
     * 解析 CSV 行，支持双引号转义。
     */
    private String[] parseLine(String line, char delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == delimiter) {
                    parts.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        parts.add(sb.toString());
        return parts.toArray(new String[0]);
    }
}
