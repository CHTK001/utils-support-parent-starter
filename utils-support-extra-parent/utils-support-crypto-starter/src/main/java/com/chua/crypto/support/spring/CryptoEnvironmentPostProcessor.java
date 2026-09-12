package com.chua.crypto.support.spring;

import com.chua.common.support.utils.ClassUtils;
import com.chua.crypto.support.Crypto;
import com.chua.crypto.support.config.ConfigFileCipher;
import com.chua.crypto.support.store.KeyFileResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
* 加密配置文件启动期装载器（SpringBoot 环境透明解密）
*
* <p>在 SpringBoot 配置文件装载完成后执行：
* <ol>
*   <li><b>整文件加密</b> — 遍历 {@code chua.crypto.config-files}，凡首行为
*       {@code #!CHKF-CONFIG:1} 标记的配置文件，解密内容解析为属性并置于最高优先级</li>
*   <li><b>单值加密</b> — 将既有 PropertySource 包装为 {@link EncryptedPropertySource}，
*       读取时对 {@code ENC(...)} 值自动解密</li>
* </ol>
*
* <p>密文配置文件在磁盘上保持加密形态，仅在内存中解密使用；解析失败仅告警不阻断启动。
*
* @author CH
* @since 2026-08-26
 */
@Slf4j
public class CryptoEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
    * 解密属性源名称
     */
    public static final String DECRYPTED_SOURCE_NAME = "chuaCryptoDecryptedConfig";

    /**
    * 排序：晚于 配置数据 装载，保证配置文件已就绪
     */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    /**
    * snakeyaml 是否可用
     */
    private static final boolean YAML_PRESENT = ClassUtils.isPresent("org.yaml.snakeyaml.Yaml");

    /**
    * 处理环境：解密整文件加密的配置 + 包装 ENC(...) 单值解密
    *
    * @param environment  环境
    * @param application  应用
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            CryptoProperties properties = bindProperties(environment);
            if (!properties.isEnabled()) {
                return;
            }
            Crypto crypto = Crypto.from(properties.toSetting()).initialize();
            MutablePropertySources sources = environment.getPropertySources();

            int[] decryptedFiles = {0};
            Map<String, Object> decrypted = decryptWholeFileConfigs(crypto, properties.getConfigFiles(), decryptedFiles);
            if (!decrypted.isEmpty()) {
                sources.addFirst(new MapPropertySource(DECRYPTED_SOURCE_NAME, decrypted));
                log.info("已解密装载 {} 个加密配置文件", decryptedFiles[0]);
            }
            wrapValueDecryption(sources, crypto);
        } catch (Exception e) {
            log.warn("加密配置装载失败，按未加密继续启动: {}", e.getMessage());
        }
    }

    /**
    * 绑定 chua.加密货币.* 配置
    *
    * @param environment 环境
    * @return 属性对象
     */
    private CryptoProperties bindProperties(ConfigurableEnvironment environment) {
        return org.springframework.boot.context.properties.bind.Binder
                .get(environment)
                .bind("chua.crypto", org.springframework.boot.context.properties.bind.Bindable.of(CryptoProperties.class))
                .orElseGet(CryptoProperties::new);
    }

    /**
    * 解密全部整文件加密的配置文件并展平为属性键值
    *
    * @param crypto        加密门面
    * @param files         配置文件列表
    * @param decryptedFile 计数器（出 参数，记录实际解密文件数）
    * @return 属性键值
     */
    private Map<String, Object> decryptWholeFileConfigs(Crypto crypto, List<String> files, int[] decryptedFile) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (String name : files) {
            Path path = KeyFileResolver.resolve(name);
            if (!Files.exists(path) || !ConfigFileCipher.isEncrypted(path)) {
                continue;
            }
            String content = ConfigFileCipher.decryptFile(path, crypto);
            flat.putAll(parse(content, path.getFileName().toString()));
            decryptedFile[0]++;
        }
        return flat;
    }

    /**
    * 按扩展名解析配置内容为扁平属性表（a.b.c 形式）
    *
    * @param content   配置明文
    * @param fileName  文件名（决定解析器）
    * @return 扁平属性表
     */
    private Map<String, Object> parse(String content, String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".properties")) {
            return parseProperties(content);
        }
        if (YAML_PRESENT) {
            return parseYaml(content);
        }
        log.warn("类路径缺少 snakeyaml，无法解析 yaml 配置: {}", fileName);
        return Map.of();
    }

    /**
    * 解析 属性 内容
    *
    * @param content 配置明文
    * @return 扁平属性表
     */
    private Map<String, Object> parseProperties(String content) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
        } catch (Exception e) {
            throw new IllegalStateException("properties 解析失败", e);
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            flat.put(key, properties.getProperty(key));
        }
        return flat;
    }

    /**
    * 解析 yaml 内容为扁平属性表
    *
    * @param content 配置明文
    * @return 扁平属性表
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String content) {
        try {
            Object loaded = new org.yaml.snakeyaml.Yaml().load(content);
            Map<String, Object> flat = new LinkedHashMap<>();
            if (loaded instanceof Map<?, ?> map) {
                flatten("", (Map<String, Object>) map, flat);
            }
            return flat;
        } catch (Exception e) {
            throw new IllegalStateException("yaml 解析失败", e);
        }
    }

    /**
    * 递归展平嵌套 映射 为点号分隔键
    *
    * @param prefix 键前缀
    * @param source 嵌套结构
    * @param target 输出
     */
    private void flatten(String prefix, Map<String, Object> source, Map<String, Object> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested && !(nested).isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) nested;
                flatten(key, casted, target);
            } else {
                target.put(key, value);
            }
        }
    }

    /**
    * 用 ENC(...) 解密装饰器替换既有枚举型属性源（跳过自身与已装饰项）
    *
    * @param sources 属性源集合
    * @param crypto  加密门面
     */
    private void wrapValueDecryption(MutablePropertySources sources, Crypto crypto) {
        List<PropertySource<?>> snapshot = new ArrayList<>();
        for (PropertySource<?> source : sources) {
            snapshot.add(source);
        }
        for (PropertySource<?> source : snapshot) {
            if (!(source.getSource() instanceof EnumerablePropertySource<?>)) {
                continue;
            }
            String name = source.getName();
            if (DECRYPTED_SOURCE_NAME.equals(name) || sources.get(name) instanceof EncryptedPropertySource) {
                continue;
            }
            sources.replace(name, new EncryptedPropertySource(sources.get(name), crypto));
        }
    }

    /**
    * 获取排序值
    *
    * @return 排序值
     */
    @Override
    public int getOrder() {
        return ORDER;
    }
}
