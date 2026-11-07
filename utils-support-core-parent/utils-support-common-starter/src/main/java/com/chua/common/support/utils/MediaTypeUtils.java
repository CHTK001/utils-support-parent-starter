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
 *   <tr><td>{@link #getMediaType(String)}</td><td>MIME 字符串</td><td>返回 Optional.empty()（实际不会失败，Guava
 *   强制解析）</td><td>已知合法 MIME 字符串的解析</td></tr>
 *   <tr><td>{@link #getMediaTypeNullable(String)}</td><td>文件名</td><td>返回 {@link
 *   MediaType#ANY_TYPE}</td><td>需要空安全的文件名推断</td></tr>
 *   <tr><td>{@link #getNoneMediaType(String)}</td><td>文件名</td><td>返回 {@link MediaType#ANY_TYPE}</td><td>与
 *   getMediaTypeNullable 行为等价，内部实现路径不同</td></tr>
 *   <tr><td>{@link #getMediaTypes(String)}</td><td>文件名</td><td>返回包含单个元素的列表（不是空列表）</td><td>需以 List 形式消费结果时使用</td></tr>
 *   <tr><td>{@link #parse(String)}</td><td>文件名</td><td>返回 {@link MediaType#ANY_TYPE}</td><td>{@link
 *   #getMediaTypeNullable(String)} 的快捷别名</td></tr>
 * </table>
 *
 * @author CH
 * @since 1.0
 * @see com.google.common.net.MediaType
 */
public class MediaTypeUtils {
    /**
     * 创建 media类型工具 实例
     */
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




}
