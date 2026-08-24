package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 响应式流式解析测试：验证惰性、背压与早停（不整文件装载）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class ReactiveUsageStreamsTest {

    /**
     * 基于 BaseUsageParser.streamLines 的临时 JSONL 解析器。
     */
    static class TempJsonlParser extends BaseUsageParser {
        private final Path file;
        final AtomicInteger parsed = new AtomicInteger();

        TempJsonlParser(Path file) {
            this.file = file;
        }

        @Override
        public String name() {
            return "temp";
        }

        /** 旧契约桥接：不应被调用（streamAll 已走流式路径） */
        @Override
        public List<AiUsage> parseAll() {
            throw new UnsupportedOperationException("应走流式路径");
        }

        /** 新契约：逐行惰性流式 */
        @Override
        public Flux<AiUsage> streamAll() {
            return streamLines(file)
                    .filter(l -> !l.isBlank())
                    .map(l -> {
                        parsed.incrementAndGet();
                        AiUsage u = Mockito.mock(AiUsage.class);
                        return u;
                    });
        }
    }

    /**
     * 流式读取 5000 行且 take(3) 早停——证明非全量装载。
     */
    @Test
    void streaming_with_early_termination() throws Exception {
        Path f = Files.createTempFile("usage", ".jsonl");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("model-").append(i).append('\n');
        }
        Files.writeString(f, sb.toString());

        TempJsonlParser parser = new TempJsonlParser(f);

        StepVerifier.create(parser.streamAll().take(3), 3)
                .expectNextCount(3)
                .verifyComplete();

        // 早停后实际解析行数远小于 5000（拉取式，非预读全部）
        assertTrue(parser.parsed.get() <= 1000,
                "早停后解析行数应远小于总量, 实际=" + parser.parsed.get());
        Files.deleteIfExists(f);
    }

    /**
     * 聚合器：多解析器合并 + 惰性（未订阅前不执行）。
     */
    @Test
    void merge_is_lazy_and_complete() {
        AtomicInteger calls = new AtomicInteger();
        BaseUsageParser lazy = new BaseUsageParser() {
            @Override
            public String name() {
                return "lazy";
            }

            @Override
            public List<AiUsage> parseAll() {
                calls.incrementAndGet();
                return List.of(Mockito.mock(AiUsage.class), Mockito.mock(AiUsage.class));
            }
        };

        var flux = ReactiveUsageStreams.concat(List.of(lazy));
        assertEquals(0, calls.get(), "concat 应惰性，订阅前不得触发");

        StepVerifier.create(flux).expectNextCount(2).verifyComplete();
        assertEquals(1, calls.get());
    }

    /**
     * 遗留桥接：Base 子类仅需提供 parseAll，即可获得响应式入口（惰性）。
     */
    @Test
    void bridge_wraps_legacy_parseAll() {
        AtomicInteger calls = new AtomicInteger();
        BaseUsageParser legacy = new BaseUsageParser() {
            @Override
            public String name() {
                return "legacy";
            }

            @Override
            public List<AiUsage> parseAll() {
                calls.incrementAndGet();
                return List.of(Mockito.mock(AiUsage.class));
            }
        };

        assertEquals(0, calls.get(), "桥接应惰性，订阅前不得触发");
        StepVerifier.create(legacy.streamAll()).expectNextCount(1).verifyComplete();
        assertEquals(1, calls.get());
    }
}
