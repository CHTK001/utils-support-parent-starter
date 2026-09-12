package com.chua.common.support.datasearch.phone.spi.impl;

import com.chua.common.support.datasearch.phone.model.PhoneLocationInfo;
import com.chua.common.support.datasearch.phone.spi.PhoneLocationProvider;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
* 基于在线号码段接口的手机归属地提供器（在线 API + 内置兜底）。
*
* <p>在线数据源使用 tenapi 手机号码归属地接口（无需 key）：
* {@code https://tenapi.cn/v2/mobile?phone=%s}，返回结构
* {@code {"code":200,"data":{"province":..., "city":..., "carrier":..., ...}}}。
*
* <p>在线请求失败或接口不可达时，自动回退到内置号码段前缀表（覆盖常用运营商与省份），
* 保证核心能力可用。内置兜底基于公开号码段资料，具体属地以运营商实时数据为准。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("tenapi")
public class OnlinePhoneLocationProvider implements PhoneLocationProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(OnlinePhoneLocationProvider.class);

    /** 在线接口地址模板 */
    private static final String DEFAULT_URL_TEMPLATE = "https://tenapi.cn/v2/mobile?phone=%s";

    /** 映射器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP客户端 */
    private final HttpClient httpClient;

    /** 在线接口地址模板 */
    private final String urlTemplate;

    /** 内置号码段前缀表：前 4 位（如 1380）-> 归属地 */
    private static final Map<String, PhoneLocationInfo> PREFIX_TABLE = buildPrefixTable();

    /** 创建 onlinephone位置提供者 实例 */
    public OnlinePhoneLocationProvider() {
        this(DEFAULT_URL_TEMPLATE);
    }

    /**
    * 构造一个指定接口地址模板的提供器。
    *
    * @param urlTemplate 含 {@code %s} 号码占位符的接口地址，如 {@code https://host/api?phone=%s}
     */
    public OnlinePhoneLocationProvider(String urlTemplate) {
        this.urlTemplate = urlTemplate;
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    /** 名称 */
    public String name() {
        return "tenapi";
    }

    @Override
    /** 获取归属地 */
    public PhoneLocationInfo getLocation(String phone) {
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            return null;
        }
        PhoneLocationInfo online = fetchOnline(phone);
        return online != null ? online : lookupFallback(phone);
    }

    /**
    * 在线查询
    *
    * @param phone phone
    * @return 获取online的结果
     */
    private PhoneLocationInfo fetchOnline(String phone) {
        try {
            ClientResponse resp = httpClient.get(String.format(urlTemplate, phone));
            if (!resp.isSuccess()) {
                return null;
            }
            JsonNode root = MAPPER.readTree(resp.getBodyString());
            JsonNode data = root.path("data");
            if (data == null || !data.isObject()) {
                return null;
            }
            String province = text(data, "province");
            String city = text(data, "city");
            String carrier = text(data, "carrier");
            if (province.isEmpty() && city.isEmpty() && carrier.isEmpty()) {
                return null;
            }
            return new PhoneLocationInfo(phone, province, city, carrier,
                    text(data, "areaCode"), text(data, "postCode"));
        } catch (Exception e) {
            log.warn("[tenapi] 手机归属地在线查询失败: {}", e.getMessage());
            return null;
        }
    }

    /**
    * 内置前缀表兜底
    *
    * @param phone phone
    * @return lookup降级的结果
     */
    private PhoneLocationInfo lookupFallback(String phone) {
        String prefix4 = phone.substring(0, 4);
        PhoneLocationInfo hit = PREFIX_TABLE.get(prefix4);
        if (hit != null) {
            return hit;
        }
        String prefix3 = phone.substring(0, 3);
        String carrier = carrierByPrefix3(prefix3);
        if (carrier != null) {
            return new PhoneLocationInfo(phone, "", "", carrier, "", "");
        }
        return new PhoneLocationInfo(phone, "", "", "", "", "");
    }

    /**
    * 按前 3 位识别运营商
    *
    * @param prefix 前缀
    * @return carrierByPrefix3的结果
     */
    private static String carrierByPrefix3(String prefix) {
        if (prefix.startsWith("13") || prefix.startsWith("15") || prefix.startsWith("18")) {
            return "中国移动";
        }
        if (prefix.startsWith("14") || prefix.startsWith("16") || prefix.startsWith("19")) {
            return "中国联通";
        }
        if (prefix.startsWith("17")) {
            return "中国电信";
        }
        return null;
    }

    /**
    * 文本
    *
    * @param n n
    * @param k k
    * @return 文本的结果
     */
    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null ? "" : v.asText();
    }

    /**
    * 内置常用号码段前缀表（前 4 位 -> 属地）。
    * @return 构建前缀table的结果
     */
    private static Map<String, PhoneLocationInfo> buildPrefixTable() {
        Map<String, PhoneLocationInfo> map = new HashMap<>();
        add(map, "1380", "北京市", "北京市", "中国移动");
        add(map, "1390", "北京市", "北京市", "中国移动");
        add(map, "1880", "北京市", "北京市", "中国移动");
        add(map, "1860", "北京市", "北京市", "中国联通");
        add(map, "1331", "北京市", "北京市", "中国电信");

        add(map, "1381", "上海市", "上海市", "中国移动");
        add(map, "1391", "上海市", "上海市", "中国移动");
        add(map, "1881", "上海市", "上海市", "中国移动");
        add(map, "1862", "上海市", "上海市", "中国联通");
        add(map, "1338", "上海市", "上海市", "中国电信");

        add(map, "1392", "广东省", "广州市", "中国移动");
        add(map, "1882", "广东省", "深圳市", "中国移动");
        add(map, "1866", "广东省", "深圳市", "中国联通");
        add(map, "1333", "广东省", "广州市", "中国电信");
        add(map, "1885", "浙江省", "杭州市", "中国移动");

        add(map, "1385", "江苏省", "南京市", "中国移动");
        add(map, "1865", "江苏省", "南京市", "中国联通");

        add(map, "1398", "四川省", "成都市", "中国移动");
        add(map, "1888", "四川省", "成都市", "中国移动");
        add(map, "1868", "四川省", "成都市", "中国联通");

        add(map, "1387", "湖北省", "武汉市", "中国移动");
        add(map, "1887", "湖北省", "武汉市", "中国移动");

        add(map, "1389", "陕西省", "西安市", "中国移动");
        add(map, "1399", "陕西省", "西安市", "中国移动");
        add(map, "1889", "陕西省", "西安市", "中国移动");

        add(map, "1396", "山东省", "济南市", "中国移动");
        add(map, "1886", "山东省", "济南市", "中国移动");

        add(map, "1388", "福建省", "福州市", "中国移动");
        add(map, "1340", "福建省", "福州市", "中国移动");
        return map;
    }

    /**
    * 添加前缀表词条
    *
    * @param map 映射
    * @param prefix 前缀
    * @param province province
    * @param city city
    * @param carrier carrier
     */
    private static void add(Map<String, PhoneLocationInfo> map, String prefix, String province, String city, String carrier) {
        map.put(prefix, new PhoneLocationInfo("", province, city, carrier, "", ""));
    }
}
