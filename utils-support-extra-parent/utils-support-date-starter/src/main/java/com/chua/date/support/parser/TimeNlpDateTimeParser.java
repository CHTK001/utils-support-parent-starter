package com.chua.date.support.parser;

import com.chua.ast.support.annotation.AutoSpi;
import com.chua.common.support.lang.date.DateTimeParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.condition.Precondition;
import com.xkzhangsan.time.nlp.TimeNLP;
import com.xkzhangsan.time.nlp.TimeNLPUtil;

import java.time.LocalDateTime;
import java.util.List;
import com.chua.common.support.utils.StringUtils;

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
        if (StringUtils.isEmpty(timeStr)) {
            return null;
        }
       
        // 标准 ISO 格式交由 StandardDateTimeParser 精确处理，此处主动让出，
        // 保证与顺序无关的精确性；解析异常即条件不成立，orElse 继续走 Time-NLP
        return Precondition.tryGet(() -> LocalDateTime.parse(timeStr))
                .orElseGet(ex -> parseByTimeNlp(timeStr));
    }

    /**
     * Time-NLP 兜底解析：解析有异常或结果为空均视为无法识别，返回 {@code null}，
     * 以便 {@code DateTime.parse(String)} 继续尝试下一个 SPI 实现。
     *
     * @param timeStr 原始文本
     * @return 解析结果，无法识别时为 null
     */
    private LocalDateTime parseByTimeNlp(String timeStr) {
        List<TimeNLP> result = Precondition.tryGet(() -> TimeNLPUtil.parse(timeStr))
                .orElseGet(ex -> null);
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
