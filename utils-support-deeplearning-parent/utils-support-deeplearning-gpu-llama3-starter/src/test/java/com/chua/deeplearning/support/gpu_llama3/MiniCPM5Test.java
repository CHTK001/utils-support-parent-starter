package com.chua.deeplearning.support.gpu_llama3;

import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaIterator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MiniCPM5-2B GGUF CPU 冒烟测试（非 JUnit，直接 main 运行）。
 *
 * <p>验证 kherud/llama.cpp-java 4.2.0 在 CPU 上加载
 * openbmb/MiniCPM5-2B-GGUF (Q4_K_M, 1.56 GB) 并完成对话生成，
 * 确认 Java 侧可直接运行该 GGUF 模型。</p>
 *
 * <p>用法：先确认 llama3/MiniCPM5-2B-Q4_K_M.gguf 已就位，
 * 再运行 main；第一个参数可自定义模型路径。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MiniCPM5Test {

    /**
     * 生成上限 令牌 数
    */
    private static final int NPREDICT = 128;
    /**
     * 上下文大小
    */
    private static final int CTX_SIZE = 2048;

    /**
     * 执行 MiniCPM5-2B CPU 冒烟推理。
     *
     * @param args 运行参数（可选：[0]=模型 路径，缺省 使用模块 内 llama3/MiniCPM5-2B-Q4_K_M.gguf）
     */
    public static void main(String[] args) {
        String modelPath = args.length > 0 ? args[0] : "../llama3/MiniCPM5-2B-Q4_K_M.gguf";
        Path p = Paths.get(modelPath);
        System.out.printf("[MiniCPM5] 模型 路径: %s%n", p.toAbsolutePath());
        System.out.printf("[MiniCPM5] CPU 核心数: %d%n", Runtime.getRuntime().availableProcessors());

        long t0 = System.currentTimeMillis();
        ModelParameters parameters = new ModelParameters()
                .setModel(p.toString())
                .setCtxSize(CTX_SIZE)
                .setThreads(Runtime.getRuntime().availableProcessors())
                .setGpuLayers(0);
        LlamaModel model;
        try {
            model = new LlamaModel(parameters);
        } catch (Exception e) {
            System.err.println("[MiniCPM5] 加载失败: " + e.getMessage());
            e.printStackTrace();
            return;
        }
        long loadMs = System.currentTimeMillis() - t0;
        System.out.printf("[MiniCPM5] 加载耗时 %d ms (纯 CPU)%n", loadMs);

        try {
            run(model, "你好！请 用 一 句话 介绍 自己。");
            run(model, "用 一个 词 回答：水 的 沸点（摄氏 度）是多少？");
            run(model, "请 写 一 首 关于 秋天 的 短 诗。");
        } finally {
            model.close();
        }
        System.out.println("[MiniCPM5] 测试完成");
    }

    /**
     * 执行单次推理并打印结果与速度。
     *
     * @param model  已 加载 模型
     * @param prompt 提示词
     */
    private static void run(LlamaModel model, String prompt) {
        System.out.printf("%n[MiniCPM5] >>> %s%n", prompt);
        long t0 = System.currentTimeMillis();
        InferenceParameters ip = new InferenceParameters(prompt)
                .setTemperature(0.7f)
                .setTopK(40)
                .setNPredict(NPREDICT);
        StringBuilder sb = new StringBuilder();
        LlamaIterator it = model.generate(ip).iterator();
        int tokens = 0;
        while (it.hasNext() && tokens < NPREDICT) {
            LlamaOutput out = it.next();
            if (out == null || out.text == null) {
                break;
            }
            sb.append(out.text);
            tokens++;
        }
        it.cancel();
        long ms = System.currentTimeMillis() - t0;
        double tps = tokens > 0 ? (tokens * 1000.0) / ms : 0;
        System.out.printf("[MiniCPM5] <<< (%d tokens, %d ms, %.1f tok/s)%n", tokens, ms, tps);
        System.out.println(sb.toString().trim());
    }
}
