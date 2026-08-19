package com.chua.common.support.image.processor;

import com.chua.common.support.image.ImageProcessor;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiOrder;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.InputStream;
import java.io.IOException;
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
            String libName = System.mapLibraryName("image_processor");
            String libPath = extractNativeLib(libName);
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
     * 提取并定位原生库路径
     *
     * <p>优先从 classpath 的 {@code /native/} 目录解压到临时目录，
     * 其次尝试直接从 {@code java.library.path} 加载。
     *
     * @param libName 库文件名（如 image_processor.dll）
     * @return 库文件的绝对路径
     */
    private static String extractNativeLib(String libName) {
        // 1. 优先从 classpath 的平台子目录解压（如 /native/windows-x86_64/image_processor.dll）
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        String platformDir = getPlatformDir(osName, osArch);
        if (platformDir != null) {
            try (InputStream in = RustImageProcessor.class.getResourceAsStream("/native/" + platformDir + "/" + libName)) {
                if (in != null) {
                    Path tmp = Files.createTempFile("native_", "_" + libName);
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    tmp.toFile().deleteOnExit();
                    return tmp.toString();
                }
            } catch (IOException e) {
                // 忽略，尝试下一级
            }
        }
        // 2. 其次从 classpath 的 /native/ 根目录解压
        try (InputStream in = RustImageProcessor.class.getResourceAsStream("/native/" + libName)) {
            if (in != null) {
                Path tmp = Files.createTempFile("native_", "_" + libName);
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                return tmp.toString();
            }
        } catch (IOException e) {
            // 忽略，尝试 library path
        }
        return libName;
    }

    /**
     * 根据 OS 和架构确定平台子目录名
     *
     * @param osName 操作系统名称（如 "windows 10"、"linux"）
     * @param osArch 架构名称（如 "amd64"、"aarch64"）
     * @return 平台目录名（如 "windows-x86_64"），无法确定时返回 null
     */
    private static String getPlatformDir(String osName, String osArch) {
        String os;
        if (osName.contains("win")) os = "windows";
        else if (osName.contains("linux")) os = "linux";
        else if (osName.contains("mac") || osName.contains("darwin")) os = "macos";
        else return null;

        String arch;
        if (osArch.matches("amd64|x86_64")) arch = "x86_64";
        else if (osArch.matches("aarch64|arm64")) arch = "aarch64";
        else return null;

        return os + "-" + arch;
    }

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

    @Override
    public String name() {
        return "rust";
    }

    @Override
    public boolean available() {
        return LOADED.get();
    }
}