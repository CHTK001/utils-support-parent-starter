package com.chua.deeplearning.support.sherpa;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * sherpa-onnx 模型目录解析器。
 *
 * <p>负责把「模型标识 / 目录 / 单个模型文件」统一解析成一个模型目录，并按目录内文件
 * 命名自动判定模型族（{@link SherpaModelType} 与模型文件路径找齐，供
 * {@link #buildRecognizerConfig(Path, SherpaModelType, String, String, int, String)} 组装官方配置。</p>
 *
 * <p>解析顺序：</p>
 * <ol>
 *   <li>绝对路径且为目录 → 直接使用；</li>
 *   <li>绝对路径且为文件 → 使用其父目录；</li>
 *   <li>否则交给 {@link ModelRegistry#resolveModelPath(String)} 解析（类路径 / 模型根目录 / 下载缓存）；</li>
 *   <li>仍失败则回落到系统属性 {@code sherpa.onnx.model.dir} 指定的根目录下的同名子目录。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SherpaModelResolver {

    /**
     * 系统属性：sherpa-onnx 模型根目录（每个模型一个子目录）。
     */
    public static final String PROP_MODEL_DIR = "sherpa.onnx.model.dir";

    /**
     * 系统属性：计算线程数。
     */
    public static final String PROP_NUM_THREADS = "sherpa.onnx.num-threads";

    /**
     * 系统属性：推理 provider（cpu / cuda / coreml 等，取决于 native 库编译选项）。
     */
    public static final String PROP_PROVIDER = "sherpa.onnx.provider";

    /**
     * tokens 文件名。
     */
    private static final String TOKENS = "tokens.txt";

    /**
     * 工具类禁止实例化。
     */
    private SherpaModelResolver() {
    }

    /**
     * 解析模型目录。
     *
     * @param modelIdOrPath 模型标识或目录/文件路径，可为 {@code null}
     * @return 模型目录
     * @throws SherpaException 无法定位模型目录
     */
    public static Path resolveDirectory(String modelIdOrPath) {
        if (modelIdOrPath == null || modelIdOrPath.isBlank()) {
            throw new SherpaException("未指定 sherpa-onnx 模型（可通过 model(...) 或 -D"
                    + PROP_MODEL_DIR + " 指定模型目录）");
        }
        String text = modelIdOrPath.trim();
        Path direct = safePath(text);
        if (direct != null) {
            if (Files.isDirectory(direct)) {
                return direct;
            }
            if (Files.isRegularFile(direct)) {
                Path parent = direct.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    return parent;
                }
            }
        }

        // 本地模型根目录优先：运维通常已把 sherpa 模型预置在 <model.dir>/<模型标识> 下，
        // 不应因为「注册表未命中」而触发联网下载。
        String root = System.getProperty(PROP_MODEL_DIR);
        if (root != null && !root.isBlank()) {
            Path candidate = safePath(root.trim());
            if (candidate != null && Files.isDirectory(candidate)) {
                Path sub = candidate.resolve(text);
                if (Files.isDirectory(sub)) {
                    return sub;
                }
            }
        }

        // 其次尊重 ModelRegistry 中已登记的条目（仅已登记才解析，避免触发下载）
        if (ModelRegistry.isRegistered(text)) {
            Path fromRegistry = ModelRegistry.resolveModelPath(text);
            if (fromRegistry != null) {
                if (Files.isDirectory(fromRegistry)) {
                    return fromRegistry;
                }
                if (Files.isRegularFile(fromRegistry)) {
                    Path parent = fromRegistry.getParent();
                    if (parent != null && Files.isDirectory(parent)) {
                        return parent;
                    }
                }
            }
        }

        if (root != null && !root.isBlank()) {
            Path candidate = safePath(root.trim());
            if (candidate != null && Files.isDirectory(candidate)) {
                // 末级回落：把根目录本身当作模型目录（单模型部署的常见布局）
                return candidate;
            }
        }
        throw new SherpaException("无法定位 sherpa-onnx 模型目录: " + text
                + "（已尝试绝对路径、-D" + PROP_MODEL_DIR + " 与 ModelRegistry）");
    }

    /**
     * 把字符串安全转换为路径（非法路径返回 {@code null}，避免 Windows 下的 {@link java.nio.file.InvalidPathException}）。
     *
     * @param text 路径文本
     * @return 路径或 {@code null}
     */
    private static Path safePath(String text) {
        try {
            return Path.of(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 自动判定模型族：优先用显式 hint，其次按目录内文件命名推断。
     *
     * @param dir  模型目录
     * @param hint 显式指定的模型族短标识，可为 {@code null}
     * @return 模型族
     * @throws SherpaException 无法判定
     */
    public static SherpaModelType detect(Path dir, String hint) {
        SherpaModelType explicit = SherpaModelType.of(hint);
        if (explicit != null) {
            return explicit;
        }
        if (dir == null || !Files.isDirectory(dir)) {
            throw new SherpaException("模型目录不存在，无法判定模型族: " + dir);
        }
        List<String> names = listFileNames(dir);
        String dirName = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
        String joined = (String.join(" ", names) + " " + dirName).toLowerCase(Locale.ROOT);
        boolean hasEncoder = containsAny(names, "encoder");
        boolean hasDecoder = containsAny(names, "decoder");
        boolean hasJoiner = containsAny(names, "joiner");
        if (hasEncoder && hasDecoder && hasJoiner) {
            return SherpaModelType.TRANSDUCER;
        }
        if (hasEncoder && hasDecoder) {
            return SherpaModelType.WHISPER;
        }
        if (joined.contains("sensevoice") || joined.contains("sense-voice") || joined.contains("sense_voice")) {
            return SherpaModelType.SENSEVOICE;
        }
        if (joined.contains("paraformer")) {
            return SherpaModelType.PARAFORMER;
        }
        if (joined.contains("whisper")) {
            return SherpaModelType.WHISPER;
        }
        if (joined.contains("ctc")) {
            return SherpaModelType.ZIPFORMER_CTC;
        }
        if (joined.contains("transducer") || joined.contains("zipformer")) {
            return SherpaModelType.TRANSDUCER;
        }
        throw new SherpaException("无法判定模型族（目录内文件: " + names + "）；"
                + "请显式指定模型类型，如 model(\"sensevoice:/path/to/model-dir\")");
    }

    /**
     * 列出目录内（及一级子目录内）的全部文件名。
     *
     * @param dir 目录
     * @return 小写文件名列表
     */
    private static List<String> listFileNames(Path dir) {
        List<String> names = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return names;
        }
        try (Stream<Path> stream = Files.walk(dir, 2)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .forEach(names::add);
        } catch (IOException e) {
            throw new SherpaException("遍历模型目录失败: " + dir, e);
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    /**
     * 判断文件名集合中是否存在包含任一关键字的条目。
     *
     * @param names    文件名列表（小写）
     * @param keywords 关键字
     * @return true 表示命中
     */
    private static boolean containsAny(List<String> names, String... keywords) {
        for (String name : names) {
            for (String keyword : keywords) {
                if (name.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 在模型目录内查找匹配文件，优先 int8 量化版本。
     *
     * @param dir      模型目录
     * @param required 文件名必须包含的关键字，全部命中才算匹配
     * @return 命中的文件路径；未命中返回 {@code null}
     */
    public static Path findFile(Path dir, String... required) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        List<Path> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir, 2)) {
            stream.filter(Files::isRegularFile).forEach(hits::add);
        } catch (IOException e) {
            throw new SherpaException("遍历模型目录失败: " + dir, e);
        }
        List<Path> matched = new ArrayList<>();
        for (Path path : hits) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean ok = true;
            for (String keyword : required) {
                if (!name.contains(keyword.toLowerCase(Locale.ROOT))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                matched.add(path);
            }
        }
        if (matched.isEmpty()) {
            return null;
        }
        matched.sort(Comparator
                .comparingInt((Path path) -> isInt8(path) ? 0 : 1)
                .thenComparing(path -> path.getFileName().toString()));
        return matched.getFirst();
    }

    /**
     * 是否 int8 量化文件。
     *
     * @param path 文件路径
     * @return true 表示文件名含 int8
     */
    private static boolean isInt8(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).contains("int8");
    }

    /**
     * 查找 tokens 文件。
     *
     * @param dir 模型目录
     * @return tokens 路径；缺失返回 {@code null}
     */
    public static Path findTokens(Path dir) {
        Path exact = dir.resolve(TOKENS);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        return findFile(dir, "tokens");
    }

    /**
     * 组装离线识别器配置。
     *
     * @param dir        模型目录
     * @param type       模型族
     * @param language   语言代码（SenseVoice 使用，其它模型忽略），可为 {@code null}
     * @param provider   推理 provider（cpu / cuda），空则读系统属性或默认 cpu
     * @param numThreads 线程数，{@code <= 0} 时读系统属性或按 CPU 核数
     * @param modelType  显式 modelType（一般留空）
     * @return 官方离线识别器配置
     * @throws SherpaException 模型文件缺失
     */
    public static OfflineRecognizerConfig buildRecognizerConfig(Path dir, SherpaModelType type,
                                                                String language, String provider,
                                                                int numThreads, String modelType) {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new SherpaException("模型目录不存在: " + dir);
        }
        Path tokens = findTokens(dir);
        if (tokens == null) {
            throw new SherpaException("模型目录缺少 tokens.txt: " + dir);
        }
        String effectiveProvider = effectiveProvider(provider);
        int threads = effectiveThreads(numThreads);

        OfflineModelConfig.Builder model = OfflineModelConfig.builder()
                .setTokens(tokens.toString())
                .setNumThreads(threads)
                .setDebug(false)
                .setProvider(effectiveProvider);
        if (modelType != null && !modelType.isBlank()) {
            model.setModelType(modelType);
        }

        switch (type) {
            case PARAFORMER -> {
                Path model3 = findFile(dir, "model");
                if (model3 == null) {
                    throw new SherpaException("Paraformer 模型目录缺少 model*.onnx: " + dir);
                }
                model.setParaformer(OfflineParaformerModelConfig.builder()
                        .setModel(model3.toString())
                        .build());
            }
            case SENSEVOICE -> {
                Path model3 = findFile(dir, "model");
                if (model3 == null) {
                    throw new SherpaException("SenseVoice 模型目录缺少 model*.onnx: " + dir);
                }
                model.setSenseVoice(OfflineSenseVoiceModelConfig.builder()
                        .setModel(model3.toString())
                        .setLanguage(language == null ? "" : language)
                        .setInverseTextNormalization(true)
                        .build());
            }
            case WHISPER -> {
                Path encoder = findFile(dir, "encoder");
                Path decoder = findFile(dir, "decoder");
                if (encoder == null || decoder == null) {
                    throw new SherpaException("Whisper 模型目录缺少 encoder/decoder onnx: " + dir);
                }
                model.setWhisper(OfflineWhisperModelConfig.builder()
                        .setEncoder(encoder.toString())
                        .setDecoder(decoder.toString())
                        .setLanguage(language == null || language.isBlank() ? "en" : language)
                        .setTask("transcribe")
                        .build());
            }
            case TRANSDUCER -> {
                Path encoder = findFile(dir, "encoder");
                Path decoder = findFile(dir, "decoder");
                Path joiner = findFile(dir, "joiner");
                if (encoder == null || decoder == null || joiner == null) {
                    throw new SherpaException("Transducer 模型目录缺少 encoder/decoder/joiner onnx: " + dir);
                }
                model.setTransducer(OfflineTransducerModelConfig.builder()
                        .setEncoder(encoder.toString())
                        .setDecoder(decoder.toString())
                        .setJoiner(joiner.toString())
                        .build());
            }
            case ZIPFORMER_CTC -> {
                Path model3 = findFile(dir, "model");
                if (model3 == null) {
                    throw new SherpaException("Zipformer-CTC 模型目录缺少 model*.onnx: " + dir);
                }
                model.setZipformerCtc(OfflineZipformerCtcModelConfig.builder()
                        .setModel(model3.toString())
                        .build());
            }
            default -> throw new SherpaException("不支持的模型族: " + type);
        }

        return OfflineRecognizerConfig.builder()
                .setFeatureConfig(FeatureConfig.builder()
                        .setSampleRate(SherpaWavUtils.DEFAULT_SAMPLE_RATE)
                        .setFeatureDim(80)
                        .setDither(0F)
                        .build())
                .setOfflineModelConfig(model.build())
                .setDecodingMethod("greedy_search")
                .setMaxActivePaths(4)
                .build();
    }

    /**
     * 解析实际使用的 provider。
     *
     * @param provider 显式 provider，可为 {@code null}
     * @return provider 名称
     */
    public static String effectiveProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            return provider.trim();
        }
        String prop = System.getProperty(PROP_PROVIDER);
        return prop == null || prop.isBlank() ? "cpu" : prop.trim();
    }

    /**
     * 解析实际使用的线程数。
     *
     * @param numThreads 显式线程数，{@code <= 0} 表示自动
     * @return 线程数（至少 1）
     */
    public static int effectiveThreads(int numThreads) {
        if (numThreads > 0) {
            return numThreads;
        }
        String prop = System.getProperty(PROP_NUM_THREADS);
        if (prop != null && !prop.isBlank()) {
            try {
                int parsed = Integer.parseInt(prop.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法配置忽略，走 CPU 核数
            }
        }
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    /**
     * 检查模型目录是否看起来可用（存在 tokens 与至少一个 onnx）。
     *
     * @param dir 模型目录
     * @return true 表示结构完整
     */
    public static boolean isUsableModelDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        return findTokens(dir) != null && findFile(dir, ".onnx") != null;
    }
}
