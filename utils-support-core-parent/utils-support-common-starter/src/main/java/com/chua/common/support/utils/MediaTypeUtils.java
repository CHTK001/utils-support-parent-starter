package com.chua.common.support.utils;

import com.google.common.net.MediaType;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
* 媒体类型（MIME 类型）工具类，基于 Guava {@link com.google.common.net.MediaType} 提供文件名与 MIME 字符串之间的解析能力。
*
* <h3>支持的 MIME 类型</h3>
* <ul>
*   <li><b>图片类型：</b>image/jpeg、image/png、image/gif、image/bmp、image/webp、image/svg+xml、image/tiff 等</li>
*   <li><b>文本类型：</b>text/html、text/plain、text/css、text/javascript、text/xml、text/csv 等</li>
*   <li><b>应用类型：</b>application/json、application/xml、application/pdf、application/zip、application/octet-stream、
*       application/x-www-form-urlencoded、application/msword、application/vnd.ms-excel 等</li>
*   <li><b>音视频类型：</b>audio/mpeg、audio/ogg、video/mp4、video/webm 等</li>
*   <li><b>多部分类型：</b>multipart/form-data、multipart/alternative 等</li>
*   <li><b>其他类型：</b>font/woff、font/woff2、message/rfc822 等</li>
*   <li><b>兜底类型：</b>{@link MediaType#ANY_TYPE} —— 表示未知或无法识别的媒体类型</li>
* </ul>
*
* <h3>使用场景</h3>
* <ul>
*   <li><b>HTTP 响应头设置：</b>根据文件扩展名动态设置 Content-Type 响应头</li>
*   <li><b>文件上传校验：</b>根据文件名判断用户上传文件的 MIME 类型，进行白名单/黑名单过滤</li>
*   <li><b>资源路由分发：</b>根据请求的 Accept 头或文件扩展名匹配对应的处理器</li>
*   <li><b>邮件附件标识：</b>设置邮件附件的 MIME 类型确保客户端正确渲染</li>
*   <li><b>静态资源服务：</b>为 CDN 或 Web 服务器提供正确的 Content-Type 映射</li>
* </ul>
*
* <h3>方法选择指南</h3>
* <table border="1">
*   <tr><th>方法</th><th>输入</th><th>失败行为</th><th>适用场景</th></tr>
*   <tr><td>{@link #getMediaType(String)}</td><td>MIME 字符串</td><td>返回 Optional.empty()（实际不会失败，Guava 强制解析）</td><td>已知合法 MIME 字符串的解析</td></tr>
*   <tr><td>{@link #getMediaTypeNullable(String)}</td><td>文件名</td><td>返回 {@link MediaType#ANY_TYPE}</td><td>需要空安全的文件名推断</td></tr>
*   <tr><td>{@link #getNoneMediaType(String)}</td><td>文件名</td><td>返回 {@link MediaType#ANY_TYPE}</td><td>与 getMediaTypeNullable 行为等价，内部实现路径不同</td></tr>
*   <tr><td>{@link #getMediaTypes(String)}</td><td>文件名</td><td>返回包含单个元素的列表（不是空列表）</td><td>需以 List 形式消费结果时使用</td></tr>
*   <tr><td>{@link #parse(String)}</td><td>文件名</td><td>返回 {@link MediaType#ANY_TYPE}</td><td>{@link #getMediaTypeNullable(String)} 的快捷别名</td></tr>
* </table>
*
* @author CH
* @since 1.0
* @see com.google.common.net.MediaType
 */
public class MediaTypeUtils {
    /** 创建 media类型工具 实例 */
    private MediaTypeUtils() {
        //
    }

    /**
    * 根据 MIME 类型字符串解析为 {@link MediaType} 实例，包装在 {@link Optional} 中返回。
    *
    * <p>该方法直接调用 {@link MediaType#parse(String)}，
    * Guava 解析器不会抛出异常，即使输入看似不合法的字符串也会返回有效结果。
    * 因此 Optional 永远不会为空，但保留 Optional 包装是为了保持 API 语义上的一致性。
    *
    * <p><b>输入示例：</b>
    * <ul>
    *   <li>{@code "image/jpeg"} —— 返回 image/jpeg</li>
    *   <li>{@code "text/html;charset=utf-8"} —— 返回包含 charset 参数的 text/html</li>
    *   <li>{@code "application/json"} —— 返回 application/json</li>
    * </ul>
    *
    * <h3>与其他方法的区别</h3>
    * <ul>
    *   <li><b>输入粒度：</b>本方法接收 MIME 字符串，而 {@link #getMediaTypeNullable(String)}、
    *       {@link #getNoneMediaType(String)}、{@link #getMediaTypes(String)} 接收文件名</li>
    *   <li><b>空安全性：</b>本方法返回 Optional，即使输入为空字符串也不会返回 null，
    *       而文件名系列方法在匹配失败时返回 {@link MediaType#ANY_TYPE}</li>
    * </ul>
    *
    * @param mediaType MIME 类型字符串，格式为 "{@code type/subtype}" 或 "{@code type/subtype;param=value}"，
    *                  例如 {@code "image/jpeg"} 或 {@code "text/html;charset=utf-8"}；
    * 不允许为 {@code null}，Guava 解析 方法不接受 空 输入
    * @return 包含解析结果的 {@link Optional}；实际不会出现 {@code Optional.empty()}，
    *         但 API 设计保留了这个可能性以应对未来变化
     */
    public static Optional<MediaType> getMediaType(String mediaType) {
        return Optional.of(MediaType.parse(mediaType));
    }

    /**
    * 根据文件名推断并返回首个匹配的 {@link MediaType}，匹配失败时返回 {@link MediaType#ANY_TYPE} 作为兜底。
    *
    * <p>内部流程：调用 {@link #getMediaTypes(String)} 获取匹配结果列表，取列表的第一个元素；
    * 如果列表为空（即无匹配结果），则返回 {@link MediaType#ANY_TYPE}，
    * 表示"任意类型"，调用方可根据此结果判断该文件名无法被识别。
    *
    * <p><b>边界情况：</b>
    * <ul>
    *   <li>空文件名 —— 返回 {@link MediaType#ANY_TYPE}</li>
    *   <li>无扩展名的文件名（如 {@code "README"}） —— 返回 {@link MediaType#ANY_TYPE}</li>
    *   <li>未知扩展名（如 {@code "data.xyz"}） —— 返回 {@link MediaType#ANY_TYPE}</li>
    *   <li>{@code null} 文件名 —— 调用下游可能抛出 {@link NullPointerException}</li>
    * </ul>
    *
    * <h3>与 {@link #getMediaTypeNullable(String)} 的区别</h3>
    * <ul>
    *   <li><b>行为结果相同：</b>两者在匹配失败时都返回 {@link MediaType#ANY_TYPE}</li>
    *   <li><b>实现路径不同：</b>本方法通过 {@link #getMediaTypes(String)} → 列表 → {@code findFirst} 取首元素，
    * 而 {@code getMediaTypeNullable} 通过 {@link #getMediaType(String)} → 期权 → {@code orElse}</li>
    *   <li><b>语义侧重：</b>本方法强调"取第一个匹配结果或返回兜底类型"，
    *       {@code getMediaTypeNullable} 强调"允许为空的解析"</li>
    * </ul>
    *
    * @param filename 文件名，可以是完整文件名（如 {@code "photo.jpg"}）、带路径的文件名（如 {@code "/images/photo.jpg"}），
    *                 或仅扩展名（如 {@code ".png"}）；不允许为 {@code null}
    * @return 匹配到的 {@link MediaType}；匹配失败时返回 {@link MediaType#ANY_TYPE}，
    *         该常量表示无法识别的任意类型，调用方可据此进行兜底处理
     */
    public static MediaType getNoneMediaType(String filename) {
        return getMediaTypes(filename).stream().findFirst().orElse(MediaType.ANY_TYPE);
    }

    /**
    * 根据文件名解析 {@link MediaType}，匹配失败时返回 {@link MediaType#ANY_TYPE} 而非抛出异常或返回 {@code null}。
    *
    * <p>内部流程：调用 {@link #getMediaType(String)} 获取 {@link Optional} 结果，
    * 通过 {@code orElse(MediaType.ANY_TYPE)} 解包；如果 期权 为空（理论上不会发生），
    * 则返回 {@link MediaType#ANY_TYPE} 作为兜底值。
    *
    * <p><b>边界情况：</b>
    * <ul>
    *   <li>空文件名 —— 返回 {@link MediaType#ANY_TYPE}</li>
    *   <li>无扩展名文件名 —— 返回 {@link MediaType#ANY_TYPE}</li>
    *   <li>未知扩展名 —— 返回 {@link MediaType#ANY_TYPE}</li>
    *   <li>{@code null} 文件名 —— 可能抛出 {@link NullPointerException}</li>
    * </ul>
    *
    * <h3>与 {@link #getNoneMediaType(String)} 的区别</h3>
    * <ul>
    *   <li><b>返回值相同：</b>两者匹配失败都返回 {@link MediaType#ANY_TYPE}</li>
    *   <li><b>内部路径不同：</b>本方法走 {@link #getMediaType(String)}（MIME 字符串直接解析路径），
    *       {@code getNoneMediaType} 走 {@link #getMediaTypes(String)}（列表路径后取首元素）</li>
    *   <li><b>设计意图：</b>本方法是"可空版本"（Nullable），提供比直接解析更安全的调用方式，
    *       也是 {@link #parse(String)} 的实际委托对象</li>
    * </ul>
    *
    * @param filename 文件名，格式为完整文件名（如 {@code "document.pdf"}）或带路径的文件名
    *                 （如 {@code "/docs/report.xlsx"}）；不允许为 {@code null}
    * @return 匹配到的 {@link MediaType}；匹配失败时返回 {@link MediaType#ANY_TYPE}，永不返回 {@code null}
     */
    public static MediaType getMediaTypeNullable(String filename) {
        Optional<MediaType> mediaType = getMediaType(filename);
        return mediaType.orElse(MediaType.ANY_TYPE);
    }

/**
* 根据文件名解析并返回包含匹配 {@link MediaType} 的 {@link List}，始终返回单元素列表。
*
* <p>内部直接调用 {@link MediaType#parse(String)} 解析文件名，
* 将结果包装为 {@link Collections#singletonList(Object)} 返回。
* Guava 解析器不会失败，因此列表永远非空且恰好包含一个元素。
*
* <p><b>为什么返回 List 而非单个对象：</b>
* <ul>
*   <li><b>API 扩展预留：</b>List 返回类型为未来版本升级为真正的多类型匹配（如根据内容嗅探返回多个候选类型）
*       保留了空间，无需破坏现有调用方签名</li>
*   <li><b>流式操作兼容：</b>返回 List 允许调用方直接使用 {@code stream()}、{@code forEach()} 等
* 集合操作，与 Java 流 API 的编程风格保持一致</li>
*   <li><b>统一返回类型：</b>与 Guava 部分 API 的返回约定对齐，上层代码无需区分单结果与多结果的处理逻辑</li>
* </ul>
*
* @param filename 文件名，格式为完整文件名（如 {@code "image.png"}）或带路径的文件名
*                 （如 {@code "/assets/logo.svg"}）；不允许为 {@code null}
* @return 包含单个匹配 {@link MediaType} 的不可变 {@link List}，永不为空；
*         返回的是 {@link Collections#singletonList}，不可修改
     */
    public static List<MediaType> getMediaTypes(String filename) {
        MediaType parse = MediaType.parse(filename);
        return Collections.singletonList(parse);
    }

/**
* 根据文件名解析并返回 {@link MediaType}，是 {@link #getMediaTypeNullable(String)} 的快捷别名。
*
* <p>本方法直接委托给 {@link #getMediaTypeNullable(String)}，两者行为完全一致：
* 匹配成功返回对应 {@link MediaType}，匹配失败返回 {@link MediaType#ANY_TYPE}。
*
* <p><b>废弃说明：</b>本方法名 {@code parse} 容易与 Guava 的 {@link MediaType#parse(String)} 混淆，
* 且参数命名 {@code name} 不如 {@code filename} 语义明确。
* 推荐新代码直接使用 {@link #getMediaTypeNullable(String)}。
*
* @param name 文件名，格式为完整文件名（如 {@code "archive.zip"}）
*             或带路径的文件名（如 {@code "/downloads/archive.zip"}）；不允许为 {@code null}
* @return 匹配到的 {@link MediaType}，匹配失败时返回 {@link MediaType#ANY_TYPE}，永不返回 {@code null}
* @see #getMediaTypeNullable(String)
     */
    public static MediaType parse(String name) {
        return getMediaTypeNullable(name);
    }
}
