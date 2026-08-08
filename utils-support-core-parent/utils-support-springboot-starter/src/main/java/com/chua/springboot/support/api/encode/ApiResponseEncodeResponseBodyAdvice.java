package com.chua.springboot.support.api.encode;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.code.ReturnResult;
import com.chua.springboot.support.api.annotations.ApiReturnFormatIgnore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 响应加密
 * @author CH
 */
@RestControllerAdvice
@SuppressWarnings({"unchecked", "rawtypes"})
public class ApiResponseEncodeResponseBodyAdvice implements ResponseBodyAdvice<Object>  {
    private static final Logger log = LoggerFactory.getLogger(ApiResponseEncodeResponseBodyAdvice.class);
        private final ApiResponseEncodeRegister apiResponseEncodeRegister;


    public ApiResponseEncodeResponseBodyAdvice(ApiResponseEncodeRegister apiResponseEncodeRegister) {
        this.apiResponseEncodeRegister = apiResponseEncodeRegister;
    }


    @Override
    public boolean supports(MethodParameter methodParameter, Class<? extends HttpMessageConverter<?>> aClass) {
        return true;
    }

    @SneakyThrows
    @Override
    public Object beforeBodyWrite(Object o, MethodParameter methodParameter, MediaType mediaType, Class<? extends HttpMessageConverter<?>> aClass, ServerHttpRequest serverHttpRequest, ServerHttpResponse serverHttpResponse) {
        if (o instanceof StreamingResponseBody || o instanceof ResponseBodyEmitter) {
            return o;
        }

        if (mediaType != null) {
            String subtype = mediaType.getSubtype();
            if (subtype != null && (subtype.contains("event-stream") || subtype.contains("octet-stream"))) {
                return o;
            }
        }

        if (!(serverHttpRequest instanceof ServletServerHttpRequest servletServerHttpRequest)) {
            return o;
        }

        HttpServletRequest servletRequest = servletServerHttpRequest.getServletRequest();
        // @ApiReturnFormatIgnore 标注的接口跳过加密（方法级或类级）
        if (isIgnoreEncrypt(methodParameter, aClass)) {
            return o;
        }

        if (apiResponseEncodeRegister.isPass()) {
            return o;
        }

        if (o instanceof ReturnResult returnResult && !returnResult.isOk()) {
            return o;
        }

        if (apiResponseEncodeRegister.isPass(servletRequest.getRequestURI())) {
            return o;
        }


        HttpHeaders headers = serverHttpResponse.getHeaders();

        // 随机 key AES 加密，密文前后插入噪声，冗余等级用 x-ot 标识
        ApiResponseEncodeRegister.CodecResult codecResult = apiResponseEncodeRegister.encode(Json.toJson(o));

        // 设置响应头：加密标记、随机 key、冗余等级
        headers.set("x-ec", "1");
        headers.set("x-ck", codecResult.getKey());
        headers.set("x-ot", String.valueOf(codecResult.getNoiseLevel()));

        // 设置Content-Type为application/octet-stream
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        // 添加X-Content-Type-Options: nosniff响应头
        headers.set("X-Content-Type-Options", "nosniff");

        // 返回二进制密文
        byte[] responseBytes = codecResult.getData();
        headers.setContentLength(responseBytes.length);

        log.debug("[springboot-encode] 响应加密完成，数据长度: {}, 冗余等级: {}",
                responseBytes.length, codecResult.getNoiseLevel());
        return ResponseEntity.<byte[]>ok()
                .headers(headers)
                .body(responseBytes);
    }

    /**
     * 判断接口是否标注忽略加密
     *
     * @param methodParameter 方法参数
     * @param converterType   HTTP 消息转换器类型
     * @return true 表示忽略加密
     */
    private boolean isIgnoreEncrypt(MethodParameter methodParameter, Class<? extends HttpMessageConverter<?>> converterType) {
        if (methodParameter.hasMethodAnnotation(ApiReturnFormatIgnore.class)) {
            return true;
        }
        if (AnnotationUtils.findAnnotation(methodParameter.getContainingClass(), ApiReturnFormatIgnore.class) != null) {
            return true;
        }
        return false;
    }
}
