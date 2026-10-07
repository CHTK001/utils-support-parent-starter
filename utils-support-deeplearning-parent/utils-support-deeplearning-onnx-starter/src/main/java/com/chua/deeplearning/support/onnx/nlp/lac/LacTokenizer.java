package com.chua.deeplearning.support.onnx.nlp.lac;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.language.tokenizer.Tokenizer;
import com.chua.common.support.language.tokenizer.Word;
import com.chua.common.support.spi.annotations.Spi;
import java.util.Collections;
import java.util.List;

/**
 * LAC 中文分词 SPI 实现（ONNX 后端）。
 *
 * <p>复用 {@link LacOnnxTranslator} 的 Embedding+BiGRU+CRF 模型，
 * 将 {@code String[token,label][]} 映射为 {@link Word} 列表
 * （含字符级 offset/end）。空输入返回空列表；translator 内部
 * 懒加载模型，首次调用触发 jar 内嵌/下载加载。</p>
 *
 * <p><b>会话共享</b>：SPI 的 {@code of(type)} 每次返回新实例，而 {@link Tokenizer}
 * 契约没有 {@code close()}。若每实例独占一个 {@link LacOnnxTranslator}，
 * 循环调用 {@code Tokenizer.of("lac")} 会为每次调用泄漏一份 32MB ONNX 会话。
 * 故此处持有 JVM 级共享的单一会话（与全局 {@code OrtEnvironment} 生命周期一致），
 * 由 {@link #shutdown()} 在应用退出时统一释放。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("lac")
public class LacTokenizer implements Tokenizer {

    /**
     * JVM 级共享会话（懒初始化）。
     */
    private static volatile LacOnnxTranslator shared;

    /**
     * 获取 JVM 级共享 translator。
     *
     * @return 共享 translator
     */
    private static LacOnnxTranslator shared() {
        LacOnnxTranslator t = shared;
        if (t == null) {
            synchronized (LacTokenizer.class) {
                t = shared;
                if (t == null) {
                    t = new LacOnnxTranslator();
                    shared = t;
                }
            }
        }
        return t;
    }

    /**
     * 释放 JVM 级共享会话（应用退出时调用；重复调用安全）。
     * <p>释放后再次 {@code segments()} 会重新加载模型。</p>
     */
    public static void shutdown() {
        synchronized (LacTokenizer.class) {
            if (shared != null) {
                shared.close();
                shared = null;
            }
        }
    }

    @Override
    public List<Word> segments(String word) {
        if (word == null || word.isEmpty()) {
            return Collections.emptyList();
        }
        String[][] rows = shared().translate(word);
        List<Word> result = Wraps.list(rows.length);
        int pos = 0;
        for (String[] row : rows) {
            String token = row[0];
            String nature = row.length > 1 ? row[1] : "";
            int start = word.indexOf(token, pos);
            if (start < 0) {
                start = pos;
            }
            int end = Math.min(word.length(), start + token.length());
            Word w = new Word(token, nature == null ? "" : nature);
            w.setOffset(start);
            w.setEnd(end);
            result.add(w);
            pos = end;
        }
        return result;
    }
}
