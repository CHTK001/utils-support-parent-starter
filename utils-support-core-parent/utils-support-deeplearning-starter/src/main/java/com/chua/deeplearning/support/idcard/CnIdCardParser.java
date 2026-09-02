package com.chua.deeplearning.support.idcard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中国居民身份证文字解析器
 *
 * <p>从 PaddleOCR 识别的身份证文字中提取结构化字段。
 * 支持正面（姓名/性别/民族/出生日期/地址/身份证号/签发机关）和反面（有效期限）。
 * 使用正则匹配各行关键字+值模式。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class CnIdCardParser {

    private static final Pattern NAME_PATTERN = Pattern.compile("姓名\\s*[:：]\\s*(\\S+)");
    private static final Pattern GENDER_PATTERN = Pattern.compile("性别\\s*[:：]\\s*(\\S+)");
    private static final Pattern ETHNICITY_PATTERN = Pattern.compile("民族\\s*[:：]\\s*(\\S+)");
    private static final Pattern BIRTH_PATTERN = Pattern.compile("出生\\s*[:：]\\s*(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern BIRTH_SHORT_PATTERN = Pattern.compile("出生\\s*[:：]\\s*(\\d{4})[^\\d]{1,3}(\\d{1,2})[^\\d]{1,3}(\\d{1,2})");
    private static final Pattern ID_PATTERN = Pattern.compile("公民身份号码\\s*[:：]\\s*([0-9Xx]{17}[0-9Xx])");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("住址\\s*[:：]\\s*([^\n\r]+)");
    private static final Pattern ISSUE_PATTERN = Pattern.compile("签发机关\\s*[:：]\\s*([^\n\r]+)");
    private static final Pattern VALID_PATTERN = Pattern.compile("有效期限\\s*[:：]\\s*([^原]+)(?:\\s*至\\s*([^\n\r]*))?(?:\\s*原)?");
    private static final Pattern VALID_LONG_PATTERN = Pattern.compile("有效期限\\s*[:：]\\s*(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*至\\s*(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern VALID_SIMPLE_PATTERN = Pattern.compile("有效期限\\s*[:：]\\s*(\\d{4})[^\\d]{1,3}(\\d{1,2})[^\\d]{1,3}(\\d{1,2})");

    /**
     * 从识别文本中解析身份证信息
     *
     * @param text PaddleOCR 识别的身份证正面或反面文字
     * @return 解析结果
     */
    public CnIdCardResult parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        CnIdCardResult result = new CnIdCardResult();
        result.setConfidence(1.0f);

        Matcher m;

        // 姓名
        m = NAME_PATTERN.matcher(text);
        if (m.find()) result.setName(m.group(1).trim());

        // 性别
        m = GENDER_PATTERN.matcher(text);
        if (m.find()) result.setGender(m.group(1).trim());

        // 民族
        m = ETHNICITY_PATTERN.matcher(text);
        if (m.find()) result.setEthnicity(m.group(1).trim());

        // 出生日期（带"年/月/日"格式）
        m = BIRTH_PATTERN.matcher(text);
        if (m.find()) {
            result.setBirthDate(m.group(1) + "-" + pad(m.group(2)) + "-" + pad(m.group(3)));
        } else {
            m = BIRTH_SHORT_PATTERN.matcher(text);
            if (m.find()) {
                result.setBirthDate(m.group(1) + "-" + pad(m.group(2)) + "-" + pad(m.group(3)));
            }
        }

        // 身份证号
        m = ID_PATTERN.matcher(text);
        if (m.find()) result.setIdNumber(m.group(1).toUpperCase().trim());

        // 住址
        m = ADDRESS_PATTERN.matcher(text);
        if (m.find()) result.setAddress(m.group(1).trim());

        // 签发机关
        m = ISSUE_PATTERN.matcher(text);
        if (m.find()) result.setIssueAuthority(m.group(1).trim());

        // 有效期限（完整格式）
        m = VALID_LONG_PATTERN.matcher(text);
        if (m.find()) {
            result.setValidPeriod(m.group(1) + "-" + pad(m.group(2)) + "-" + pad(m.group(3))
                    + " 至 " + m.group(4) + "-" + pad(m.group(5)) + "-" + pad(m.group(6)));
        } else {
            // 有效期限（短格式）
            m = VALID_SIMPLE_PATTERN.matcher(text);
            if (m.find()) {
                result.setValidPeriod(pad(m.group(1)) + "-" + pad(m.group(2)) + "-" + pad(m.group(3)));
            } else {
                m = VALID_PATTERN.matcher(text);
                if (m.find()) {
                    result.setValidPeriod(m.group(1).trim());
                }
            }
        }

        return result;
    }

    /**
     * 从多行 OCR 结果中合并解析
     *
     * @param lines OCR 识别的每一行文字
     * @return 解析结果
     */
    public CnIdCardResult parseLines(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                sb.append(line).append("\n");
            }
        }
        return parse(sb.toString());
    }

    private static String pad(String s) {
        return s.length() >= 2 ? s : "0" + s;
    }
}
