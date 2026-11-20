// [P3C A20 豁免] A20/A30/A31: 姓氏 / 名字用字 / 手机号段 / 行政区划等非业务枚举值场景，豁免。
package com.chua.common.support.mock;

import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nonnull;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 用户信息 / 用户画像 Mock 生成器
 *
 * <p>仿「随机用户信息生成器」的通用口径，一次生成一份自洽的用户画像：
 * 姓名、性别、出生日期、身份证号（按 GB 11643-1999 加权因子计算校验位）、
 * 手机号、邮箱、住址与公司名。可通过 {@link MockEnvironment#setKeyword(String)}
 * 附加 {@code creditcard} / {@code card} 关键词，让画像同时携带一张虚拟信用卡
 * （卡号复用 {@link CreditCardMockString}：按卡组织前缀 + Luhn 校验位生成）。</p>
 *
 * <p>输出为 JSON 字符串（Jackson 序列化，字段顺序即写入顺序）：
 * {@code name / gender / birthday / idNumber / phone / email / address / company}，
 * 携带卡片时追加 {@code cardNumber / cardVendor / cardExpire / cardCvv}。</p>
 *
 * <p>仅用于测试数据填充，不代表真实用户或真实银行卡，禁止用于任何实名 / 支付场景。</p>
 *
 * <p>本实现下沉在 common-starter（常用生成器随底层走，使用方无需依赖 mock-starter），
 * 通过 {@code META-INF/extensions/com.chua.common.support.mock.MockString} 手工登记。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi({"user-profile", "userinfo", "user-info", "profile"})
public class UserProfileMockString implements MockString {

    /**
     * JSON 序列化器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 常见姓氏池
     */
    private static final String[] SURNAMES = {
            "王", "李", "张", "刘", "陈", "杨", "黄", "赵", "吴", "周",
            "徐", "孙", "马", "朱", "胡", "郭", "何", "林", "罗", "高",
            "郑", "梁", "谢", "宋", "唐", "许", "韩", "冯", "邓", "曹",
            "彭", "曾", "肖", "田", "董", "潘", "袁", "蔡", "蒋", "余",
            "杜", "叶", "程", "苏", "魏", "吕", "丁", "任", "沈", "姚"
    };

    /**
     * 常见名字用字池（随机组合 1~2 字名）
     */
    private static final String[] GIVEN_CHARS = {
            "伟", "芳", "娜", "敏", "静", "丽", "强", "磊", "军", "洋",
            "勇", "艳", "杰", "娟", "涛", "明", "超", "霞", "平", "刚",
            "辉", "鹏", "华", "飞", "鑫", "晨", "曦", "悦", "佳", "怡",
            "博", "文", "昊", "天", "雨", "泽", "梓", "萱", "浩", "宇",
            "子", "涵", "思", "远", "梦", "琪", "欣", "志", "建", "兰"
    };

    /**
     * 常见手机号段
     */
    private static final String[] PHONE_PREFIXES = {
            "133", "135", "136", "137", "138", "139", "147", "150",
            "151", "152", "155", "156", "157", "158", "159", "166",
            "170", "176", "177", "178", "180", "181", "182", "185",
            "186", "187", "188", "189", "191", "193", "195", "198", "199"
    };

    /**
     * 身份证地区码池（市辖区级行政区划代码）
     */
    private static final String[] ID_AREA_CODES = {
            "110101", "310104", "440304", "330102", "510104",
            "420102", "320102", "610102", "370202", "410102",
            "430102", "500103", "120101", "350203", "530102", "230102"
    };

    /**
     * 常用邮箱域名
     */
    private static final String[] EMAIL_DOMAINS = {
            "163.com", "126.com", "qq.com", "gmail.com",
            "outlook.com", "sina.com", "sohu.com", "139.com"
    };

    /**
     * 地址省 / 市配对池
     */
    private static final String[][] REGIONS = {
            {"北京市", "北京市"}, {"上海市", "上海市"}, {"广东省", "深圳市"},
            {"广东省", "广州市"}, {"浙江省", "杭州市"}, {"江苏省", "南京市"},
            {"四川省", "成都市"}, {"湖北省", "武汉市"}, {"陕西省", "西安市"},
            {"山东省", "青岛市"}, {"福建省", "厦门市"}, {"河南省", "郑州市"}
    };

    /**
     * 区 / 县名池
     */
    private static final String[] DISTRICTS = {
            "朝阳区", "海淀区", "南山区", "福田区", "西湖区", "鼓楼区",
            "锦江区", "武昌区", "雁塔区", "市南区", "思明区", "金水区"
    };

    /**
     * 街道名池
     */
    private static final String[] STREETS = {
            "中山路", "人民路", "解放大道", "建设路", "和平街", "文化路",
            "科技路", "金融街", "滨江大道", "长安街", "幸福路", "望江路"
    };

    /**
     * 公司名字号池
     */
    private static final String[] COMPANY_BRANDS = {
            "星辰", "云帆", "博远", "瑞丰", "恒信", "智联",
            "蓝海", "启航", "宏图", "天翼", "绿洲", "锐达"
    };

    /**
     * 公司后缀池
     */
    private static final String[] COMPANY_SUFFIXES = {
            "科技有限公司", "信息技术有限公司", "网络科技有限公司",
            "贸易有限公司", "文化传媒有限公司", "咨询服务有限公司"
    };

    /**
     * 身份证校验位加权因子（GB 11643-1999）
     */
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /**
     * 身份证校验位字符表（余数 0~10 对应）
     */
    private static final char[] ID_CHECK_CHARS = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    @Override
    @Nonnull
    /**
     * get String
     * @param environment 参数
     * @return 返回值
     */
    public String getString(@Nonnull MockEnvironment environment) {
        String keyword = environment.getKeyword();
        String token = null == keyword ? "" : keyword.toLowerCase(Locale.ROOT);
        boolean withCard = token.contains("credit") || token.contains("card");

        int birthYear = 1960 + environment.nextInt(46);
        int birthMonth = 1 + environment.nextInt(12);
        int birthDay = 1 + environment.nextInt(daysInMonth(birthYear, birthMonth));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", randomName(environment));
        data.put("gender", environment.nextInt(2) == 0 ? "男" : "女");
        data.put("birthday", String.format("%04d-%02d-%02d", birthYear, birthMonth, birthDay));
        data.put("idNumber", randomIdNumber(environment, birthYear, birthMonth, birthDay));
        data.put("phone", randomPhone(environment));
        data.put("email", randomEmail(environment));
        data.put("address", randomAddress(environment));
        data.put("company", randomCompany(environment));
        if (withCard) {
            fillCard(environment, data);
        }
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("用户画像 JSON 序列化失败", e);
        }
    }

    /**
     * 追加虚拟信用卡信息：卡号复用 {@link CreditCardMockString}，
     * 有效期取未来 1~5 年，CVV 按卡组织取 3 位（Amex 4 位）。
     *
     * @param environment 环境
     * @param data        画像数据（写入 cardNumber / cardVendor / cardExpire / cardCvv）
     */
    private void fillCard(MockEnvironment environment, Map<String, Object> data) {
        String number = new CreditCardMockString().getString(environment);
        String vendor = cardVendorLabel(number);
        data.put("cardNumber", number);
        data.put("cardVendor", vendor);
        int expireMonth = 1 + environment.nextInt(12);
        int expireYear = LocalDate.now().getYear() + 1 + environment.nextInt(5);
        data.put("cardExpire", String.format("%02d/%02d", expireMonth, expireYear % 100));
        int cvvLength = "American Express".equals(vendor) ? 4 : 3;
        StringBuilder cvv = new StringBuilder(cvvLength);
        for (int i = 0; i < cvvLength; i++) {
            cvv.append(environment.nextInt(10));
        }
        data.put("cardCvv", cvv.toString());
    }

    /**
     * 按卡号前缀推导卡组织展示名。
     *
     * @param number 卡号
     * @return 卡组织名（Visa / Mastercard / 银联等）
     */
    private static String cardVendorLabel(String number) {
        if (number.startsWith("4")) {
            return "Visa";
        }
        if (number.startsWith("34") || number.startsWith("37")) {
            return "American Express";
        }
        if (number.startsWith("62")) {
            return "银联";
        }
        if (number.startsWith("6011") || number.startsWith("64") || number.startsWith("65")) {
            return "Discover";
        }
        if (number.startsWith("35")) {
            return "JCB";
        }
        if (number.startsWith("30") || number.startsWith("36") || number.startsWith("38")) {
            return "Diners Club";
        }
        if (number.startsWith("5") || number.startsWith("2")) {
            return "Mastercard";
        }
        return "Unknown";
    }

    /**
     * 随机中文姓名（常见姓氏 + 1~2 字名）。
     *
     * @param environment 环境
     * @return 姓名
     */
    private String randomName(MockEnvironment environment) {
        String surname = SURNAMES[environment.nextInt(SURNAMES.length)];
        int givenLength = 1 + environment.nextInt(2);
        StringBuilder given = new StringBuilder(givenLength);
        for (int i = 0; i < givenLength; i++) {
            given.append(GIVEN_CHARS[environment.nextInt(GIVEN_CHARS.length)]);
        }
        return surname + given;
    }

    /**
     * 随机 18 位身份证号（地区码 + 出生年月日 + 3 位顺序码 + GB 校验位）。
     *
     * @param environment 环境
     * @param year        出生年（与生日同源）
     * @param month       出生月
     * @param day         出生日
     * @return 身份证号
     */
    private String randomIdNumber(MockEnvironment environment, int year, int month, int day) {
        String area = ID_AREA_CODES[environment.nextInt(ID_AREA_CODES.length)];
        String base = area + String.format("%04d%02d%02d", year, month, day)
                + String.format("%03d", environment.nextInt(1000));
        int sum = 0;
        for (int i = 0; i < base.length(); i++) {
            sum += (base.charAt(i) - '0') * ID_WEIGHTS[i];
        }
        return base + ID_CHECK_CHARS[sum % 11];
    }

    /**
     * 随机 11 位手机号（真实号段 + 8 位随机尾号）。
     *
     * @param environment 环境
     * @return 手机号
     */
    private String randomPhone(MockEnvironment environment) {
        String prefix = PHONE_PREFIXES[environment.nextInt(PHONE_PREFIXES.length)];
        StringBuilder tail = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            tail.append(environment.nextInt(10));
        }
        return prefix + tail;
    }

    /**
     * 随机邮箱（随机小写本地部分 + 2 位数字 + 常用域名）。
     *
     * @param environment 环境
     * @return 邮箱地址
     */
    private String randomEmail(MockEnvironment environment) {
        int length = 6 + environment.nextInt(5);
        StringBuilder local = new StringBuilder(length + 2);
        for (int i = 0; i < length; i++) {
            local.append((char) ('a' + environment.nextInt(26)));
        }
        local.append(10 + environment.nextInt(90));
        return local + "@" + EMAIL_DOMAINS[environment.nextInt(EMAIL_DOMAINS.length)];
    }

    /**
     * 随机住址（省市区 + 街道门牌 + 楼栋房号）。
     *
     * @param environment 环境
     * @return 住址
     */
    private String randomAddress(MockEnvironment environment) {
        String[] region = REGIONS[environment.nextInt(REGIONS.length)];
        String district = DISTRICTS[environment.nextInt(DISTRICTS.length)];
        String street = STREETS[environment.nextInt(STREETS.length)];
        int number = 1 + environment.nextInt(200);
        int building = 1 + environment.nextInt(12);
        int floor = 1 + environment.nextInt(18);
        int room = floor * 100 + 1 + environment.nextInt(4);
        return region[0] + region[1] + district + street + " " + number + " 号 "
                + building + " 幢 " + room + " 室";
    }

    /**
     * 随机公司名（城市 + 字号 + 行业后缀）。
     *
     * @param environment 环境
     * @return 公司名
     */
    private String randomCompany(MockEnvironment environment) {
        String[] region = REGIONS[environment.nextInt(REGIONS.length)];
        String brand = COMPANY_BRANDS[environment.nextInt(COMPANY_BRANDS.length)];
        String suffix = COMPANY_SUFFIXES[environment.nextInt(COMPANY_SUFFIXES.length)];
        return region[1] + brand + suffix;
    }

    /**
     * 某年某月的天数（含闰年 2 月）。
     *
     * @param year  年
     * @param month 月（1~12）
     * @return 天数
     */
    private static int daysInMonth(int year, int month) {
        switch (month) {
            case 2:
                return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }
}
