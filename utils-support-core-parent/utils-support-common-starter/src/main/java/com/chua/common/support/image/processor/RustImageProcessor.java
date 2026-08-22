package com.chua.common.support.image.processor;

import com.chua.common.support.image.ImageProcessor;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiOrder;
import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Rust 原生动态库的图像处理器（v0.2 优化版）
 *
 * <p>通过 Java FFM API（{@code java.lang.foreign}，JDK 22+）加载
 * {@code image_processor.dll/.so/.dylib} 并调用：
 * <ul>
 *   <li>{@code process_image(input, len, json)} — 处理图像，返回 malloc 内存（前4字节为长度，后为图像数据）</li>
 *   <li>{@code process_image_shared(input, len, json, output, capacity)} — 共享内存版，直接写入预分配缓冲区，零 malloc/free</li>
 *   <li>{@code free_result(ptr)} — 释放 malloc 内存</li>
 * </ul>
 *
 * <p>v0.2 优化：
 * <ul>
 *   <li>resize 使用 fast_image_resize（SIMD: SSE4.1/AVX2/NEON）</li>
 *   <li>erode/dilate/binarize/rotate 使用 rayon 多线程并行</li>
 *   <li>PNG 编码使用 CompressionType::Fast + FilterType::Sub（比默认快 3-5x）</li>
 *   <li>新增共享内存协议 process_image_shared，消除 malloc/free 开销</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("image-processor")
@SpiOrder(100)
public class RustImageProcessor implements ImageProcessor {

    /**
     * 是否已成功加载原生库
     */
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    /**
     * 原生链接器
     */
    private static final Linker LINKER = Linker.nativeLinker();

    /**
     * process_image 函数句柄（malloc 版）
     */
    private static MemorySegment processImage;

    /**
     * process_image_shared 函数句柄（共享内存版）
     */
    private static MemorySegment processImageShared;

    /**
     * free_result 函数句柄
     */
    private static MemorySegment freeResult;

    /**
     * 共享输出缓冲区（预分配，避免每次 malloc/free）
     * 初始 1MB，按需增长
     */
    private static long sharedBufferCapacity = 1024 * 1024;

    /**
     * 是否使用共享内存协议
     */
    private static boolean useSharedBuffer = false;

