package com.chua.deeplearning.support.onnx.text.minimind;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * MiniMindTokenizer 独立单元测试
 * <p>
 * 验证 BPE 编码与 GPT-2 ByteLevel 编码一致性。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniMindTokenizerTest {

    public static void main(String[] args) throws Exception {
        Path tokenizerPath;
        if (args.length > 0) {
            tokenizerPath = Path.of(args[0]);
        } else {
            String envPath = System.getenv("MINIMIND_TOKENIZER");
            if (envPath != null) {
                tokenizerPath = Path.of(envPath);
            } else {
                tokenizerPath = Path.of("D:/ch/project/minimind-3/tokenizer.json");
            }
        }
        log.info("==> Loading tokenizer from: {}", tokenizerPath);
        MiniMindTokenizer tk = MiniMindTokenizer.load(tokenizerPath);
        log.info("vocab_size: {}", tk.vocabSize());

        String[] prompts = {
                "你好",
                "你好，请介绍一下自己。",
                "中国的首都是",
                "1+1=",
                "今天天气",
                "<|im_start|>system\n你是一个AI助手<|im_end|>",
                "Hello, world!",
                "AI 是",
        };
        int[] expected = {
                1968, 294,
                1968, 294, 960, 2919, 2360, 1153, 302,
                1405, 296, 1408, 2462,
                52, 46, 52, 64,
                5640, 3660,
                1, 118, 4849, 234, 441, 1001, 1339, 5127, 2,
                -1, -1,
                -1, -1,
        };
        int idx = 0;
        boolean allOk = true;
        for (String p : prompts) {
            int[] ids = tk.encode(p);
            String decoded = tk.decode(ids);
            StringBuilder sb = new StringBuilder();
            for (int id : ids) sb.append(id).append(' ');
            if (expected[idx] >= 0) {
                int[] exp = Arrays.copyOfRange(expected, idx, idx + ids.length);
                boolean ok = Arrays.equals(exp, ids);
                log.info("encode({}) -> [{}] (length={}) expected=[{}] ok={}", p, sb, ids.length, Arrays.toString(exp), ok);
                if (!ok) {
                    allOk = false;
                }
            } else {
                log.info("encode({}) -> [{}] (length={})", p, sb, ids.length);
            }
            log.info("  decode() = '{}'", decoded);
            idx += ids.length;
        }
        log.info("==> Overall: {}", allOk ? "PASS" : "FAIL");
    }
}
