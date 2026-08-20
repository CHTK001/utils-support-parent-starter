package com.chua.mock.support;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockString SPI 发现与生成测试
 *
 * @author CH
 * @since 4.0.0.42
 */
class MockStringFactoryTest {

    /**
     * 全部已注册生成器名称。
     */
    private static final String[] ALL_NAMES = {
            "random", "uuid", "name", "en-name", "phone", "email", "address", "company",
            "username", "password", "chinese", "letter", "digit", "date", "datetime", "ip",
            "city", "postcode", "bankcard", "idcard", "plate",
            "uscc", "telephone", "mac", "ipv6", "province", "street", "coordinate", "amount",
            "order-no", "tracking-no", "pinyin", "nickname", "captcha", "gender",
            "constellation", "zodiac", "job", "color", "port", "lorem", "sentence",
            "version", "url", "domain",
            "snowflake", "short-id", "md5", "base64", "file-ext", "serial-no",
            "invoice", "stock-code", "cvv",
            "passport", "driver-license", "org-code", "vin", "wechat", "qq",
            "school", "school-stage", "image"
    };

    /**
     * 校验 SPI 索引自动生成且全部生成器可被按名发现。
     */
    @Test
    void shouldDiscoverAllMockStringImplementations() {
        Set<String> extensions = ServiceProvider.of(MockString.class).getExtensions();
        assertFalse(extensions.isEmpty(), "应能发现至少一个 MockString 实现");
        assertTrue(MockStringFactory.isSupport("random"), "应包含默认的 random 实现");
        assertTrue(MockStringFactory.isSupport("name"), "应包含 name 实现");
        assertTrue(MockStringFactory.isSupport("phone"), "应包含 phone 实现");
        assertTrue(MockStringFactory.isSupport("idcard"), "应包含 idcard 实现");
        assertTrue(MockStringFactory.isSupport("bankcard"), "应包含 bankcard 实现");
        assertTrue(MockStringFactory.isSupport("postcode"), "应包含 postcode 实现");
        assertTrue(MockStringFactory.isSupport("uscc"), "应包含 uscc 实现");
        assertTrue(MockStringFactory.isSupport("vin"), "应包含 vin 实现");
    }

    /**
     * 校验常用生成器均可产出非空字符串数据。
     */
    @Test
    void shouldGenerateAllKindsOfString() {
        for (String name : ALL_NAMES) {
            String value = MockStringFactory.generate(name);
            assertNotNull(value, name + " 应能生成数据");
            assertFalse(value.isEmpty(), name + " 不应生成空字符串");
        }
    }

    /**
     * 校验银行卡号通过 Luhn 校验且长度为 16 或 19 位。
     */
    @Test
    void shouldGenerateValidBankCard() {
        for (int i = 0; i < 50; i++) {
            String card = MockStringFactory.generate("bankcard");
            assertNotNull(card);
            int length = card.length();
            assertTrue(length == 16 || length == 19, "银行卡号长度应为 16 或 19");
            assertTrue(luhnValid(card), "银行卡号应通过 Luhn 校验: " + card);
        }
    }

    /**
     * 校验身份证号为 18 位且校验码正确。
     */
    @Test
    void shouldGenerateValidIdCard() {
        for (int i = 0; i < 50; i++) {
            String id = MockStringFactory.generate("idcard");
            assertNotNull(id);
            assertEquals(18, id.length(), "身份证号应为 18 位");
            assertTrue(idCardValid(id), "身份证校验码应正确: " + id);
        }
    }

    /**
     * 校验统一社会信用代码为 18 位且 mod 31 校验码正确。
     */
    @Test
    void shouldGenerateValidUscc() {
        for (int i = 0; i < 50; i++) {
            String code = MockStringFactory.generate("uscc");
            assertNotNull(code);
            assertEquals(18, code.length(), "统一社会信用代码应为 18 位");
            assertTrue(usccValid(code), "统一社会信用代码校验码应正确: " + code);
        }
    }

    /**
     * 校验 VIN 车架号为 17 位且校验位正确。
     */
    @Test
    void shouldGenerateValidVin() {
        for (int i = 0; i < 50; i++) {
            String vin = MockStringFactory.generate("vin");
            assertNotNull(vin);
            assertEquals(17, vin.length(), "VIN 应为 17 位");
            assertTrue(vinValid(vin), "VIN 校验位应正确: " + vin);
        }
    }

