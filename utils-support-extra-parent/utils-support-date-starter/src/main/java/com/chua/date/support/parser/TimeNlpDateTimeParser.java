package com.chua.date.support.parser;

import com.chua.ast.support.annotation.AutoSpi;
import com.chua.common.support.lang.date.DateTimeParser;
import com.chua.common.support.spi.annotations.Spi;
import com.xkzhangsan.time.nlp.TimeNLP;
import com.xkzhangsan.time.nlp.TimeNLPUtil;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 基于 xk-time Time-NLP 的中文自然语言时间解析器。
 *
 * <p>输入一句话识别话里的时间，例如：“下周一下午三点开会”。
 * 无法识别时返回 {@code null}，以便 {@code DateTime.parse(String)}
 * 继续尝试下一个 SPI 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("time-nlp")
@AutoSpi(value = "com.chua.common.support.lang.date.DateTimeParser", name = "time-nlp")
public class TimeNlpDateTimeParser implements DateTimeParser {

    @Override
    public LocalDateTime parse(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return null;
        }
        try {
            // 标准 ISO 格式交由 StandardDateTimeParser 精确处理，
            // 此处主动让出，保证与顺序无关的精确性
            LocalDateTime.parse(timeStr);
            return null;
        } catch (Exception ignored) {
            // 非标准格式，继续走 Time-NLP 解析
        }
        List<TimeNLP> result;
        try {
            result = TimeNLPUtil.parse(timeStr);
        } catch (Exception ignored) {
            return null;
        }
        if (result == null || result.isEmpty()) {
            return null;
        }
        for (TimeNLP timeNLP : result) {
            if (timeNLP == null) {
                continue;
            }
            LocalDateTime localDateTime = timeNLP.getLocalDateTime();
            if (localDateTime != null) {
                return localDateTime;
            }
        }
        return null;
    }
}
