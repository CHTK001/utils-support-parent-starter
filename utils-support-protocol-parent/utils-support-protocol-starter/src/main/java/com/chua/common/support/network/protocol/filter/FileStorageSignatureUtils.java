package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.base.collection.Options;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 文件存储签名工具类
 * <p>
 * 用于生成和验证文件存储链接的签名
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
public class FileStorageSignatureUtils {

    // 密钥，生成签名
    private static final String SECRET_KEY = "fileStorageMappingSecret2024";

    // 默认过期时间（毫秒），默认5分钟
    private static final long DEFAULT_EXPIRE_TIME = 5 * 60 * 1000L;

    /**
     * 生成签名
     */
    public static String generateSignature(Map<String, String> params) {
        try {
            // 构建待签名字符串
            StringBuilder signBuilder = new StringBuilder();
            signBuilder.append(SECRET_KEY);

            // 按键排序添加参数
            params.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> signBuilder.append(entry.getKey()).append(entry.getValue()));

            signBuilder.append(SECRET_KEY);

            // 使用SHA-256生成签名
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signBuilder.toString().getBytes(StandardCharsets.UTF_8));

            // 使用Base64编码
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            log.error("生成签名失败", e);
            return "";
        }
    }

    /**
     * 验证签名
     */
    public static boolean validateSignature(Options options) {
        try {
            // 获取签名参数
            String signature = options.getString("signature");
            if (StringUtils.isEmpty(signature)) {
                return false;
            }

            // 重新构建待签名字符串进行验证
            Map<String, String> params = new HashMap<>();

            // 复制所有参数除了signature
            for (String key : options.getOptionNames()) {
                if (!"signature".equals(key)) {
                    params.put(key, options.getString(key));
                }
            }

            String expectedSignature = generateSignature(params);
            return signature.equals(expectedSignature);
        } catch (Exception e) {
            log.error("验证签名失败", e);
            return false;
        }
    }

    /**
     * 获取文件存储桶
     */
    public static String getBucket(Options options) {
        return options.getString("bucket", "/");
    }

    /**
     * 获取文件路径
     */
    public static String getFilePath(Options options) {
        return options.getString("path", "");
    }

    /**
     * 检查是否是闪图
     */
    public static boolean isFlashImage(Options options) {
        String flash = options.getString("flash");
        return "true".equals(flash);
    }

    /**
     * 获取过期时间（支持任意格式）
     */
    public static long getExpireTime(Options options) {
        String expireTimeStr = options.getString("expireTime");
        if (StringUtils.isNotEmpty(expireTimeStr)) {
            try {
                // 尝试直接解析为数字（毫秒）
                return Long.parseLong(expireTimeStr);
            } catch (NumberFormatException e) {
                // 如果不是数字，尝试解析为时间格式（如 5m, 1h, 30s 等）
                return parseExpireTime(expireTimeStr);
            }
        }
        // 返回默认过期时间
        return DEFAULT_EXPIRE_TIME;
    }

    /**
     * 解析时间格式字符串（支持 5m, 1h, 30s 等格式）
     */
    private static long parseExpireTime(String expireTimeStr) {
        try {
            expireTimeStr = expireTimeStr.toLowerCase().trim();

            // 检查是否以数字结尾
            if (expireTimeStr.matches(".*\\d$")) {
                // 纯数字，假设为秒
                return Long.parseLong(expireTimeStr) * 1000;
            }

            // 提取数字部分和单位部分
            String numberPart = expireTimeStr.replaceAll("[^\\d]", "");
            String unitPart = expireTimeStr.replaceAll("\\d", "");

            if (numberPart.isEmpty()) {
                return DEFAULT_EXPIRE_TIME; // 无法解析，返回默认值
            }

            long number = Long.parseLong(numberPart);

            // 根据单位转换为毫秒
            switch (unitPart) {
                case "ms": // 毫秒
                    return number;
                case "s": // 秒
                    return number * 1000;
                case "m": // 分钟
                    return number * 60 * 1000;
                case "h": // 小时
                    return number * 60 * 60 * 1000;
                case "d": // 天
                    return number * 24 * 60 * 60 * 1000;
                default: // 默认为秒
                    return number * 1000;
            }
        } catch (Exception e) {
            log.warn("解析过期时间失败，使用默认值: {}", expireTimeStr, e);
            return DEFAULT_EXPIRE_TIME; // 解析失败，返回默认值
        }
    }

    /**
     * 检查是否超时
     */
    public static boolean isExpired(Options options) {
        String timestampStr = options.getString("timestamp");
        if (StringUtils.isEmpty(timestampStr)) {
            return true; // 参数不完整，认为已超时
        }

        try {
            long timestamp = Long.parseLong(timestampStr);
            long currentTime = System.currentTimeMillis();
            return currentTime - timestamp > getExpireTime(options);
        } catch (NumberFormatException e) {
            return true; // 时间戳格式错误，认为已超时
        }
    }
}