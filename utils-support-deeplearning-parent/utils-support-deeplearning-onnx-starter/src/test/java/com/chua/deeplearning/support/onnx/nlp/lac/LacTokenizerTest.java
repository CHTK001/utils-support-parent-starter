package com.chua.deeplearning.support.onnx.nlp.lac;

import com.chua.common.support.language.tokenizer.Tokenizer;
import com.chua.common.support.language.tokenizer.Word;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LacTokenizer SPI 生产级验收。
 *
 * <p>覆盖 Tokenizer 契约的全部可调用入口：SPI 按别名获取、静态
 * {@code segment(word,type)}、默认方法、Word offset 连续性、NER 标签、空输入，
 * 以及 JVM 级共享会话的创建/复用/释放。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LacTokenizerTest {

    @Test
    @Order(1)
    void spiRegistered() {
        Tokenizer t = Tokenizer.of("lac");
        assertNotNull(t, "SPI lac 未注册");
        assertInstanceOf(LacTokenizer.class, t);
    }

    @Test
    @Order(2)
    void segments() {
        Tokenizer t = Tokenizer.of("lac");
        List<Word> words = t.segments("\u6211\u7231\u5317\u4eac\u5929\u5b89\u95e8");
        assertFalse(words.isEmpty());
        StringBuilder back = new StringBuilder();
        int pos = 0;
        for (Word w : words) {
            assertNotNull(w.getWord());
            assertNotNull(w.getNature());
            back.append(w.getWord());
            assertTrue(w.getOffset() >= pos, w.toString());
            assertEquals(w.getOffset() + w.getWord().length(), w.getEnd());
            pos = w.getEnd();
        }
        assertEquals("\u6211\u7231\u5317\u4eac\u5929\u5b89\u95e8", back.toString());
    }

    @Test
    @Order(3)
    void nerLabels() {
        Tokenizer t = Tokenizer.of("lac");
        List<Word> words = t.segments("\u5f20\u4e09\u5728\u5317\u4eac\u5e02\u5de5\u4f5c");
        boolean hasPer = words.stream().anyMatch(w -> "PER".equals(w.getNature()));
        boolean hasLoc = words.stream().anyMatch(w -> "LOC".equals(w.getNature()));
        assertTrue(hasPer, words.toString());
        assertTrue(hasLoc, words.toString());
    }

    @Test
    @Order(4)
    void emptyInput() {
        Tokenizer t = Tokenizer.of("lac");
        assertTrue(t.segments("").isEmpty());
        assertTrue(t.segments(null).isEmpty());
    }

    @Test
    @Order(5)
    void defaultMethods() {
        Tokenizer t = Tokenizer.of("lac");
        String seg = t.toSegment("\u6211\u7231\u5317\u4eac");
        assertFalse(seg.isEmpty());
        assertTrue(seg.contains(" "));
        assertNotNull(t.toFirstSegment("\u6211\u7231\u5317\u4eac"));
    }

    /**
     * 静态入口 {@code segment(word, type)}：按别名分词并还原原文。
     */
    @Test
    @Order(6)
    void staticSegmentByType() {
        List<Word> words = Tokenizer.segment("\u6211\u7231\u5317\u4eac", "lac");
        assertFalse(words.isEmpty(), "segment(word,type) 未走 SPI");
        StringBuilder back = new StringBuilder();
        words.forEach(w -> back.append(w.getWord()));
        assertEquals("\u6211\u7231\u5317\u4eac", back.toString());
    }

    /**
     * 未知别名：接口约定退化为单字切分（不得抛异常）。
     */
    @Test
    @Order(7)
    void staticSegmentUnknownTypeFallsBackToChars() {
        List<Word> words = Tokenizer.segment("\u4e2d\u6587", "no-such-tokenizer-xyz");
        assertEquals(2, words.size());
        assertEquals("\u4e2d", words.get(0).getWord());
        assertEquals("\u6587", words.get(1).getWord());
    }

    /**
     * 共享会话：连续多次 {@code Tokenizer.of("lac")} 必须复用同一底层会话，
     * 否则循环调用会按实例泄漏 32MB ONNX 会话（Tokenizer 契约无 close()）。
     */
    @Test
    @Order(8)
    void sharedSessionIsReused() {
        Tokenizer a = Tokenizer.of("lac");
        a.segments("\u6d4b\u8bd5");
        Tokenizer b = Tokenizer.of("lac");
        assertNotSame(a, b, "SPI of() 应返回新实例");
        b.segments("\u6d4b\u8bd5");
        // 反射比对底层共享 translator 是否为同一对象
        Object sharedA = field(a, "shared");
        Object sharedB = field(b, "shared");
        assertSame(sharedA, sharedB, "两个实例应共享同一 translator");
    }

    /**
     * 释放后仍可重新加载（幂等 + 可恢复）。
     */
    @Test
    @Order(9)
    void shutdownIsIdempotentAndReloadable() {
        Tokenizer t = Tokenizer.of("lac");
        assertFalse(t.segments("\u6d4b\u8bd5").isEmpty());
        assertDoesNotThrow(LacTokenizer::shutdown);
        assertDoesNotThrow(LacTokenizer::shutdown);
        Tokenizer again = Tokenizer.of("lac");
        assertFalse(again.segments("\u91cd\u65b0\u52a0\u8f7d").isEmpty());
    }

    /**
     * 读静态字段 shared（可能为 null）
     */
    private static Object field(Tokenizer t, String name) {
        try {
            var f = LacTokenizer.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}