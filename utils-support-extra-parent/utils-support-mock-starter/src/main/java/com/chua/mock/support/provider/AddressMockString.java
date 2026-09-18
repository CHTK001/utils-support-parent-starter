package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

/**
* 地址 Mock 生成器
*
* <p>由省份、城市、街道、门牌号组合生成中国地址字符串，
* 如「广东省广州市天河区人民路 128 号」。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("address")
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class AddressMockString implements MockString {

    /**
    * 省份与下辖城市映射（保证生成的省市组合真实匹配）
    */
    private static final Map<String, List<String>> PROVINCE_CITIES = Map.ofEntries(
            Map.entry("北京市", List.of("东城区", "西城区", "朝阳区", "海淀区", "丰台区", "通州区")),
            Map.entry("上海市", List.of("黄浦区", "徐汇区", "静安区", "浦东新区", "杨浦区", "闵行区")),
            Map.entry("广东省", List.of("广州市", "深圳市", "东莞市", "佛山市", "珠海市", "中山市")),
            Map.entry("浙江省", List.of("杭州市", "宁波市", "温州市", "嘉兴市", "绍兴市", "金华市")),
            Map.entry("江苏省", List.of("南京市", "苏州市", "无锡市", "常州市", "南通市", "徐州市")),
            Map.entry("四川省", List.of("成都市", "绵阳市", "德阳市", "乐山市", "宜宾市", "南充市")),
            Map.entry("湖北省", List.of("武汉市", "宜昌市", "襄阳市", "荆州市", "黄石市", "十堰市")),
            Map.entry("湖南省", List.of("长沙市", "株洲市", "湘潭市", "衡阳市", "岳阳市", "常德市")),
            Map.entry("福建省", List.of("福州市", "厦门市", "泉州市", "漳州市", "莆田市", "宁德市")),
            Map.entry("山东省", List.of("济南市", "青岛市", "烟台市", "潍坊市", "淄博市", "济宁市")),
            Map.entry("陕西省", List.of("西安市", "宝鸡市", "咸阳市", "渭南市", "汉中市", "延安市")),
            Map.entry("河南省", List.of("郑州市", "洛阳市", "开封市", "南阳市", "信阳市", "新乡市"))
    );
    /**
    * 街道名池
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final String[] STREETS = {
            "人民路", "中山路", "解放路", "建设路", "和平路", "新华路", "青年路",
            "朝阳路", "长江路", "黄河路", "文化路", "体育路", "东湖路", "西湖路",
            "南京路", "北京路", "上海路", "高新路", "文华路", "学府路"
    };

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        String[] provinces = PROVINCE_CITIES.keySet().toArray(new String[0]);
        String province = environment.randomOf(provinces);
        String district = environment.randomOf(PROVINCE_CITIES.get(province).toArray(new String[0]));
        String street = environment.randomOf(STREETS);
        int door = environment.nextInt(1, 200);
        return province + district + street + door + "号";
    }
}
