package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.github.luben.zstd.ZstdOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DshUsageParser} 取数口径测试。
 *
 * <p>DSH 的转录是 <b>zstd 压缩</b>的 {@code session.v4.jsonl.zstd}。本测试用真实
 * zstd 压缩写出夹具，覆盖"不解压就恒为空"这一静默失效，并锁定令牌口径：
 * input/cacheRead/cacheWrite 为互斥三段（须并回全量输入），推理量单列不计入输出。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
class DshUsageParserTest {

    /**
     * 把 JSONL 明文写成会话目录下的 zstd 压缩文件。
     *
     * @param home    DSH 数据根目录
     * @param project 项目目录名
     * @param session 会话目录名
     * @param jsonl   明文 JSONL
     * @return 写入的文件路径
     * @throws IOException 写文件失败
     */
    private static Path writeSession(Path home, String project, String session, String jsonl)
            throws IOException {
        Path dir = home.resolve("sessions").resolve(project).resolve(session);
        Files.createDirectories(dir);
        Path file = dir.resolve("session.v4.jsonl.zstd");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(buffer)) {
            out.write(jsonl.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(file, buffer.toByteArray());
        return file;
    }

    /**
     * 构造指向夹具根目录的解析器。
     *
     * @param home 夹具根目录
     * @return 解析器
     */
    private static DshUsageParser parserFor(Path home) {
        return new DshUsageParser(home);
    }

    @Test
    @DisplayName("解压 zstd 转录并取出 assistant/message 的用量")
    void parsesCompressedSession(@TempDir Path home) throws IOException {
        writeSession(home, "--D-ch-project--", "session-a", """
                {"type":"session","seq":0,"time":1791765305618}
                {"type":"request/header","seq":1,"time":1791765305700,"data":{"header":{"config":{"model":"deepseek-v4"}}}}
                {"type":"assistant/message","seq":2,"time":1791765306000,"data":{"message":{"source":{"model":"deepseek-v4"}},"usage":{"inputTokens":100,"outputTokens":20,"cacheReadTokens":300,"cacheWriteTokens":50,"reasoningTokens":7}}}
                """);

        List<AiUsage> records = parserFor(home).streamAll().collectList().block();

        assertNotNull(records);
        assertEquals(1, records.size(), "压缩转录应被解出恰好一条用量");
        AiUsage u = records.getFirst();
        assertEquals(Integer.valueOf(450), u.getInputTokens(), "互斥三段应并回全量输入: 100+300+50");
        assertEquals(Integer.valueOf(20), u.getOutputTokens());
        assertEquals(Integer.valueOf(300), u.getCacheTokens(), "命中量只计 cacheRead");
        assertEquals(Integer.valueOf(7), u.getReasoningTokens(), "推理量单列");
        assertEquals(Integer.valueOf(470), u.getTotalTokens(), "total = 全量输入 + 输出，不含推理");
        assertEquals("deepseek-v4", u.getModel());
        assertEquals("dsh", u.getProvider());
        assertEquals(1791765306000L, u.getStartTime().longValue());
    }

    @Test
    @DisplayName("模型名缺失时回退到请求头，再缺失则用占位名")
    void fallsBackToHeaderModel(@TempDir Path home) throws IOException {
        writeSession(home, "p", "s", """
                {"type":"request/header","seq":1,"time":1791765305700,"data":{"header":{"config":{"model":"header-model"}}}}
                {"type":"assistant/message","seq":2,"time":1791765306000,"data":{"message":{"source":{}},"usage":{"inputTokens":10,"outputTokens":1}}}
                """);

        List<AiUsage> records = parserFor(home).streamAll().collectList().block();

        assertNotNull(records);
        assertEquals(1, records.size());
        assertEquals("header-model", records.getFirst().getModel());
    }

    @Test
    @DisplayName("全零用量行不产出记录")
    void skipsZeroUsage(@TempDir Path home) throws IOException {
        writeSession(home, "p", "s", """
                {"type":"assistant/message","seq":2,"time":1791765306000,"data":{"message":{"source":{"model":"m"}},"usage":{"inputTokens":0,"outputTokens":0,"cacheReadTokens":0,"cacheWriteTokens":0,"reasoningTokens":0}}}
                """);

        List<AiUsage> records = parserFor(home).streamAll().collectList().block();

        assertNotNull(records);
        assertTrue(records.isEmpty(), "全零用量不应产出记录");
    }

    @Test
    @DisplayName("非 assistant/message 事件被忽略")
    void ignoresOtherEvents(@TempDir Path home) throws IOException {
        writeSession(home, "p", "s", """
                {"type":"session","seq":0,"time":1791765305618}
                {"type":"user/message","seq":1,"time":1791765305700,"data":{"text":"hi"}}
                {"type":"tool/call","seq":2,"time":1791765305800,"data":{}}
                """);

        List<AiUsage> records = parserFor(home).streamAll().collectList().block();

        assertNotNull(records);
        assertTrue(records.isEmpty());
    }

    @Test
    @DisplayName("缺少会话目录时返回空流")
    void emptyWhenNoSessions(@TempDir Path home) {
        List<AiUsage> records = parserFor(home).streamAll().collectList().block();

        assertNotNull(records);
        assertTrue(records.isEmpty());
    }

    @Test
    @DisplayName("明文 JSONL 也能读取（未压缩时不做解压）")
    void readsPlainJsonl(@TempDir Path home) throws IOException {
        Path dir = home.resolve("sessions").resolve("p").resolve("s");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("session.v4.jsonl.zstd"), """
                {"type":"assistant/message","seq":2,"time":1791765306000,"data":{"message":{"source":{"model":"m"}},"usage":{"inputTokens":5,"outputTokens":6}}}
                """, StandardCharsets.UTF_8);

        List<AiUsage> records = parserFor(home).streamAll().collectList().block();

        assertNotNull(records);
        assertEquals(1, records.size(), "无 zstd 魔数时应按明文处理");
        assertEquals(Integer.valueOf(5), records.getFirst().getInputTokens());
    }

    @Test
    @DisplayName("多个会话文件全部被扫描")
    void scansAllSessions(@TempDir Path home) throws IOException {
        writeSession(home, "p", "s1", """
                {"type":"assistant/message","seq":1,"time":1791765306000,"data":{"message":{"source":{"model":"m"}},"usage":{"inputTokens":1,"outputTokens":1}}}
                """);
        writeSession(home, "p", "s2", """
                {"type":"assistant/message","seq":1,"time":1791765307000,"data":{"message":{"source":{"model":"m"}},"usage":{"inputTokens":2,"outputTokens":2}}}
                """);

        List<AiUsage> records = parserFor(home).streamAll().collectList().block();

        assertNotNull(records);
        assertEquals(2, records.size());
    }

    @Test
    @DisplayName("SPI 名称为 dsh")
    void exposesSpiName() {
        assertEquals("dsh", parserFor(Path.of(".")).name());
    }
}
