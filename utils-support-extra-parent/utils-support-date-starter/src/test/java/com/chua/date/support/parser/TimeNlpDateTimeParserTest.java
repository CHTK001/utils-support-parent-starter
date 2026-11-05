// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.date.support.parser;

import com.chua.common.support.lang.date.DateTime;
import com.chua.common.support.lang.date.DateTimeParser;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DateTimeParser SPI 与 Time-NLP 解析验证。
 *
 * @author CH
 * @since 4.0.0.42
 */
class TimeNlpDateTimeParserTest {

    @Test
    void collectShouldContainStandardAndTimeNlp() {
        List<DateTimeParser> parsers = ServiceProvider.of(DateTimeParser.class).collect();
        assertTrue(parsers.stream().anyMatch(it -> it instanceof TimeNlpDateTimeParser));
        assertTrue(parsers.stream().anyMatch(it -> it.getClass().getName().endsWith("StandardDateTimeParser")));
    }

    @Test
    void parseIsoShouldWork() {
        LocalDateTime result = DateTime.parse("2024-01-01T12:00:00");
        assertEquals(LocalDateTime.of(2024, 1, 1, 12, 0, 0), result);
    }

    @Test
    void parseNaturalLanguageShouldWork() {
        LocalDateTime result = DateTime.parse("明天下午三点开会");
        assertNotNull(result);
        assertEquals(15, result.getHour());
    }
}