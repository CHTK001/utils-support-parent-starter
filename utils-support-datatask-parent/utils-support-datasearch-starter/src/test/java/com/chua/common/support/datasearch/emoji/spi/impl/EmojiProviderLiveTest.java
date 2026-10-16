package com.chua.common.support.datasearch.emoji.spi.impl;

import com.chua.common.support.datasearch.emoji.model.EmojiInfo;
import com.chua.common.support.datasearch.emoji.spi.EmojiProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表情包提供器联调测试（真实抓取「斗图啦」与 Bing 图片检索）。
 *
 * <p>回归点：</p>
 * <ol>
 *   <li>垂直站点「斗图啦」搜索接口忽略页码，需在内存中按页切片，页码语义才对调用方有效；</li>
 *   <li>结果卡片混有广告占位锚点（无 {@code data-original}），必须跳过，否则会产出空图链接；</li>
 *   <li>不同数据源覆盖面不同，SPI 聚合需按原图地址去重，且空关键词必须短路为空列表。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EmojiProviderLiveTest {

    /**
     * 通过数
     */
    private static int pass = 0;

    /**
     * 失败数
     */
    private static int fail = 0;

    /**
     * 联调关键词
     */
    private static final String[] KEYWORDS = {"抖音", "短句", "狗子", "丧彪"};

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        Map<String, EmojiProvider> providers = ServiceProvider.of(EmojiProvider.class).list();
        Map<String, EmojiProvider> byName = new LinkedHashMap<>();
        for (EmojiProvider provider : providers.values()) {
            byName.put(provider.name(), provider);
        }
        System.out.println("[EmojiProviderLiveTest] 已注册数据源: " + byName.keySet());
        check("SPI 注册了两个数据源", byName.size() >= 2);
        check("包含垂直站点 doutula", byName.containsKey("doutula"));
        check("包含通用检索 bing", byName.containsKey("bing"));

        EmojiProvider doutula = byName.get("doutula");
        EmojiProvider bing = byName.get("bing");
        check("doutula 与 bing 均已实例化", doutula != null && bing != null);
        if (doutula == null || bing == null) {
            summary();
            return;
        }

        for (String keyword : KEYWORDS) {
            List<EmojiInfo> vertical = doutula.search(keyword, 1);
            List<EmojiInfo> general = bing.search(keyword, 1);
            System.out.println("      [" + keyword + "] doutula=" + vertical.size()
                    + " bing=" + general.size());
            print("doutula", vertical);
            print("bing", general);

            check("[" + keyword + "] 垂直站点有结果", !vertical.isEmpty());
            check("[" + keyword + "] 通用检索有结果", !general.isEmpty());
            check("[" + keyword + "] 垂直站点原图为 http 地址",
                    vertical.stream().allMatch(it -> it.getImageUrl() != null
                            && it.getImageUrl().startsWith("http")));
            check("[" + keyword + "] 垂直站点命中时字段完整",
                    vertical.stream().allMatch(it -> notBlank(it.getImageUrl()) && !it.getId().isBlank()
                            && it.getDetailUrl().contains("/photo/")));
            check("[" + keyword + "] 垂直站点标题无乱码",
                    vertical.stream().noneMatch(it -> it.getTitle().contains("\uFFFD")));
            check("[" + keyword + "] 通用检索 source 标记正确",
                    general.stream().allMatch(it -> "bing".equals(it.getSource())));
        }

        List<EmojiInfo> firstPage = doutula.search("狗子", 1);
        List<EmojiInfo> secondPage = doutula.search("狗子", 2);
        System.out.println("      [狗子] 分页 doutula p1=" + firstPage.size() + " p2=" + secondPage.size());
        check("doutula 内存分页第二页非空", !secondPage.isEmpty());
        check("doutula 分页无重复", secondPage.stream()
                .noneMatch(it -> firstPage.stream().anyMatch(f -> f.getImageUrl().equals(it.getImageUrl()))));

        check("空关键词短路为空列表", doutula.search("", 1).isEmpty() && bing.search("", 1).isEmpty());

        summary();
    }

    /**
     * 打印样本结果。
     *
     * @param source 数据源
     * @param list   结果列表
     */
    private static void print(String source, List<EmojiInfo> list) {
        list.stream().limit(3).forEach(it ->
                System.out.println("        " + source + " | " + it.getTitle() + " | " + it.getImageUrl()));
    }

    /**
     * 判断字符串非空。
     *
     * @param value 字符串
     * @return 非空返回 true
     */
    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 输出汇总并在失败时退出非零。
     */
    private static void summary() {
        System.out.println("[EmojiProviderLiveTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 断言。
     *
     * @param name 用例名
     * @param ok   是否通过
     */
    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
