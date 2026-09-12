package com.chua.deeplearning.support.environment;

/**
* 深度学习环境变量解析器。
* <p>按 Provider 名称从系统属性 / 环境变量解析 appId、appSecret、activeKey、modelPath 等配置项。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DeeplearningEnvironment {

    /**
    * 提供者 名称
     */
    private final String provider;

    /**
    * 应用标识
     */
    private String appId;

    /**
    * 应用密钥
     */
    private String appSecret;

    /**
    * 激活密钥
     */
    private String activeKey;

    /**
    * 模型路径
     */
    private String modelPath;

    /**
    * 属性键前缀：环境变量前缀
     */
    private static final String PREFIX_DEEPLEARNING = "deeplearning.";

    /**
    * 配置键：app-标识
     */
    private static final String KEY_APP_ID = "app-id";

    /**
    * 配置键：app-secret
     */
    private static final String KEY_APP_SECRET = "app-secret";

    /**
    * 配置键：活跃-键
     */
    private static final String KEY_ACTIVE_KEY = "active-key";

    /**
    * 配置键：模型-路径
     */
    private static final String KEY_MODEL_PATH = "model-path";

    /**
    * 空字符串常量
     */
    private static final String EMPTY = "";

    /**
    * 构造环境解析器。
    *
    * @param provider 提供者 名称
     */
    public DeeplearningEnvironment(String provider) {
        this.provider = provider;
    }

    /**
    * 获取应用标识。
    *
    * @return 应用标识
     */
    public String getAppId() {
        if (appId == null) {
            appId = resolve(KEY_APP_ID);
        }
        return appId;
    }

    /**
    * 获取应用密钥。
    *
    * @return 应用密钥
     */
    public String getAppSecret() {
        if (appSecret == null) {
            appSecret = resolve(KEY_APP_SECRET);
        }
        return appSecret;
    }

    /**
    * 获取激活密钥。
    *
    * @return 激活密钥
     */
    public String getActiveKey() {
        if (activeKey == null) {
            activeKey = resolve(KEY_ACTIVE_KEY);
        }
        return activeKey;
    }

    /**
    * 获取模型路径。
    *
    * @return 模型路径
     */
    public String getModelPath() {
        if (modelPath == null) {
            modelPath = resolve(KEY_MODEL_PATH);
        }
        return modelPath;
    }

    /**
    * 从系统属性和环境变量中解析配置值。
    *
    * @param key 配置键
    * @return 配置值，未找到时返回空字符串
     */
    private String resolve(String key) {
        String propertyKey = PREFIX_DEEPLEARNING + provider + "." + key;
        String val = System.getProperty(propertyKey);
        if (val != null && !val.isEmpty()) {
            return val;
        }

        String envKey = propertyKey.replace('.', '_').toUpperCase();
        val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) {
            return val;
        }

        return EMPTY;
    }

    /**
    * 创建环境解析器实例。
    *
    * @param provider 提供者 名称
    * @return DeeplearningEnvironment 实例
     */
    public static DeeplearningEnvironment of(String provider) {
        return new DeeplearningEnvironment(provider);
    }
}