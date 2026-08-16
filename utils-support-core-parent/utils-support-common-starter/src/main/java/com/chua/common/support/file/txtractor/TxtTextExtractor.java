package com.chua.common.support.file.txtractor;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * TXT 文本提取器 SPI 实现，从纯文本文件中提取文本内容。
 * <p>
 * 纯 JDK 实现，无外部依赖。支持 UTF-8 BOM 自动检测与跳过。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("txt")
public class TxtTextExtractor implements TextExtractor {

    @Override
    public List<TextExtractResult> extractText(File file) {
        StringBuilder sb = new StringBuilder();

        try (InputStream is = new BufferedInputStream(new FileInputStream(file));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(skipBom(is), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            log.warn("提取 TXT 文本失败: {}", file.getAbsolutePath(), e);
        }

        return Collections.singletonList(new TextExtractResult(sb.toString(), "", 0, file.getName()));
    }

    @Override
    public String type() {
        return "txt";
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
}
