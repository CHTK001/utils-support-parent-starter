package com.chua.common.support.image;

import java.util.Map;

/**
* 图像处理器 SPI 接口
*
* <p>定义统一的图像处理能力，支持按 {@link java.util.ServiceLoader} 机制注册多种实现：
* <ul>
*   <li>Rust 原生实现（{@code @SpiOrder(100)}）— 通过 FFM API 加载 {@code image_processor.dll/.so/.dylib}，
*       性能最优，支持 SIMD 加速（resize）和多线程并行（erode/dilate/binarize/rotate）</li>
*   <li>OpenCV 原生实现（{@code @SpiOrder(50)}）— 通过 {@code org.openpnp:opencv} 加载 OpenCV 原生库，
*       支持 Canny/Sobel 边缘检测和模板匹配</li>
*   <li>JDK AWT 实现（{@code @SpiOrder(-100)}）— 无原生依赖，作为兜底实现，
*       支持 14 种操作包括形态学操作（erode/dilate）和 Sobel 边缘检测</li>
* </ul>
*
* <p>默认通过 {@link ImageProcessors#getProcessor()} 获取，按 @SpiOrder 自动降级。
* 也可通过 {@link ImageProcessors#from(byte[])} 流畅 API 链式调用多个操作。</p>
*
* <h3>支持的操作类型</h3>
* <table>
*   <tr><th>操作</th><th>参数</th><th>说明</th></tr>
*   <tr><td>{@code resize}</td><td>width, height</td><td>缩放图像至指定尺寸</td></tr>
*   <tr><td>{@code grayscale}</td><td>无</td><td>转为灰度图像</td></tr>
*   <tr><td>{@code rotate}</td><td>angle（度）</td><td>旋转图像，支持任意角度</td></tr>
*   <tr><td>{@code crop}</td><td>x, y, width, height</td><td>裁剪图像</td></tr>
*   <tr><td>{@code blur}</td><td>sigma（模糊半径）</td><td>高斯模糊</td></tr>
*   <tr><td>{@code flip}</td><td>axis（h=水平 / v=垂直）</td><td>翻转图像</td></tr>
*   <tr><td>{@code brightness}</td><td>value（[-255,255]）</td><td>调整亮度</td></tr>
*   <tr><td>{@code contrast}</td><td>value（[-100,100]）</td><td>调整对比度</td></tr>
*   <tr><td>{@code border}</td><td>width, color</td><td>绘制边框</td></tr>
*   <tr><td>{@code binarize}</td><td>threshold（0~255）</td><td>二值化</td></tr>
*   <tr><td>{@code denoise}</td><td>radius（邻域半径）</td><td>降噪（中值滤波）</td></tr>
*   <tr><td>{@code erode}</td><td>kernel（核尺寸）</td><td>腐蚀（形态学）</td></tr>
*   <tr><td>{@code dilate}</td><td>kernel（核尺寸）</td><td>膨胀（形态学）</td></tr>
*   <tr><td>{@code edge}</td><td>direction（h/v/both）</td><td>边缘检测（Sobel 算子）</td></tr>
*   <tr><td>{@code templateMatch}</td><td>template, method, threshold, maxCount, drawMatch</td><td>模板匹配（仅 OpenCV）</td></tr>
* </table>
*
* <h3>通用参数</h3>
* <ul>
*   <li>{@code format} — 输出格式（png / jpeg），默认 png</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
* @see ImageProcessors
* @see ImageProcessorUtils
 */
public interface ImageProcessor {

    /**
    * 处理图像
    *
    * <p>根据操作类型和参数对图像进行处理，返回处理后的图像字节。
    * 输入和输出均为图像编码后的字节数组（PNG / JPEG 等格式）。</p>
    *
    * @param imageData 原始图像字节（PNG / JPEG 等格式）
    * @param operation 操作类型：resize / grayscale / rotate / crop / blur / flip /
    *                  brightness / contrast / border / binarize / denoise / erode / dilate / edge /
    *                  templateMatch（仅 OpenCV）
    * @param params    操作参数，如 resize 的 width/height、rotate 的 angle、输出 format 等；
    *                  参数值类型为 Object，内部通过 {@link ImageProcessorUtils#toInt(Object, int)} 转换
    * @return 处理后的图像字节
    */
    byte[] process(byte[] imageData, String operation, Map<String, Object> params);

    /**
    * 批量处理图像
    *
    * <p>减少跨语言调用次数，提升批量场景吞吐。默认逐张调用 {@link #process}，
    * 原生实现可重写为一次 FFI 调用处理整批。</p>
    *
    * @param images    原始图像字节数组
    * @param operation 操作类型
    * @param params    操作参数
    * @return 处理后的图像字节数组
    */
    default byte[][] processBatch(byte[][] images, String operation, Map<String, Object> params) {
        byte[][] results = new byte[images.length][];
        for (int i = 0; i < images.length; i++) {
            results[i] = process(images[i], operation, params);
        }
        return results;
    }

    /**
    * 处理器名称，用于日志与优先级判断
    *
    * @return 处理器名称，如 "rust" / "opencv" / "jdk"
    */
    String name();

    /**
    * 处理器是否可用
    *
    * <p>原生实现需动态库加载成功后返回 true，否则返回 false 以便回退。
    * JDK AWT 实现始终返回 true。</p>
    *
    * @return true 可用
    */
    boolean available();
}