    /**
     * 校验邮编为 6 位数字。
     */
    @Test
    void shouldGenerateValidPostcode() {
        String postcode = MockStringFactory.generate("postcode");
        assertNotNull(postcode);
        assertEquals(6, postcode.length(), "邮编应为 6 位");
        assertTrue(postcode.matches("\\d{6}"), "邮编应为纯数字");
    }

    /**
     * 校验 MAC 地址格式。
     */
    @Test
    void shouldGenerateValidMac() {
        String mac = MockStringFactory.generate("mac");
        assertNotNull(mac);
        assertTrue(mac.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}"), "MAC 地址格式应合法: " + mac);
    }

    /**
     * 校验 IPv6 地址为 8 组十六进制段。
     */
    @Test
    void shouldGenerateValidIpv6() {
        String ipv6 = MockStringFactory.generate("ipv6");
        assertNotNull(ipv6);
        assertEquals(8, ipv6.split(":").length, "IPv6 应为 8 组");
        assertTrue(ipv6.matches("([0-9a-f]{4}:){7}[0-9a-f]{4}"), "IPv6 格式应合法: " + ipv6);
    }

    /**
     * 校验经纬度格式。
     */
    @Test
    void shouldGenerateValidCoordinate() {
        String coordinate = MockStringFactory.generate("coordinate");
        assertNotNull(coordinate);
        String[] parts = coordinate.split(",");
        assertEquals(2, parts.length, "经纬度应包含经度和纬度");
        double lng = Double.parseDouble(parts[0]);
        double lat = Double.parseDouble(parts[1]);
        assertTrue(lng >= 73 && lng < 136, "经度应在中国范围内: " + lng);
        assertTrue(lat >= 18 && lat < 54, "纬度应在中国范围内: " + lat);
    }

    /**
     * 校验金额格式。
     */
    @Test
    void shouldGenerateValidAmount() {
        String amount = MockStringFactory.generate("amount");
        assertNotNull(amount);
        assertTrue(amount.startsWith("¥"), "金额应带人民币符号");
        assertTrue(amount.substring(1).matches("\\d+\\.\\d{2}"), "金额应保留两位小数: " + amount);
    }

    /**
     * 校验结构化 ID 类生成器格式。
     */
    @Test
    void shouldGenerateValidStructuredIds() {
        assertTrue(MockStringFactory.generate("stock-code").matches("\\d{6}"), "股票代码应为 6 位数字");
        assertTrue(MockStringFactory.generate("passport").matches("[EGDP]\\d{8}"), "护照号格式应合法");
        assertTrue(MockStringFactory.generate("cvv").matches("\\d{3}"), "CVV 应为 3 位数字");
        assertTrue(MockStringFactory.generate("qq").matches("[1-9]\\d{4,10}"), "QQ 号格式应合法");
        assertTrue(MockStringFactory.generate("org-code").matches("[0-9A-HJKMNPQRTUWXY]{8}-[0-9X]"), "组织机构代码格式应合法");

        int port = Integer.parseInt(MockStringFactory.generate("port"));
        assertTrue(port >= 1024 && port <= 65535, "端口号应在 [1024, 65535] 内");

        String captcha = MockStringFactory.generate("captcha");
        assertTrue(captcha.length() >= 4 && captcha.length() <= 6, "验证码应为 4-6 位");

        String wechat = MockStringFactory.generate("wechat");
        assertTrue(wechat.length() >= 6 && wechat.length() <= 20, "微信号应为 6-20 位");
        assertTrue(wechat.matches("[a-zA-Z][a-zA-Z0-9_]+"), "微信号应以字母开头");
    }

    /**
     * 校验图片 URL 格式及指定尺寸生效。
     */
    @Test
    void shouldGenerateValidImageUrl() {
        String url = MockStringFactory.generate("image");
        assertNotNull(url);
        assertTrue(url.matches("https://picsum\\.photos/seed/[a-zA-Z0-9]{8}/\\d+/\\d+"),
                "图片 URL 格式应合法: " + url);

        String sized = MockStringFactory.generate("image", 500);
        assertTrue(sized.endsWith("/500/500"), "指定尺寸应生成正方形图片: " + sized);
    }

