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
 * 基于 Rust 原生动态库的图像处理器
 *
 * <p>通过 Java FFM API（{@code java.lang.foreign}，JDK 22+）加载
 * {@code libimage_processor.so} 并调用：
 * <ul>
 *   <li>{@code process_image(input, len, json)} — 处理图像，返回 malloc 内存（前4字节为长度，后为图像数据）</li>
 *   <li>{@code free_result(ptr)} — 释放上述内存</li>
 * </ul>
 * 加载失败时 {@link #available()} 返回 false，上层自动回退到 {@link JdkImageProcessor}。
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
     * process_image 函数句柄
     */
    private static MemorySegment processImage;

    /**
     * free_result 函数句柄
     */
    private static MemorySegment freeResult;

    static {
        try {
            String libName = System.mapLibraryName("image_processor");
            String libPath = extractNativeLib(libName);
            SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, Arena.ofAuto());
            processImage = lookup.find("process_image").orElseThrow(() -> new IllegalStateException("未找到 process_image"));
            freeResult = lookup.find("free_result").orElseThrow(() -> new IllegalStateException("未找到 free_result"));
            LOADED.set(true);
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
     * @param libName 库文件名（如 libimage_processor.so）
     * @return 库文件的绝对路径
     */
    private static String extractNativeLib(String libName) {
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

    @Override
    public byte[] process(byte[] imageData, String operation, Map<String, Object> params) {
        if (!LOADED.get()) {
            throw new IllegalStateException("Rust 原生库未加载");
        }
        String json = toJson(operation, params);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(imageData.length);
            MemorySegment.copy(imageData, 0, input, ValueLayout.JAVA_BYTE, 0, imageData.length);
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            MemorySegment paramJson = arena.allocate(jsonBytes.length + 1);
            MemorySegment.copy(jsonBytes, 0, paramJson, ValueLayout.JAVA_BYTE, 0, jsonBytes.length);
            paramJson.set(ValueLayout.JAVA_BYTE, jsonBytes.length, (byte) 0);
            MemorySegment result = (MemorySegment) LINKER.downcallHandle(
                    processImage,
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
                    .invokeExact(input, (long) imageData.length, paramJson);
            return readResult(result);
        } catch (Throwable e) {
            throw new IllegalStateException("Rust 图像处理失败", e);
        }
    }

    /**
     * 读取原生返回结果并释放内存
     *
     * <p>原生函数返回指向 malloc 内存的指针，布局为「前 4 字节小端长度 + 图像数据」。
     * 返回的 {@link MemorySegment} 未限定大小（byteSize=0），需先 {@link MemorySegment#reinterpret(long)} 扩展后再读取。
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