    static {
        try {
            NativeLoader.of("image-processor")
                    .toTarget(NativeUtils.tempRoot().resolve("image-processor"))
                    .glob("*image_processor*")
                    .load();
            Path libPath = NativeUtils.tempRoot().resolve("image-processor").resolve(System.mapLibraryName("image_processor"));
            SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, Arena.ofAuto());
            processImage = lookup.find("process_image").orElseThrow(() -> new IllegalStateException("未找到 process_image"));
            freeResult = lookup.find("free_result").orElseThrow(() -> new IllegalStateException("未找到 free_result"));
            // 共享内存版为可选功能
            try {
                processImageShared = lookup.find("process_image_shared").orElse(MemorySegment.NULL);
                if (!processImageShared.equals(MemorySegment.NULL)) {
                    useSharedBuffer = true;
                }
            } catch (Exception e) {
                // v0.1 库可能没有此函数，回退到 malloc 版
            }
            LOADED.set(true);
            System.out.println("[RustImageProcessor] 原生库加载成功" + (useSharedBuffer ? "（共享内存模式）" : "（malloc 模式）"));
        } catch (Throwable e) {
            System.err.println("[RustImageProcessor] 原生库加载失败，回退到 AWT: " + e.getMessage());
        }
    }

    /**
     * 使用 Rust 原生动态库对图像进行处理。
     *
     * <p>将原始图像字节与操作参数序列化为 JSON 后，通过 FFM API 调用原生
     * {@code process_image}（或共享内存版 {@code process_image_shared}）函数完成处理，
     * 并将返回的图像字节回传。处理前会校验原生库是否已成功加载，未加载时抛出异常。
     *
     * <p>处理协议说明：
     * <ul>
     *   <li>若原生库支持共享内存协议（{@code process_image_shared} 存在），则预分配输出缓冲区，
     *       由原生函数直接写入，避免 malloc/free 开销；当缓冲区容量不足时会自动扩容后重试一次。</li>
     *   <li>否则回退到 malloc 协议（{@code process_image}），原生函数内部 malloc 内存，
     *       布局为「前 4 字节小端长度 + 图像数据」，本方法读取后调用 {@code free_result} 释放。</li>
     * </ul>
     *
     * @param imageData 原始图像字节数组（如 PNG/JPEG 编码后的二进制数据），不允许为 {@code null}
     * @param operation 操作类型标识，会被作为 {@code "op"} 字段写入传给原生库的 JSON，
     *                  例如 resize、erode、dilate、binarize、rotate 等
     * @param params    操作所需的参数键值对（如宽高、半径、阈值、角度等）；键名会原样写入 JSON，
     *                  数值与布尔值直接输出，其它类型以字符串形式输出；允许为 {@code null}
     * @return 处理后的图像字节数组（与原输入同格式或目标格式编码后的二进制数据）
     * @throws IllegalStateException 若原生库未加载，或原生函数返回空指针、非法长度、处理失败
     * @author CH
     * @since 4.0.0.42
     */
    @Override
    public byte[] process(byte[] imageData, String operation, Map<String, Object> params) {
        if (!LOADED.get()) {
            throw new IllegalStateException("Rust 原生库未加载");
        }
        String json = toJson(operation, params);
        try (Arena arena = Arena.ofConfined()) {
            // 分配输入缓冲区
            MemorySegment input = arena.allocate(imageData.length);
            MemorySegment.copy(imageData, 0, input, ValueLayout.JAVA_BYTE, 0, imageData.length);
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            MemorySegment paramJson = arena.allocate(jsonBytes.length + 1);
            MemorySegment.copy(jsonBytes, 0, paramJson, ValueLayout.JAVA_BYTE, 0, jsonBytes.length);
            paramJson.set(ValueLayout.JAVA_BYTE, jsonBytes.length, (byte) 0);

            // 优先使用共享内存协议
            if (useSharedBuffer) {
                return processShared(arena, input, imageData.length, paramJson);
            }
            // 回退到 malloc 版
            return processMalloc(arena, input, imageData.length, paramJson);
        } catch (Throwable e) {
            throw new IllegalStateException("Rust 图像处理失败", e);
        }
    }

    /**
     * 使用 malloc 协议处理图像（v0.1 兼容模式）
     */
    private byte[] processMalloc(Arena arena, MemorySegment input, long len, MemorySegment paramJson) throws Throwable {
        MemorySegment result = (MemorySegment) LINKER.downcallHandle(
                processImage,
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
                .invokeExact(input, len, paramJson);
        return readResult(result);
    }

    /**
     * 使用共享内存协议处理图像（v0.2 零 malloc/free 模式）
     *
     * <p>流程：
     * 1. 在 Arena 中预分配输出缓冲区
     * 2. 调用 process_image_shared 直接写入缓冲区
     * 3. 返回值 > 0 表示写入字节数，直接从缓冲区读取
     * 4. 返回值 < 0 表示容量不足，|返回值| 为所需字节数，扩容后重试
     * 5. 返回值 = 0 表示处理失败
     */
    private byte[] processShared(Arena arena, MemorySegment input, long len, MemorySegment paramJson) throws Throwable {
        long capacity = sharedBufferCapacity;
        MemorySegment outputBuf = arena.allocate(capacity);

        long result = (long) LINKER.downcallHandle(
                processImageShared,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
                .invokeExact(input, len, paramJson, outputBuf, capacity);

        if (result > 0) {
            // 成功写入，直接从缓冲区读取
            byte[] bytes = new byte[(int) result];
            MemorySegment.copy(outputBuf, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) result);
            return bytes;
        } else if (result < 0) {
            // 容量不足，扩容后重试
            long required = -result;
            sharedBufferCapacity = required + 64 * 1024; // 多分配 64KB 避免频繁扩容
            MemorySegment largerBuf = arena.allocate(sharedBufferCapacity);
            long result2 = (long) LINKER.downcallHandle(
                    processImageShared,
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
                    .invokeExact(input, len, paramJson, largerBuf, sharedBufferCapacity);
            if (result2 > 0) {
                byte[] bytes = new byte[(int) result2];
                MemorySegment.copy(largerBuf, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) result2);
                return bytes;
            }
            throw new IllegalStateException("Rust 共享内存处理失败（重试后 result=" + result2 + "）");
        } else {
            // result == 0，处理失败
            throw new IllegalStateException("Rust 共享内存处理失败（返回 0）");
        }
    }

    /**
     * 读取原生返回结果并释放内存（malloc 版专用）
     *
     * <p>原生函数返回指向 malloc 内存的指针，布局为「前 4 字节小端长度 + 图像数据」。
     *
     * @param result 指向 malloc 内存的指针
     * @return 图像字节
     */
    private byte[] readResult(MemorySegment result) {
        if (result == null || result.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Rust 返回空指针");
        }
        try {
            int len = result.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
            if (len <= 0 || len > 64 * 1024 * 1024) {
                throw new IllegalStateException("Rust 返回非法长度: " + len);
            }
            MemorySegment data = result.reinterpret(4L + len);
            byte[] bytes = data.asSlice(4, len).toArray(ValueLayout.JAVA_BYTE);
            return bytes;
        } finally {
            try {
                LINKER.downcallHandle(
                        freeResult,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
                        .invokeExact(result);
            } catch (Throwable ignored) {
                // 忽略释放异常
            }
        }
    }

    /**
     * 将操作与参数序列化为 JSON 字符串
     *
     * @param operation 操作类型
     * @param params    参数
     * @return JSON 字符串
     */
    private String toJson(String operation, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder("{\"op\":\"");
        sb.append(operation).append('"');
        if (params != null) {
            params.forEach((k, v) -> {
                sb.append(",\"").append(k).append("\":");
                if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append('"').append(v).append('"');
                }
            });
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 返回该图像处理器的标识名称。
     *
     * <p>该名称用于 SPI 场景下区分不同的 {@link ImageProcessor} 实现，
     * 例如 {@code "rust"} 表示底层由 Rust 原生动态库提供加速能力。
     *
     * @return 处理器标识名称，固定为 {@code "rust"}
     * @author CH
     * @since 4.0.0.42
     */
    @Override
    public String name() {
        return "rust";
    }

    /**
     * 判断该图像处理器当前是否可用。
     *
     * <p>当且仅当 Rust 原生动态库在类初始化时成功加载（{@code LOADED} 被置为 {@code true}）时返回
     * {@code true}；若原生库加载失败（例如缺少对应平台的 {@code image_processor} 动态库），
     * 返回 {@code false}，调用方应回退到 AWT 等纯 Java 实现。
     *
     * @return 原生库已加载且可用返回 {@code true}，否则返回 {@code false}
     * @author CH
     * @since 4.0.0.42
     */
    @Override
    public boolean available() {
        return LOADED.get();
    }
}