    /**
     * 校验按关键词生成图片 URL。
     */
    @Test
    void shouldGenerateImageByKeyword() {
        String url = MockStringFactory.generate("image", "cat");
        assertNotNull(url);
        assertTrue(url.matches("https://loremflickr\\.com/\\d+/\\d+/cat"),
                "关键词图片 URL 格式应合法: " + url);

        String sized = MockStringFactory.generate("image", "dog", 400);
        assertTrue(sized.endsWith("/400/400/dog"), "关键词图片应支持指定尺寸: " + sized);

        assertEquals(null, MockStringFactory.generate("image", (String) null));
        assertEquals(null, MockStringFactory.generate("image", ""));
    }

    /**
     * 校验学段取值合法。
     */
    @Test
    void shouldGenerateValidSchoolStage() {
        java.util.Set<String> stages = java.util.Set.of(
                "幼儿园", "小学", "初中", "高中", "中专", "大专", "本科", "硕士", "博士");
        for (int i = 0; i < 20; i++) {
            String stage = MockStringFactory.generate("school-stage");
            assertTrue(stages.contains(stage), "学段应合法: " + stage);
        }
    }

    /**
     * 校验固定种子环境下生成结果可复现。
     */
    @Test
    void shouldReproduceWithSeededEnvironment() {
        String first = MockStringFactory.generate("random", MockEnvironment.of(20240820L));
        String second = MockStringFactory.generate("random", MockEnvironment.of(20240820L));
        assertEquals(first, second, "相同种子应生成相同结果");
    }

    /**
     * 校验指定长度生效。
     */
    @Test
    void shouldHonorConfiguredLength() {
        String value = MockStringFactory.generate("random", 12);
        assertEquals(12, value.length(), "固定长度应精确生效");
    }

    /**
     * 校验批量生成。
     */
    @Test
    void shouldGenerateList() {
        List<String> values = MockStringFactory.generateList("phone", 5);
        assertEquals(5, values.size(), "批量数量应一致");
        for (String value : values) {
            assertNotNull(value);
            assertEquals(11, value.length(), "手机号应为 11 位");
        }
    }

    /**
     * 校验未注册名称的生成器为 null、生成结果为 null。
     */
    @Test
    void shouldReturnNullForUnknownName() {
        assertFalse(MockStringFactory.isSupport("not-exists"));
        assertEquals(null, MockStringFactory.getMockString("not-exists"));
        assertEquals(null, MockStringFactory.generate("not-exists"));
    }

    /**
     * Luhn 校验算法。
     *
     * @param digits 数字串
     * @return true 表示通过校验
     */
    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n <<= 1;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    /**
     * 身份证校验码算法（GB 11643-1999）。
     *
     * @param id 18 位身份证号
     * @return true 表示校验码正确
     */
    private static boolean idCardValid(String id) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] codes = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (id.charAt(i) - '0') * weights[i];
        }
        return id.charAt(17) == codes[sum % 11];
    }

    /**
     * 统一社会信用代码校验（GB 32100-2015，mod 31）。
     *
     * @param code 18 位统一社会信用代码
     * @return true 表示校验码正确
     */
    private static boolean usccValid(String code) {
        String chars = "0123456789ABCDEFGHJKLMNPQRTUWXY";
        int[] weights = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += chars.indexOf(code.charAt(i)) * weights[i];
        }
        return code.charAt(17) == chars.charAt((31 - sum % 31) % 31);
    }

    /**
     * VIN 校验位算法（ISO 3779，MOD 11）。
     *
     * @param vin 17 位 VIN
     * @return true 表示校验位正确
     */
    private static boolean vinValid(String vin) {
        int[] weights = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            if (i == 8) {
                continue;
            }
            sum += vinValue(vin.charAt(i)) * weights[i];
        }
        int mod = sum % 11;
        return vin.charAt(8) == (mod == 10 ? 'X' : (char) ('0' + mod));
    }

    /**
     * VIN 字符转数字值。
     *
     * @param ch VIN 字符
     * @return 数字值
     */
    private static int vinValue(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        return switch (ch) {
            case 'A', 'J' -> 1;
            case 'B', 'K', 'S' -> 2;
            case 'C', 'L', 'T' -> 3;
            case 'D', 'M', 'U' -> 4;
            case 'E', 'N', 'V' -> 5;
            case 'F', 'W' -> 6;
            case 'G', 'P', 'X' -> 7;
            case 'H', 'Y' -> 8;
            case 'R', 'Z' -> 9;
            default -> 0;
        };
    }
}