package com.chua.openai.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestAuthenticator;
import com.openai.core.http.HttpRequestBody;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * EAC (GitHub OAuth + Star + HMAC 网关) ChatClient。
 *
 * <p>继承 {@link OpenAiChatClient}，在构建 OkHttp 客户端时注入
 * {@link EacHmacAuthenticator}，对每个出站请求计算 HMAC-SHA256 签名头：
 * <ul>
 *   <li>{@code x-ofm-timestamp} — 当前毫秒时间戳</li>
 *   <li>{@code x-ofm-signature} — HMAC-SHA256(secret, timestamp\nMETHOD\npath\nbodyHash)</li>
 *   <li>{@code x-ofm-user} — 用户令牌（由 extraHeaders 承载）</li>
 * </ul>
 *
 * <p>签名密钥从 {@code setting.getAppKey()} 读取，该值由
 * {@code EacAccountChannelAuthManager.toRouteTarget()} 注入。</p>
 *
 * <p>{@code httpRequestAuthenticator} 带 ACC_SYNTHETIC 标志（Kotlin @JvmSynthetic），
 * 需通过反射调用——与 {@link OpenAiProviderAuth} 一致。</p>
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@Spi({"eac"})
public class EacChatClient extends OpenAiChatClient {

    /**
     * 反射解析出的 httpRequestAuthenticator setter，null 表示不可用
     */
    private static volatile Method authenticatorSetter;
    private static volatile boolean setterResolved;

    public EacChatClient(ChatClientSetting setting) {
        super(setting);
    }

    @Override
    protected void configureClientBuilder(OpenAIOkHttpClient.Builder builder) {
        String signingSecret = getSetting() != null ? getSetting().getAppKey() : null;
        if (signingSecret == null || signingSecret.isBlank()) {
            log.warn("EAC signing secret is empty, requests will not be HMAC-signed");
            return;
        }
        Method setter = resolveSetter();
        if (setter == null) {
            log.warn("EAC: httpRequestAuthenticator setter not available via reflection, "
                    + "HMAC signing will not be applied");
            return;
        }
        try {
            setter.invoke(builder, new EacHmacAuthenticator(signingSecret));
        } catch (Exception e) {
            log.warn("EAC: failed to inject HMAC authenticator: {}", e.getMessage());
        }
    }

    /**
     * resolve Setter
     * @return 返回值
    */
    private static Method resolveSetter() {
        if (setterResolved) {
            return authenticatorSetter;
        }
        synchronized (EacChatClient.class) {
            if (setterResolved) {
                return authenticatorSetter;
            }
            Method method = null;
            try {
                method = OpenAIOkHttpClient.Builder.class
                        .getDeclaredMethod("httpRequestAuthenticator", HttpRequestAuthenticator.class);
            } catch (NoSuchMethodException ignored) {
                for (Method candidate : OpenAIOkHttpClient.Builder.class.getDeclaredMethods()) {
                    if ("httpRequestAuthenticator".equals(candidate.getName())
                            && candidate.getParameterCount() == 1) {
                        method = candidate;
                        break;
                    }
                }
            }
            if (method != null) {
                try {
                    method.setAccessible(true);
                } catch (Exception e) {
                    log.debug("EAC: httpRequestAuthenticator setter setAccessible skipped: {}",
                            e.getMessage());
                }
            }
            authenticatorSetter = method;
            setterResolved = true;
            return method;
        }
    }

    /**
     * EAC HMAC-SHA256 签名认证器。
     *
     * <p>对每个出站请求计算：</p>
     * <pre>
     *   timestamp  = String(Math.trunc(Date.now()))  // 毫秒
     *   bodyHash   = SHA256(body, 'utf8').hex
     *   mac        = HMAC-SHA256(secret, timestamp + "\n" + METHOD + "\n" + path + "\n" + bodyHash).hex
     * </pre>
     * <p>签名头：{@code x-ofm-timestamp}, {@code x-ofm-signature}。</p>
     */
    static final class EacHmacAuthenticator implements HttpRequestAuthenticator {

        private final String signingSecret;

        /**
         * Eac Hmac Authenticator
         * @param signingSecret 字符串
         * @return 返回值
        */
        EacHmacAuthenticator(String signingSecret) {
            this.signingSecret = signingSecret;
        }

        @Override
        public HttpRequest authenticate(HttpRequest request) {
            try {
                String timestamp = String.valueOf(System.currentTimeMillis());
                String method = request.method().name();
                String path = buildPath(request);
                String bodyHash = hashBody(request.body());

                String mac = hmacSha256(signingSecret,
                        timestamp + "\n" + method + "\n" + path + "\n" + bodyHash);

                HttpRequest.Builder builder = request.toBuilder();
                builder.putHeader("x-ofm-timestamp", timestamp);
                builder.putHeader("x-ofm-signature", mac);
                return builder.build();
            } catch (Exception e) {
                log.warn("EAC HMAC signing failed: {}", e.getMessage());
                return request;
            }
        }

        /**
         * build Path
         * @param request 参数
         * @return 返回值
        */
        private static String buildPath(HttpRequest request) {
            String baseUrl = request.baseUrl();
            StringBuilder path = new StringBuilder();
            if (baseUrl != null) {
                int schemeEnd = baseUrl.indexOf("://");
                if (schemeEnd > 0) {
                    int pathStart = baseUrl.indexOf('/', schemeEnd + 3);
                    if (pathStart >= 0) {
                        path.append(baseUrl, pathStart, baseUrl.length());
                    }
                }
            }
            // 始终把端点路径段拼到 baseUrl 路径之后——网关对完整 url.pathname
            // （含挂载前缀与端点）验签，只取 baseUrl 路径会签名成 /eac/v1
            // 而非 /eac/v1/models，导致 401。
            var segments = request.pathSegments();
            if (segments != null && !segments.isEmpty()) {
                if (path.length() > 0 && !path.toString().endsWith("/")) {
                    path.append("/");
                }
                path.append(String.join("/", segments));
            }
            if (path.length() == 0) {
                path.append('/');
            }
            return path.toString();
        }

        /**
         * hash Body
         * @param body 参数
         * @return 返回值
         * @throws Exception 异常
        */
        private static String hashBody(HttpRequestBody body) throws Exception {
            if (body == null) {
                return sha256Hex("");
            }
            if (!body.repeatable()) {
                return sha256Hex("");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            body.writeTo(baos);
            return sha256Hex(baos.toString(StandardCharsets.UTF_8));
        }

        /**
         * sha256Hex
         * @param input 字符串
         * @return 返回值
         * @throws Exception 异常
        */
        private static String sha256Hex(String input) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        }

        /**
         * hmac Sha256
         * @param message 字符串
         * @param secret 字符串
         * @return 返回值
         * @throws Exception 异常
        */
        private static String hmacSha256(String secret, String message) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        }
    }
}
