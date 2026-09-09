package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.annotation.SpiSupport;
import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.network.protocol.storage.FileStorageFactory;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.ServletFilterConfig;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 文件存储Servlet映射过滤器
 * <p>
 * 主要用于生成图片链接，支持闪图模式和自签名验证
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("fileStorageMapping")
@SpiDescribe("文件存储映射")
@SpiSupport("http")
public class FileStorageServletMappingFilter extends AbstractServletFilter {

    private final FileStorageFactory fileStorageFactory;

    public FileStorageServletMappingFilter(FileStorageFactory.FileStorageSetting config) {
        this.fileStorageFactory = FileStorageFactory.create(config);
    }

    @Override
    public void init(ServletFilterConfig config) throws Exception {
        // 初始化逻辑
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        try {
            String url = request.getUrl();
            if (StringUtils.isEmpty(url)) {
                sendXmlErrorResponse(response, 400, "请求URL为空", url);
                return;
            }

            // 检查是否是生成链接的请求
            if (url.startsWith("/generate-link")) {
                generateLink(request, response);
                return;
            }

            // 其他请求继续处理
            chain.doFilter(request, response);

        } catch (Exception e) {
            log.error("FileStorageServletMappingFilter处理失败", e);
            sendXmlErrorResponse(response, 500, "处理失败", request.getUrl());
        }
    }

    /**
     * 生成带签名的链接
     */
    private void generateLink(ServletRequest request, ServletResponse response) throws Exception {
        // 获取参数
        String type = request.getParameter("type"); // 支持闪图
        String bucket = request.getParameter("bucket"); // 存储桶
        String filePath = request.getParameter("filePath"); // 文件路径
        String expireTimeStr = request.getParameter("expireTime"); // 过期时间（任意格式）

        if (StringUtils.isEmpty(bucket) || StringUtils.isEmpty(filePath)) {
            sendXmlErrorResponse(response, 400, "存储桶和文件路径不能为空", request.getUrl());
            return;
        }

        // 检查是否是闪图并且文件不是图片格式
        if ("flash".equals(type)) {
            // 检查文件扩展名是否为图片格式
            String lowerPath = filePath.toLowerCase();
            if (!lowerPath.endsWith(".jpg") && !lowerPath.endsWith(".jpeg") &&
                    !lowerPath.endsWith(".png") && !lowerPath.endsWith(".gif") &&
                    !lowerPath.endsWith(".bmp") && !lowerPath.endsWith(".webp")) {
                sendXmlErrorResponse(response, 400, "闪图功能只支持图片文件", request.getUrl());
                return;
            }
        }

        // 构建查询参数
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        params.put("nonce", String.valueOf(System.nanoTime())); // 添加随机数防止重复
        params.put("bucket", bucket); // 存储桶
        params.put("path", filePath); // 文件路径

        // 处理闪图类型
        if ("flash".equals(type)) {
            params.put("flash", "true");
            // 支持任意格式的过期时间
            if (StringUtils.isNotEmpty(expireTimeStr)) {
                params.put("expireTime", expireTimeStr);
            } else {
                // 使用默认过期时间（5分钟）
                params.put("expireTime", "300000");
            }
        } else {
            // 非闪图使用默认过期时间
            params.put("expireTime", "300000"); // 5分钟
        }

        // 生成签名
        String signature = FileStorageSignatureUtils.generateSignature(params);
        params.put("signature", signature);

        // 构建完整URL（使用与普通文件访问相同的端点，但包含签名参数）
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append("/file"); // 使用与普通文件访问相同的端点

        // 添加查询参数
        boolean firstParam = true;
        urlBuilder.append("?");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!firstParam) {
                urlBuilder.append("&");
            }
            urlBuilder.append(entry.getKey())
                    .append("=")
                    .append(java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
            firstParam = false;
        }

        // 返回生成的链接
        response.setStatusCode(200);
        response.setContentType("text/plain;charset=UTF-8");
        response.setBody(urlBuilder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 发送XML格式的错误响应
     */
    private void sendXmlErrorResponse(ServletResponse response, int statusCode, String message, String url) {
        try {
            // 构建错误信息
            StringBuilder xmlBuilder = new StringBuilder();
            xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            xmlBuilder.append("<error>");
            xmlBuilder.append("<code>").append(statusCode).append("</code>");
            xmlBuilder.append("<message><![CDATA[").append(message).append("]]></message>");
            xmlBuilder.append("<url><![CDATA[").append(url).append("]]></url>");
            xmlBuilder.append("<timestamp>").append(System.currentTimeMillis()).append("</timestamp>");

            // 添加签名
            Map<String, String> params = new HashMap<>();
            params.put("code", String.valueOf(statusCode));
            params.put("message", message);
            params.put("url", url);
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            String signature = FileStorageSignatureUtils.generateSignature(params);
            xmlBuilder.append("<signature>").append(signature).append("</signature>");

            xmlBuilder.append("</error>");

            response.setStatus(statusCode);
            response.setContentType("application/xml;charset=UTF-8");
            response.setBody(xmlBuilder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("发送XML错误响应失败", e);
            // 如果XML构建失败，发送简单的错误信息
            response.setStatus(statusCode);
            response.setContentType("text/plain;charset=UTF-8");
            response.setBody(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Override
    public String getFilterName() {
        return "FileStorageServletMappingFilter";
    }

    @Override
    public int getOrder() {
        return 40; // 比FileStorageServletFilter优先级稍高
    }

    @Override
    public String getDescription() {
        return "文件存储Servlet映射过滤器 - 用于生成带签名的图片链接";
    }

    @Override
    public boolean matches(ServletRequest request) {
        return true;
    }

    @Override
    public void destroy() {
        try {
            log.info("FileStorageServletMappingFilter已销毁");
        } catch (Exception e) {
            log.error("FileStorageServletMappingFilter销毁失败", e);
        }
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.HTTPS};
    }
}