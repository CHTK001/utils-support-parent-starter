package com.chua.common.support.utils;

import java.util.*;
import java.util.stream.Collectors;

/**
* 签名工具类，提供基于 映射 参数的签名计算与验证功能。
*
* <p>核心功能：对传入的 Map 参数按 key 自然排序，去除 value 为空的数据，
* 拼接为 {@code key1=value1&key2=value2} 格式的字符串后计算 MD5/SHA256 签名。</p>
*
* <h3>支持的签名模式</h3>
* <ul>
*   <li><b>key= 模式</b>：密钥以 {@code &key=secretKey} 形式追加（微信支付 V2 等场景）</li>
*   <li><b>直接追加模式</b>：密钥直接追加在末尾（部分第三方接口）</li>
*   <li><b>微信支付签名</b>：MD5 或 HMAC-SHA256，结果大写，排除 sign/sign_type 字段</li>
* </ul>
*
* <h3>使用示例</h3>
* <pre>{@code
* Map<String, String> params = new HashMap<>();
* params.put("name", "test");
* params.put("age", "18");
* params.put("empty", "");
* params.put("nullKey", null);
*
* // 默认 MD5 签名
* String sign = SignUtils.sign(params);
*
* // 指定密钥的 MD5 签名（key拼接在末尾）
* String signWithKey = SignUtils.sign(params, "secretKey");
*
* // 密钥直接追加模式（不使用 key= 前缀）
* String directKeySign = SignUtils.signDirectKey(params, "secretKey");
*
* // 微信支付 MD5 签名（大写输出，排除 sign/sign_type）
* String wechatSign = SignUtils.wechatSignMd5(params, "apiKey");
*
* // 微信支付 HMAC-SHA256 签名
* String wechatHmacSign = SignUtils.wechatSignHmacSha256(params, "apiKey");
*
* // 构建包含签名的参数 Map
* Map<String, Object> signedParams = SignUtils.buildSignedParams(params, "secretKey");
* }</pre>ils.wechatSignHmacSha256(params, "apiKey");
*
* // 构建包含签名的参数 Map
* Map<String, Object> signedParams = SignUtils.buildSignedParams(params, "secretKey");
* }</pre>
*
* @author CH
* @since 4.0.0
 */
public class SignUtils {

    /**
    * 默认键值连接符
     */
    private static final String DEFAULT_KEY_VALUE_SEPARATOR = "=";

    /**
    * 默认参数分隔符
     */
    private static final String DEFAULT_PARAM_SEPARATOR = "&";

