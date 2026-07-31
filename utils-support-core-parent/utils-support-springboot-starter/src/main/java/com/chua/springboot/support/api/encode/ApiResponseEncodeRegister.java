package com.chua.springboot.support.api.encode;
import com.chua.common.support.function.Upgrade;
import com.chua.common.support.matcher.PathMatcher;
import com.chua.springboot.support.api.properties.ApiProperties;
import com.chua.starter.common.support.application.GlobalSettingFactory;
import lombok.Getter;
import org.springframework.context.ApplicationListener;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 响应编码注册器
 * <p>
 * 提供响应数据加密功能。
 * </p>
 *
 * @author CH
 * @version 2.0.0
 * @since 2024/01/22
 */
public class ApiResponseEncodeRegister implements Upgrade<ApiResponseEncodeConfiguration>, ApplicationListener<ApiResponseEncodeConfiguration>  {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiResponseEncodeRegister.class);

    /**
     * 安全随机数
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * AES 编解码器（与前端 wasm 协议一致）
     */
    private static final ApiEncodeAesCodec AES_CODEC = new ApiEncodeAesCodec();

    private final List<String> whiteList;
    private final String codecType;
    private ApiResponseEncodeConfiguration apiResponseEncodeConfiguration;

    /**
     * 编解码器类型
     */
    private ApiResponseEncodeRegister(String codecType, List<String> whiteList) {
        this.codecType = codecType;
        this.whiteList = whiteList;
    }

    /**
     * 构造函数
     *
     * @param responseEncodePropertiesConfig 编解码配置
     */
    public ApiResponseEncodeRegister(ApiProperties.ResponseEncodeProperties responseEncodePropertiesConfig) {
        this.codecType = responseEncodePropertiesConfig.getCodecType();
        this.whiteList = responseEncodePropertiesConfig.getWhiteList() == null
                ? Collections.emptyList()
                : responseEncodePropertiesConfig.getWhiteList();
        ApiResponseEncodeConfiguration configuration = getOrCreateConfiguration();
        if (!responseEncodePropertiesConfig.isExtInject()) {
            configuration.setCodecResponseOpen(responseEncodePropertiesConfig.isResponseEnable());
        }
        this.apiResponseEncodeConfiguration = configuration;
    }

    public boolean isPass() {
        check();
        return !apiResponseEncodeConfiguration.isCodecResponseOpen();
    }

    private void check() {
        if (null != apiResponseEncodeConfiguration) {
            return;
        }
        this.apiResponseEncodeConfiguration = getOrCreateConfiguration();
    }

    /**
     * 编码数据（AES-128-CBC，随机 key，密文前后插入噪声，x-ot 标识冗余等级）
     *
     * @param data 数据
     * @return 编码结果
     */
    public CodecResult encode(String data) {
        try {
            // 随机生成 16 字节 AES key
            byte[] key = new byte[16];
            SECURE_RANDOM.nextBytes(key);
            byte[] encrypted = aesCbc(data.getBytes(java.nio.charset.StandardCharsets.UTF_8), key);
            // 随机选择冗余等级（1~3），等级越高噪声越多
            int noiseLevel = SECURE_RANDOM.nextInt(3) + 1;
            byte[] noisy = addNoise(encrypted, noiseLevel);
            return new CodecResult(Base64.getEncoder().encodeToString(key), noisy, noiseLevel);
        } catch (Exception e) {
            log.error("[CodecFactory] 数据加密失败", e);
            return new CodecResult("", data.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0);
        }
    }

    /**
     * AES-128-CBC 加密（PKCS5Padding，iv 为 16 字节全零，与前端 wasm 协议一致）
     *
     * @param data 明文
     * @param key  16 字节密钥
     * @return 密文
     */
    private byte[] aesCbc(byte[] data, byte[] key) throws Exception {
        return AES_CODEC.encrypt(data, key);
    }

    /**
     * 密文前后插入噪声字节
     *
     * @param encrypted   密文
     * @param noiseLevel  冗余等级（1~3）
     * @return 含噪声的字节数组
     */
    private byte[] addNoise(byte[] encrypted, int noiseLevel) {
        int prefixLen = noiseLevel;
        int suffixLen = noiseLevel;
        byte[] noisePrefix = new byte[prefixLen];
        byte[] noiseSuffix = new byte[suffixLen];
        SECURE_RANDOM.nextBytes(noisePrefix);
        SECURE_RANDOM.nextBytes(noiseSuffix);
        byte[] result = new byte[prefixLen + encrypted.length + suffixLen];
        System.arraycopy(noisePrefix, 0, result, 0, prefixLen);
        System.arraycopy(encrypted, 0, result, prefixLen, encrypted.length);
        System.arraycopy(noiseSuffix, 0, result, prefixLen + encrypted.length, suffixLen);
        return result;
    }

    /**
     * 设置是否启用
     *
     * @param parseBoolean 是否启用
     */
    public void setEnable(boolean parseBoolean) {
        check();
        apiResponseEncodeConfiguration.setCodecResponseOpen(parseBoolean);
    }

    /**
     * 是否通过（白名单检查）
     *
     * @param requestURI 请求URI
     * @return 是否通过
     */
    public boolean isPass(String requestURI) {
        if (isPass()) {
            return true;
        }
        for (String s : whiteList) {
            if (PathMatcher.INSTANCE.match(s, requestURI)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void upgrade(ApiResponseEncodeConfiguration apiResponseEncodeConfiguration) {
        this.apiResponseEncodeConfiguration = apiResponseEncodeConfiguration;
    }

    @Override
    public void onApplicationEvent(ApiResponseEncodeConfiguration event) {
        upgrade(event);
    }

    private ApiResponseEncodeConfiguration getOrCreateConfiguration() {
        ApiResponseEncodeConfiguration configuration = GlobalSettingFactory.getInstance().get("config", ApiResponseEncodeConfiguration.class);
        if (configuration == null) {
            configuration = new ApiResponseEncodeConfiguration();
            GlobalSettingFactory.getInstance().register("config", configuration);
        }
        return configuration;
    }

    /**
     * 编码结果
     */
    public static class CodecResult {
        /**
         * 传输密钥（base64）
         */
        private final String key;
        /**
         * 加密后的数据（含前后噪声）
         */
        private final byte[] data;
        /**
         * 冗余等级（1~3，0 表示无噪声）
         */
        private final int noiseLevel;

        /**
         * 构造函数
         *
         * @param key        传输密钥（base64）
         * @param data       加密后的数据（含前后噪声）
         * @param noiseLevel 冗余等级
         */
        public CodecResult(String key, byte[] data, int noiseLevel) {
            this.key = key;
            this.data = data;
            this.noiseLevel = noiseLevel;
        }

        public String getKey() {
            return key;
        }

        public byte[] getData() {
            return data;
        }

        public int getNoiseLevel() {
            return noiseLevel;
        }
    }
}
