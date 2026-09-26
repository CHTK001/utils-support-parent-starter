package com.chua.openai.support;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpRequestAuthenticator;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * openai-java SDK「服务商自定义鉴权」通道的桥接。
 *
 * <h3>为什么需要它</h3>
 * <p>SDK 的 {@code apiKey()} 恒产生 {@code Authorization: Bearer &lt;key&gt;}，无法表达
 * 「{@code api-key: &lt;key&gt;}」这类非标准方案（如 Dots Studio 只认 api-key 头）。
 * 而 {@code ClientOptions#build()} 与 {@code ClientOptions#securityHeaders()} 两道校验
 * 都只认 Bearer / Azure / WorkloadIdentity 三类凭据，自造一个 {@code Credential}
 * 会在请求期抛 {@code IllegalStateException: This request requires apiKey or workloadIdentity}。</p>
 *
 * <p>SDK 为此预留了 <b>provider authentication</b> 通道：
 * 设置 {@code httpRequestAuthenticator} 后，{@code effectiveCredential()} 会自动返回内部的
 * {@code HttpRequestAuthenticatorCredential} 哨兵，{@code securityHeaders()} 因此不再写入任何
 * 认证头，同时 {@code build()} 的凭据校验也得以通过。认证头再由
 * {@code ClientOptions#headers()}（即 {@code builder.putHeader(...)}）承载。</p>
 *
 * <h3>为什么要用反射</h3>
 * <p>{@code OpenAIOkHttpClient.Builder#httpRequestAuthenticator} 与
 * {@code ClientOptions.Builder#httpRequestAuthenticator} 均带
 * {@code ACC_SYNTHETIC} 标志（Kotlin {@code @JvmSynthetic}），Java 源码直接调用会报
 * 「找不到符号」。二者都是 {@code public} 方法，因此反射可见且可调用。</p>
 *
 * <p>反射解析失败时（例如 SDK 升级改名）本类返回 {@code false}，由调用方退回标准
 * Bearer 通道，不会让请求直接失败。</p>
 *
 * @author CHUA
 */
@Slf4j
public final class OpenAiProviderAuth {

    /**
     * 方法名（SDK 内部约定）
     */
    private static final String METHOD_NAME = "httpRequestAuthenticator";

    /**
     * 空转认证器：本桥接只借用该通道「解锁」SDK 校验，
     * 真正的认证头由调用方通过 {@code builder.putHeader(...)} 写入，
     * 因此这里保持请求原样返回。
     */
    private static final HttpRequestAuthenticator NO_OP = request -> request;

    /**
     * 反射解析出的 setter，null 表示不可用
     */
    private static volatile Method setter;

    /**
     * 是否已尝试解析
     */
    private static volatile boolean resolved;

    /**
     * 是否已提示过不可用（避免每次请求刷日志）
     */
    private static volatile boolean warned;

    private OpenAiProviderAuth() {
    }

    /**
     * 让客户端走 SDK 的 provider-authentication 通道。
     *
     * <p>调用后该 builder 不会再自动写入任何认证头，调用方需要自行把目标认证头
     * 通过 {@code builder.putHeader(name, value)} 补上（{@code name} 为空表示不发认证头）。</p>
     *
     * @param builder 待构建的客户端 builder
     * @return true 表示通道已启用；false 表示反射不可用，调用方应退回标准 Bearer 通道
     */
    public static boolean apply(OpenAIOkHttpClient.Builder builder) {
        Method method = resolveSetter();
        if (method == null) {
            if (!warned) {
                warned = true;
                log.warn("当前 openai-java SDK 不支持 provider-authentication 通道（{} 反射解析失败），"
                        + "非标准认证方案（如 api-key 头）将退回 Authorization: Bearer", METHOD_NAME);
            }
            return false;
        }
        try {
            method.invoke(builder, NO_OP);
            return true;
        } catch (Exception e) {
            if (!warned) {
                warned = true;
                log.warn("启用 provider-authentication 通道失败，非标准认证方案将退回 Authorization: Bearer: {}",
                        e.getMessage());
            }
            return false;
        }
    }

    /**
     * 当前 SDK 是否支持 provider-authentication 通道
     *
     * @return true 表示可用
     */
    public static boolean isSupported() {
        return resolveSetter() != null;
    }

    /**
     * 解析并缓存 setter
     *
     * @return setter，null 表示不可用
     */
    private static Method resolveSetter() {
        if (resolved) {
            return setter;
        }
        synchronized (OpenAiProviderAuth.class) {
            if (resolved) {
                return setter;
            }
            Method method = null;
            try {
                // 优先按精确签名查找
                method = OpenAIOkHttpClient.Builder.class
                        .getDeclaredMethod(METHOD_NAME, HttpRequestAuthenticator.class);
            } catch (NoSuchMethodException ignored) {
                // 退化为按名字扫描（SDK 若调整了参数包装类型，仍有机会命中）
                for (Method candidate : OpenAIOkHttpClient.Builder.class.getDeclaredMethods()) {
                    if (METHOD_NAME.equals(candidate.getName()) && candidate.getParameterCount() == 1) {
                        method = candidate;
                        break;
                    }
                }
            }
            if (method != null) {
                try {
                    method.setAccessible(true);
                } catch (Exception e) {
                    log.debug("provider-authentication setter 无需或无法 setAccessible: {}", e.getMessage());
                }
            }
            setter = method;
            resolved = true;
            return method;
        }
    }
}
