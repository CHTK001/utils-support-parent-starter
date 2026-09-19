package com.chua.deeplearning.support.audio;

import com.chua.deeplearning.support.engine.CliModelRunner;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

/**
 * 基于外部 CLI 的 ASR 翻译器（nemo-speech 运行时，Parakeet 等 NeMo Speech 系模型）。
 *
 * <p>输入音频字节（WAV 容器：PCM16 / {@code pcm_s16le}，单声道或多声道，8–48kHz 实测可用；
 * nemo-speech CLI 内部自行下混/重采样至模型采样率）。<b>float32 WAV 会被 CLI 拒绝</b>，
 * 需先转成 PCM16：{@code ffmpeg -i INPUT -ac 1 -ar 16000 -c:a pcm_s16le output.wav}。
 * 输出为转写文本。</p>
 *
 * <p>与进程内 ONNX 翻译器不同，本翻译器把"模型推理"整体外包给 {@link CliModelRunner}
 * 定位的外部 CLI。模型文件优先由 {@link ModelRegistry} 在 Java 侧托管下载（见
 * {@link #resolveModelArg()}），取不到时才把标识原样交给 CLI 自行下载——CLI 自带下载
 * 依赖系统 {@code curl}，在无法完成证书吊销校验的环境下不可用。
 * 本类负责 音频落盘 → 进程调用 → 回收文本。</p>
 *
 * <p>执行分两级：模型已在本地成文件时优先复用 {@link CliAsrServer}（常驻 {@code serve} 进程 +
 * OpenAI 兼容 HTTP 接口），避免每次调用重新付引擎 warmup 与模型缺页加载（本机 CPU 稳态下
 * 9.2s 音频约 3.9s 对 2.1s）；服务启动失败、进程中途退出或请求出错时，自动回退到每次新起进程的
 * {@code transcribe} 路径，两者产出的文本一致。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   ITranslator<byte[], String> t =
 *           new CliAsrTranslator(CliModelRunner.nemoSpeech(), "parakeet-v3");
 *   String text = t.translate(wavBytes);
 * }</pre>
 *
 * <h3>错误语义</h3>
 * <ul>
 *   <li>CLI 定位失败 → 抛出 {@link IllegalStateException}（见 {@link CliModelRunner#locate}）</li>
 *   <li>进程超时 → 抛出 {@link TimeoutException}</li>
 *   <li>退出码非 0 → 抛出 {@link IllegalStateException}（含 stdout/stderr 片段）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CliAsrTranslator implements ITranslator<byte[], String> {

    /** 翻译器名称 */
    private final String name;

    /** CLI 描述 */
    private final CliModelRunner.CliDescriptor cli;

    /** ASR 模型短名（nemo-speech 索引）或本地 GGUF 路径；null=CLI 默认模型 */
    private final String model;

    /** 是否请求 JSON 结构化输出（含词级时间戳） */
    private final boolean jsonOutput;

    /**
     * 构造 nemo-speech 的 CLI ASR 翻译器
     *
     * @param cli  CLI 描述（如 {@link CliModelRunner#nemoSpeech()}）
     * @param model ASR 模型短名或本地路径；null=CLI 默认
     */
    public CliAsrTranslator(CliModelRunner.CliDescriptor cli, String model) {
        this(cli, model, false);
    }

    /**
     * 构造（可开 JSON 输出）
     *
     * @param cli        CLI 描述
     * @param model      模型短名/路径，null=默认
     * @param jsonOutput 是否 JSON 输出
     */
    public CliAsrTranslator(CliModelRunner.CliDescriptor cli, String model, boolean jsonOutput) {
        this.name = cli.cliId() + (model != null ? ":" + model : "");
        this.cli = cli;
        this.model = model;
        this.jsonOutput = jsonOutput;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * 转写音频字节为文本
     *
     * @param audio 音频字节（WAV）
     * @return 转写文本
     */
    @Override
    public String translate(byte[] audio) {
        try {
            return doTranslate(audio);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("CLI ASR 转写 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 实际 转写 实现 （ 声明 受检 异常 ）
     *
     * @param audio 音频
     * @return 文本
     * @throws Exception 定位/IO/进程/超时 失败
     */
    private String doTranslate(byte[] audio) throws Exception {
        if (audio == null || audio.length == 0) {
            throw new IllegalArgumentException("音频字节为空");
        }
        Path exe = CliModelRunner.locate(cli);
        Path wav = Files.createTempFile("cli-asr-", ".wav");
        wav.toFile().deleteOnExit();
        Files.write(wav, audio);
        try {
            String m = resolveModelArg();
            String out = transcribeViaServer(exe, wav, m);
            if (out == null) {
                out = transcribeViaProcess(exe, wav, m);
            }
            return jsonOutput ? out : normalize(out);
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    /**
     * 优先走常驻服务会话；不适用或失败返回 {@code null}，由调用方回退单进程
     *
     * @param exe CLI 可执行文件
     * @param wav 落盘后的音频
     * @param m   CLI 模型取值（本地路径或短名），无模型时为 {@code null}
     * @return 转写结果；不适用时返回 {@code null}
     */
    private String transcribeViaServer(Path exe, Path wav, String m) {
        if (m == null || m.isBlank() || !Files.isRegularFile(Path.of(m))) {
            return null;
        }
        CliAsrServer server = CliAsrServer.acquire(exe, m);
        if (server == null) {
            return null;
        }
        try {
            return server.transcribe(wav, jsonOutput);
        } catch (Exception e) {
            log.warn("[cli-asr] 常驻服务转写失败，回退单进程调用: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 单进程 transcribe 调用（原始路径，每次新起进程）
     *
     * @param exe CLI 可执行文件
     * @param wav 落盘后的音频
     * @param m   CLI 模型取值（本地路径或短名），无模型时为 {@code null}
     * @return stdout 原文
     * @throws Exception 定位/进程/超时 失败
     */
    private String transcribeViaProcess(Path exe, Path wav, String m) throws Exception {
        String[] args;
        if (m != null && !m.isBlank()) {
            args = jsonOutput
                    ? new String[]{"transcribe", wav.toString(), "--model", m, "--json"}
                    : new String[]{"transcribe", wav.toString(), "--model", m};
        } else {
            args = jsonOutput ? new String[]{"transcribe", wav.toString(), "--json"}
                    : new String[]{"transcribe", wav.toString()};
        }
        return CliModelRunner.run(exe, args, 300L);
    }

    /** ModelRegistrar SPI 是否已扫描（避免每次转写重复扫资源） */
    private static volatile boolean registrarScanned;

    /**
     * 解析传给 CLI 的模型参数。
     * <p>{@code model} 若为 {@link ModelRegistry} 注册过的标识，则由 Java 侧取得（必要时下载）
     * 本地模型文件并传其绝对路径——CLI 自带的下载依赖系统 {@code curl}，在无法完成证书吊销
     * 校验的环境下不可用。未注册或取不到文件时原样透传，CLI 原生短名与本地路径均可用。</p>
     *
     * @return CLI {@code --model} 取值；无模型时为 {@code null}
     */
    private String resolveModelArg() {
        if (model == null || model.isBlank()) {
            return null;
        }
        try {
            if (!registrarScanned) {
                ModelRegistry.discoverAll();
                registrarScanned = true;
            }
            Path local = ModelRegistry.ensureDownloaded(model);
            if (local != null && Files.isRegularFile(local)) {
                return local.toAbsolutePath().toString();
            }
            log.warn("[cli-asr] 模型 {} 未取得本地文件，回退 CLI 自下载", model);
        } catch (Exception e) {
            log.warn("[cli-asr] 模型 {} 本地解析失败，回退 CLI 自下载: {}", model, e.getMessage());
        }
        return model;
    }

    /**
     * 转写并 以 默认 模型
     *
     * @param audio 音频
     * @return 文本
     * @throws Exception 失败
     */
    public String transcribe(byte[] audio) throws Exception {
        return doTranslate(audio);
    }

    /**
     * 归一化 纯文本 输出（去 行首 尾 空白 合并 多余 空行）
     *
     * @param raw 原始
     * @return 文本
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    /**
     * 字节 编码 文本 （ 供 调用方 复用 校验 等 场景 ）
     *
     * @param s 文本
     * @return 字节
     */
    public static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
