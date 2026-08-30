package com.chua.deeplearning.support.engine;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KV cache 版 ONNX decoder 通用推理解码器。
 *
 * <p>适用于 transformers.js / optimum 导出的带 past/present 缓存的 decoder 模型
 * （如 gemma/qwen/llama 系列的 ONNX 导出）。此类模型输入含
 * {@code past_key_values.N.key/value}（N = 层数），输出含 {@code present.N.key/value}，
 * 每步只需传入 1 个新 token 与上一步缓存，避免重复计算整个上下文。</p>
 *
 * <p>使用方式：
 * <pre>{@code
 * try (KvCacheDecoder decoder = KvCacheDecoder.of(env, session)) {
 *     // 首步：完整 prompt（含模板）
 *     float[] logits = decoder.step(promptIds, ones(promptIds.length));
 *     int next = argmax(logits);
 *     tokens.add(next);
 *     // 后续步：只需 1 个新 token，attention_mask 逐位加长
 *     while (...) {
 *         logits = decoder.step(new long[]{next}, ones(decoder.totalSeqLen()));
 *         next = argmax(logits);
 *         ...
 *     }
 * }
 * }</pre>
 * </p>
 *
 * <p>past 张量生命周期由本类管理：每步自动从 {@code present} 复制 fp16 数据
 * 并重建为下一步的 past，旧张量即时关闭；{@link #close()} 释放全部资源。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class KvCacheDecoder implements AutoCloseable {

    /** 空 past 的 batch / kv-heads 维度（gemma-3-270m = 1 head，head_dim 256） */
    private static final long BATCH = 1L;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final int numLayers;
    private final long kvHeads;
    private final long headDim;

    /** 当前 past 张量（每层 key/value 各一个，顺序 key0,value0,key1,value1,...），null=首步 */
    private List<OnnxTensor> past;
    /** 已缓存的 token 数（首步为 0） */
    private int pastSeqLen;

    private KvCacheDecoder(OrtEnvironment env, OrtSession session, int numLayers, long kvHeads, long headDim) {
        this.env = env;
        this.session = session;
        this.numLayers = numLayers;
        this.kvHeads = kvHeads;
        this.headDim = headDim;
    }

    /**
     * 判断 session 是否为 KV cache 版模型（输入含 {@code past_key_values.0.key}）。
     *
     * @param session ORT 会话
     * @return true 表示 KV cache 版
     */
    public static boolean isKvCacheModel(OrtSession session) {
        try {
            for (Map.Entry<String, ai.onnxruntime.NodeInfo> e : session.getInputInfo().entrySet()) {
                if (e.getKey().startsWith("past_key_values.")) {
                    return true;
                }
            }
        } catch (OrtException ex) {
            log.warn("[kv-cache] 探测模型输入失败: {}", ex.getMessage());
        }
        return false;
    }

    /**
     * 创建 KV cache 解码器（自动探测层数 / kv-heads / head_dim）。
     *
     * @param env     ORT 环境
     * @param session ORT 会话（须为 KV cache 版，否则抛异常）
     * @return 解码器实例
     * @throws OrtException 非 KV cache 版或探测失败
     */
    public static KvCacheDecoder of(OrtEnvironment env, OrtSession session) throws OrtException {
        int layers = 0;
        long kvHeads = 0;
        long headDim = 0;
        for (Map.Entry<String, ai.onnxruntime.NodeInfo> e : session.getInputInfo().entrySet()) {
            String name = e.getKey();
            if (!name.startsWith("past_key_values.")) {
                continue;
            }
            String rest = name.substring("past_key_values.".length());
            int dot = rest.indexOf('.');
            if (dot < 0) {
                continue;
            }
            int layer = Integer.parseInt(rest.substring(0, dot));
            ai.onnxruntime.NodeInfo nodeInfo = e.getValue();
            long[] shape = nodeInfo.getInfo() instanceof ai.onnxruntime.TensorInfo
                    ? ((ai.onnxruntime.TensorInfo) nodeInfo.getInfo()).getShape()
                    : new long[0];
            layers = Math.max(layers, layer + 1);
            if (shape.length >= 4) {
                kvHeads = shape[1];
                headDim = shape[3];
            }
        }
        if (layers == 0) {
            throw new OrtException("非 KV cache 版模型，输入不含 past_key_values.*");
        }
        log.info("[kv-cache] 探测到 KV cache 模型: layers={}, kv_heads={}, head_dim={}", layers, kvHeads, headDim);
        return new KvCacheDecoder(env, session, layers, kvHeads, headDim);
    }

    /**
     * 执行一步推理。
     *
     * @param inputIds       本次输入 token（首步=完整 prompt；后续步=1 个新 token）
     * @param attentionMask  注意力掩码（长度 = inputIds.length + pastSeqLen，全 1）
     * @return 最后位置的 logits（词表大小）
     * @throws OrtException 推理异常
     */
    public float[] step(long[] inputIds, long[] attentionMask) throws OrtException {
        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), new long[]{BATCH, inputIds.length}));
        inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), new long[]{BATCH, attentionMask.length}));

        long[] pastShape = {BATCH, kvHeads, pastSeqLen, headDim};
        for (int l = 0; l < numLayers; l++) {
            if (past == null) {
                inputs.put("past_key_values." + l + ".key", createEmptyFp16(pastShape));
                inputs.put("past_key_values." + l + ".value", createEmptyFp16(pastShape));
            } else {
                inputs.put("past_key_values." + l + ".key", past.get(l * 2));
                inputs.put("past_key_values." + l + ".value", past.get(l * 2 + 1));
            }
        }

        try (OrtSession.Result result = session.run(inputs)) {
            // it 版为 fp16 模型：输出 logits 是 FLOAT16，ORT 的 getValue() 在转 ShortBuffer 时
            // 会抛 HeapByteBuffer cast 异常，因此直接从张量读原始字节并手动解码 half → float
            OnnxTensor outTensor = (OnnxTensor) result.get(0);
            long[] outShape = outTensor.getInfo().getShape();
            int seq = (int) outShape[1];
            int vocab = (int) outShape[2];
            float[][][] logits = new float[1][seq][vocab];
            ByteBuffer bb = outTensor.getByteBuffer().duplicate();
            bb.order(ByteOrder.LITTLE_ENDIAN);
            for (int s = 0; s < seq; s++) {
                for (int v = 0; v < vocab; v++) {
                    logits[0][s][v] = halfToFloat(bb.getShort());
                }
            }
            int last = seq - 1;
            float[] lastLogits = logits[0][last];

            // 重建 past：从 present 复制 fp16 数据（result 关闭后张量不可用，故拷贝）
            List<OnnxTensor> newPast = new ArrayList<>(numLayers * 2);
            for (int l = 0; l < numLayers; l++) {
                int layerIdx = l;
                OnnxTensor pk = (OnnxTensor) result.get("present." + l + ".key").orElseThrow(
                        () -> new OrtException("缺少输出 present." + layerIdx + ".key"));
                OnnxTensor pv = (OnnxTensor) result.get("present." + l + ".value").orElseThrow(
                        () -> new OrtException("缺少输出 present." + layerIdx + ".value"));
                newPast.add(copyFp16(pk));
                newPast.add(copyFp16(pv));
            }
            // 关闭旧 past（首步为空张量，同样关闭）
            if (past != null) {
                for (OnnxTensor t : past) {
                    t.close();
                }
            }
            past = newPast;
            pastSeqLen = inputIds.length + pastSeqLen;
            return lastLogits;
        }
    }

    /**
     * 当前上下文总长度（pastSeqLen + 本次输入长度）。
     *
     * @return 总 token 数
     */
    public int totalSeqLen() {
        return pastSeqLen;
    }

    @Override
    public void close() {
        if (past != null) {
            for (OnnxTensor t : past) {
                try {
                    t.close();
                } catch (Exception ignored) {
                }
            }
            past = null;
        }
    }

    /**
     * 创建空 fp16 张量（首步 past 占位）。
     * <p>ORT Java API 对 FLOAT16 类型要求用 {@code ShortBuffer} 创建
     * （每元素 2 字节 = 1 个 short），传 ByteBuffer 会在 OrtUtil.prepareBuffer
     * 抛 HeapByteBuffer→ShortBuffer 强转异常。</p>
     */
    private OnnxTensor createEmptyFp16(long[] shape) throws OrtException {
        int elems = (int) (shape[0] * shape[1] * shape[2] * shape[3]);
        return OnnxTensor.createTensor(env, ShortBuffer.allocate(elems), shape, OnnxJavaType.FLOAT16);
    }

    /**
     * IEEE 754 half (fp16) 转 float。
     *
     * @param halfBits fp16 的 16 位原始值
     * @return 转换后的 float
     */
    private static float halfToFloat(short halfBits) {
        int h = halfBits & 0xFFFF;
        int sign = (h >> 15) & 0x1;
        int exp = (h >> 10) & 0x1F;
        int mant = h & 0x3FF;
        if (exp == 0) {
            if (mant == 0) {
                return sign == 0 ? 0.0f : -0.0f;
            }
            // 次正规数：2^-14 * mant/1024
            float v = (float) mant / 1024.0f * (float) Math.pow(2, -14);
            return sign == 0 ? v : -v;
        }
        if (exp == 0x1F) {
            return mant == 0
                    ? (sign == 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY)
                    : Float.NaN;
        }
        float v = (1.0f + (float) mant / 1024.0f) * (float) Math.pow(2, exp - 15);
        return sign == 0 ? v : -v;
    }

    /**
     * 复制 fp16 张量数据到新张量（避免 result 关闭后数据失效）。
     * <p>FLOAT16 输出张量在 ORT Java API 中通过 {@code getValue()} 返回 ShortBuffer
     * （每元素 2 字节 = 1 个 short），复制后同样以 ShortBuffer 重建张量。</p>
     */
    private OnnxTensor copyFp16(OnnxTensor src) throws OrtException {
        long[] shape = src.getInfo().getShape();
        ShortBuffer buf = (ShortBuffer) src.getValue();
        ShortBuffer copy = ShortBuffer.allocate(buf.remaining());
        copy.put(buf.duplicate());
        copy.flip();
        return OnnxTensor.createTensor(env, copy, shape, OnnxJavaType.FLOAT16);
    }
}