    /**
    * 默认需要排除的参数名称（小写）
     */
    private static final Set<String> DEFAULT_EXCLUDE_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("sign", "signature")));

    /**
    * 微信支付签名需要排除的参数名称（小写）：标志、标志_类型
     */
    private static final Set<String> WECHAT_EXCLUDE_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("sign", "sign_type")));

    /** 创建 标志工具 实例 */
    private SignUtils() {
    }

    // ==================== MD5 签名（key=secretKey 模式） ====================

    /**
    * 对 映射 参数计算 MD5 签名（无密钥）。
    *
    * @param params 待签名参数，允许为 空
    * @return 32 位小写十六进制 MD5 签名字符串
     */
    public static String sign(Map<String, ?> params) {
        return sign(params, null);
    }

    /**
    * 对 映射 参数计算 MD5 签名（带密钥，密钥以 {@code &key=secretKey} 形式追加）。
    *
    * @param params    待签名参数，允许为 空
    * @param secretKey 签名密钥，允许为 空 或空
    * @return 32 位小写十六进制 MD5 签名字符串
     */
    public static String sign(Map<String, ?> params, String secretKey) {
        return sign(params, secretKey, DEFAULT_KEY_VALUE_SEPARATOR, DEFAULT_PARAM_SEPARATOR);
    }

    /**
    * 对 映射 参数计算 MD5 签名（带密钥，自定义分隔符，密钥以 {@code key=secretKey} 形式追加）。
    * @param params 参数
    * @param secretKey secret键
    * @param kvSeparator kvseparator
    * @param paramSeparator 参数separator
    * @return 标志的结果
     */
    public static String sign(Map<String, ?> params, String secretKey, String kvSeparator, String paramSeparator) {
        return sign(params, secretKey, kvSeparator, paramSeparator, null);
    }

    /**
    * 对 映射 参数计算 MD5 签名（带密钥，自定义分隔符，可排除指定 键）。
     */
    public static String sign(Map<String, ?> params, String secretKey, String kvSeparator,
                              String paramSeparator, Set<String> excludeKeys) {
        String content = buildSignContent(params, secretKey, kvSeparator, paramSeparator, excludeKeys, false, true);
        return DigestUtils.md5(content);
    }

    // ==================== MD5 签名（密钥直接追加模式） ====================

    /**
    * 对 映射 参数计算 MD5 签名（密钥直接追加在末尾，不使用 {@code key=} 前缀）。
    * @param params 参数
    * @param secretKey secret键
    * @return 标志direct键的结果
     */
    public static String signDirectKey(Map<String, ?> params, String secretKey) {
        return signDirectKey(params, secretKey, DEFAULT_KEY_VALUE_SEPARATOR, DEFAULT_PARAM_SEPARATOR);
    }

    /**
    * 标志direct键
    * @param params 参数
    * @param secretKey 密钥
    * @param kvSeparator kvseparator
    * @param paramSeparator 参数separator
     */
    public static String signDirectKey(Map<String, ?> params, String secretKey,
                                       String kvSeparator, String paramSeparator) {
        return signDirectKey(params, secretKey, kvSeparator, paramSeparator, null);
    }

    /**
    * 标志direct键
    * @param params 参数
    * @param secretKey 密钥
    * @param kvSeparator kvseparator
    * @param paramSeparator 参数separator
    * @param excludeKeys exclude键
     */
    public static String signDirectKey(Map<String, ?> params, String secretKey,
                                       String kvSeparator, String paramSeparator,
                                       Set<String> excludeKeys) {
        String content = buildSignContent(params, secretKey, kvSeparator, paramSeparator, excludeKeys, true, true);
        return DigestUtils.md5(content);
    }

    // ==================== SHA256 签名 ====================

    /**
    * 标志sha
    *
    * @param params 参数
    * @param secretKey secret键
    * @return 标志sha256的结果
     */
    public static String signSha256(Map<String, ?> params, String secretKey) {
        return signSha256(params, secretKey, DEFAULT_KEY_VALUE_SEPARATOR, DEFAULT_PARAM_SEPARATOR);
    }

    /**
    * 标志sha
    * @param params 参数
    * @param secretKey 密钥
    * @param kvSeparator kvseparator
    * @param paramSeparator 参数separator
     */
    public static String signSha256(Map<String, ?> params, String secretKey,
                                    String kvSeparator, String paramSeparator) {
        return signSha256(params, secretKey, kvSeparator, paramSeparator, null);
    }

    /**
    * 标志sha
    * @param params 参数
    * @param secretKey 密钥
    * @param kvSeparator kvseparator
    * @param paramSeparator 参数separator
    * @param excludeKeys exclude键
     */
    public static String signSha256(Map<String, ?> params, String secretKey,
                                    String kvSeparator, String paramSeparator,
                                    Set<String> excludeKeys) {
        String content = buildSignContent(params, secretKey, kvSeparator, paramSeparator, excludeKeys, true, true);
        return DigestUtils.sha256(content);
    }

    // ==================== 微信支付签名 ====================

    /**
    * 微信支付 MD5 签名。
    *
    * <p>算法：按 key 的 ASCII 码从小到大排序，过滤空值，排除 sign/sign_type 字段，
    * 拼接为 {@code key1=value1&key2=value2&key=apiKey}，计算 MD5 并转大写。</p>
    *
    * @param params  待签名参数，允许为 空
    * @param apiKey  微信支付 API 密钥
    * @return 32 位大写十六进制 MD5 签名字符串
     */
    public static String wechatSignMd5(Map<String, ?> params, String apiKey) {
        String content = buildSignContent(params, apiKey, DEFAULT_KEY_VALUE_SEPARATOR,
                DEFAULT_PARAM_SEPARATOR, WECHAT_EXCLUDE_KEYS, false, true);
        return DigestUtils.md5(content).toUpperCase();
    }

    /**
    * 微信支付 HMAC-SHA256 签名。
    *
    * <p>算法：按 key 的 ASCII 码从小到大排序，过滤空值，排除 sign/sign_type 字段，
    * 拼接为 {@code key1=value1&key2=value2&key=apiKey}，以 API密钥 为密钥计算 HMAC-SHA256 并转大写。</p>
    *
    * @param params  待签名参数，允许为 空
    * @param apiKey  微信支付 API 密钥
    * @return 64 位大写十六进制 HMAC-SHA256 签名字符串
     */
    public static String wechatSignHmacSha256(Map<String, ?> params, String apiKey) {
        String content = buildSignContent(params, apiKey, DEFAULT_KEY_VALUE_SEPARATOR,
                DEFAULT_PARAM_SEPARATOR, WECHAT_EXCLUDE_KEYS, false, true);
        return DigestUtils.hmacSha256(content, apiKey).toUpperCase();
    }

    /**
    * 验证微信支付 MD5 签名。
    *
    * @param params  包含 标志 字段的参数 映射
    * @param sign    待验证的签名字符串（大写或小写均可）
    * @param apiKey  微信支付 API 密钥
    * @return 签名匹配返回 {@code true}
     */
    public static boolean wechatVerifyMd5(Map<String, ?> params, String sign, String apiKey) {
        if (sign == null) {
            return false;
        }
        String calculated = wechatSignMd5(params, apiKey);
        return sign.equalsIgnoreCase(calculated);
    }

    /**
    * 验证微信支付 HMAC-SHA256 签名。
    *
    * @param params  包含 标志 字段的参数 映射
    * @param sign    待验证的签名字符串（大写或小写均可）
    * @param apiKey  微信支付 API 密钥
    * @return 签名匹配返回 {@code true}
     */
    public static boolean wechatVerifyHmacSha256(Map<String, ?> params, String sign, String apiKey) {
        if (sign == null) {
            return false;
        }
        String calculated = wechatSignHmacSha256(params, apiKey);
        return sign.equalsIgnoreCase(calculated);
    }

    /**
    * 构建微信支付签名字符串（不计算摘要，仅返回待签名字符串）。
    *
    * <p>可用于调试或日志输出，查看实际参与签名的内容。</p>
    *
    * @param params  待签名参数
    * @param apiKey  微信支付 API 密钥
    * @return 待签名字符串，格式为 {@code key1=value1&key2=value2&key=apiKey}
     */
    public static String buildWechatSignContent(Map<String, ?> params, String apiKey) {
        return buildSignContent(params, apiKey, DEFAULT_KEY_VALUE_SEPARATOR,
                DEFAULT_PARAM_SEPARATOR, WECHAT_EXCLUDE_KEYS, false, true);
    }

    // ==================== 构建待签名字符串 ====================

    /**
    * 构建标志内容
    * @param params 参数
    * @param secretKey 密钥
    * @param kvSeparator kvseparator
    * @param paramSeparator 参数separator
     */
    public static String buildSignContent(Map<String, ?> params, String secretKey,
                                          String kvSeparator, String paramSeparator) {
        return buildSignContent(params, secretKey, kvSeparator, paramSeparator, null, false, true);
    }

    /**
    * 构建待签名字符串（6 参数版本，默认过滤空白值）。
     */
    public static String buildSignContent(Map<String, ?> params, String secretKey,
                                          String kvSeparator, String paramSeparator,
                                          Set<String> excludeKeys, boolean directKey) {
        return buildSignContent(params, secretKey, kvSeparator, paramSeparator, excludeKeys, directKey, true);
    }

    /**
    * 构建待签名字符串（完整参数）。
    *
    * <p>处理步骤：</p>
    * <ol>
    *   <li>过滤 key 为 null 的条目</li>
    *   <li>若 filterBlank=true，过滤 value 为 null 或空白字符串的条目；若 filterBlank=false，仅过滤 value 为 null 的条目</li>
    *   <li>排除 excludeKeys 中的 key（大小写不敏感）</li>
    *   <li>按 key 自然排序（{@link TreeMap}）</li>
    *   <li>按指定格式拼接为 {@code key1{kvSep}value1{paramSep}key2{kvSep}value2...}</li>
    *   <li>若密钥不为空，根据 directKey 模式决定追加方式：
    *       <ul>
    *         <li>{@code directKey=false}：追加 {@code {paramSep}key{kvSep}secretKey}</li>
    *         <li>{@code directKey=true}：直接追加 {@code secretKey}</li>
    *       </ul>
    *   </li>
    * </ol>
    *
    * @param params        待签名参数，允许为 空
    * @param secretKey     签名密钥，允许为 空 或空
    * @param kvSeparator   键值连接符
    * @param paramSeparator 参数分隔符
    * @param excludeKeys   需要排除的 键 集合，允许为 空
    * @param directKey     是否密钥直接追加模式
    * @param filterBlank   是否过滤空白值（true=过滤 空 和空白字符串；false=仅过滤 空）
    * @return 拼接后的待签名字符串
     */
    public static String buildSignContent(Map<String, ?> params, String secretKey,
                                          String kvSeparator, String paramSeparator,
                                          Set<String> excludeKeys, boolean directKey,
                                          boolean filterBlank) {
        String kvSep = kvSeparator == null ? DEFAULT_KEY_VALUE_SEPARATOR : kvSeparator;
        String paramSep = paramSeparator == null ? DEFAULT_PARAM_SEPARATOR : paramSeparator;

        // 构建排除集合（小写化）
        Set<String> excludeSet = normalizeExcludeKeys(excludeKeys);

 // 按 键 自然排序，过滤空值，排除指定 键
        TreeMap<String, String> sortedMap = new TreeMap<>();
        if (params != null) {
            for (Map.Entry<String, ?> entry : params.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key == null) {
                    continue;
                }
                if (excludeSet.contains(key.toLowerCase())) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                String strValue = value.toString();
                if (filterBlank && StringUtils.isBlank(strValue)) {
                    continue;
                }
                sortedMap.put(key, strValue);
            }
        }

        // 拼接: key1=value1&key2=value2
        String content = sortedMap.entrySet().stream()
                .map(e -> e.getKey() + kvSep + e.getValue())
                .collect(Collectors.joining(paramSep));

        // 追加密钥
        if (StringUtils.isNotBlank(secretKey)) {
            if (directKey) {
                content = content + secretKey;
            } else {
                if (!content.isEmpty()) {
                    content = content + paramSep;
                }
                content = content + "key" + kvSep + secretKey;
            }
        }

        return content;
    }

    // ==================== 构建排序参数字符串 ====================

    /**
    * 构建排序后的参数字符串（排除 标志/签名，默认过滤空白值）。
    * @param params 参数
    * @return 构建排序参数字符串的结果
     */
    public static String buildSortedParamString(Map<String, ?> params) {
        return buildSortedParamString(params, DEFAULT_EXCLUDE_KEYS);
    }

    /**
    * 构建排序参数字符串
    *
    * @param params 参数
    * @param excludeKeys exclude键
    * @return 构建排序参数字符串的结果
     */
    public static String buildSortedParamString(Map<String, ?> params, Set<String> excludeKeys) {
        return buildSortedParamString(params, excludeKeys, DEFAULT_PARAM_SEPARATOR, DEFAULT_KEY_VALUE_SEPARATOR);
    }

    /**
    * 构建排序参数字符串
    * @param params 参数
    * @param excludeKeys exclude键
    * @param separator separator
    * @param kvSeparator kvseparator
     */
    public static String buildSortedParamString(Map<String, ?> params, Set<String> excludeKeys,
                                                String separator, String kvSeparator) {
        return buildSortedParamString(params, excludeKeys, separator, kvSeparator, true);
    }

    /**
    * 构建排序后的参数字符串（完整参数）。
    *
    * @param params        待签名参数，允许为 空
    * @param excludeKeys   需要排除的 键 集合，允许为 空
    * @param separator     参数分隔符
    * @param kvSeparator   键值连接符
    * @param filterBlank   是否过滤空白值（true=过滤 空 和空白字符串；false=仅过滤 空）
    * @return 排序后的参数字符串
     */
    public static String buildSortedParamString(Map<String, ?> params, Set<String> excludeKeys,
                                                String separator, String kvSeparator,
                                                boolean filterBlank) {
        return buildSignContent(params, null, kvSeparator, separator, excludeKeys, true, filterBlank);
    }

    // ==================== 签名验证 ====================

    /**
    * 验证
    *
    * @param params 参数
    * @param sign 标志
    * @param secretKey secret键
    * @return 验证的结果
     */
    public static boolean verify(Map<String, ?> params, String sign, String secretKey) {
        if (sign == null) {
            return false;
        }
        Map<String, ?> filteredParams = filterSignKey(params);
        String calculated = sign(filteredParams, secretKey);
        return sign.equalsIgnoreCase(calculated);
    }

    /**
    * 验证direct键
    *
    * @param params 参数
    * @param sign 标志
    * @param secretKey secret键
    * @return 验证direct键的结果
     */
    public static boolean verifyDirectKey(Map<String, ?> params, String sign, String secretKey) {
        return verifyDirectKey(params, sign, secretKey, DEFAULT_EXCLUDE_KEYS);
    }

    /**
    * 验证direct键
    * @param params 参数
    * @param sign 标志
    * @param secretKey 密钥
    * @param excludeKeys exclude键
     */
    public static boolean verifyDirectKey(Map<String, ?> params, String sign, String secretKey,
                                          Set<String> excludeKeys) {
        if (sign == null) {
            return false;
        }
        String calculated = signDirectKey(params, secretKey, DEFAULT_KEY_VALUE_SEPARATOR,
                DEFAULT_PARAM_SEPARATOR, excludeKeys);
        return sign.equalsIgnoreCase(calculated);
    }

    /**
    * 验证Sha
    *
    * @param params 参数
    * @param sign 标志
    * @param secretKey secret键
    * @return 验证sha256的结果
     */
    public static boolean verifySha256(Map<String, ?> params, String sign, String secretKey) {
        return verifySha256(params, sign, secretKey, DEFAULT_EXCLUDE_KEYS);
    }

    /**
    * 验证sha
    * @param params 参数
    * @param sign 标志
    * @param secretKey 密钥
    * @param excludeKeys exclude键
     */
    public static boolean verifySha256(Map<String, ?> params, String sign, String secretKey,
                                       Set<String> excludeKeys) {
        if (sign == null) {
            return false;
        }
        String calculated = signSha256(params, secretKey, DEFAULT_KEY_VALUE_SEPARATOR,
                DEFAULT_PARAM_SEPARATOR, excludeKeys);
        return sign.equalsIgnoreCase(calculated);
    }

    // ==================== 构建签名参数 Map ====================

    /**
    * 构建标志参数
    *
    * @param params 参数
    * @param secretKey secret键
    * @return 构建标志参数的结果
     */
    public static Map<String, Object> buildSignedParams(Map<String, ?> params, String secretKey) {
        return buildSignedParams(params, secretKey, "sign");
    }

    /**
    * 构建标志参数
    *
    * @param params 参数
    * @param secretKey secret键
    * @param signKey 标志键
    * @return 构建标志参数的结果
     */
    public static Map<String, Object> buildSignedParams(Map<String, ?> params, String secretKey, String signKey) {
        Map<String, Object> signedParams = params != null ? new HashMap<>(params) : new HashMap<>();
        String signValue = signDirectKey(params, secretKey);
        signedParams.put(signKey, signValue);
        return signedParams;
    }

    /**
    * 构建sha标志参数
    *
    * @param params 参数
    * @param secretKey secret键
    * @return 构建sha256标志参数的结果
     */
    public static Map<String, Object> buildSha256SignedParams(Map<String, ?> params, String secretKey) {
        Map<String, Object> signedParams = params != null ? new HashMap<>(params) : new HashMap<>();
        String signValue = signSha256(params, secretKey);
        signedParams.put("sign", signValue);
        return signedParams;
    }

    // ==================== 提取并验证签名 ====================

    /**
    * extract和验证标志
    *
    * @param params 参数
    * @param secretKey secret键
    * @return extract和验证标志的结果
     */
    public static boolean extractAndVerifySign(Map<String, Object> params, String secretKey) {
        return extractAndVerifySign(params, secretKey, "sign");
    }

    /**
    * extract和验证标志
    *
    * @param params 参数
    * @param secretKey secret键
    * @param signKey 标志键
    * @return extract和验证标志的结果
     */
    public static boolean extractAndVerifySign(Map<String, Object> params, String secretKey, String signKey) {
        if (params == null || !params.containsKey(signKey)) {
            return false;
        }
        String expectedSign = String.valueOf(params.get(signKey));
        Map<String, Object> paramsWithoutSign = new HashMap<>(params);
        paramsWithoutSign.remove(signKey);
        return verifyDirectKey(paramsWithoutSign, expectedSign, secretKey,
                Collections.singleton(signKey.toLowerCase()));
    }

    // ==================== 内部工具方法 ====================

    /**
    * 归一化需要排除的签名键集合：过滤 null、转小写并去重。
    *
    * @param excludeKeys 待排除的键集合（可能含 null 或大写形式）
    * @return 归一化后的小写键集合；入参为 null 或空时返回空集合
     */
    private static Set<String> normalizeExcludeKeys(Set<String> excludeKeys) {
        if (excludeKeys == null || excludeKeys.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>(excludeKeys.size());
        for (String key : excludeKeys) {
            if (key != null) {
                result.add(key.toLowerCase());
            }
        }
        return result;
    }

    private static Map<String, ?> filterSignKey(Map<String, ?> params) {
        if (params == null) {
            return null;
        }
        return params.entrySet().stream()
                .filter(e -> !"sign".equals(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}