package com.chua.common.support.datasearch.region.spi.impl;

import com.chua.common.support.datasearch.region.model.RegionInfo;

import java.util.List;

/**
 * 行政区划父级编码联调测试（访问 geo.datav.aliyun.com 真实接口）。
 *
 * <p>回归点：{@code AlibabaRegionProvider.parse} 曾把 {@code RegionInfo.parent} 硬写成 {@code null}，
 * 而 DataV 每个 feature 的 {@code properties.parent.adcode} 都带有父级编码。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class AlibabaRegionParentTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        AlibabaRegionProvider provider = new AlibabaRegionProvider();

        List<RegionInfo> kids = provider.getChildren("310000");
        check("上海市下辖区可拉取", !kids.isEmpty());
        long withParent = kids.stream().filter(r -> "310000".equals(r.getParent())).count();
        check("每个子级 parent 均为 310000（修复前恒为 null/空）", !kids.isEmpty() && withParent == kids.size());
        kids.stream().filter(r -> !"310000".equals(r.getParent())).findFirst()
                .ifPresent(r -> System.out.println("      例外: " + r.getAdcode() + " parent=" + r.getParent()));

        List<RegionInfo> provinces = provider.getChildren("100000");
        System.out.println("      provinces=" + provinces.size()
                + " distinctParent=" + provinces.stream().map(RegionInfo::getParent).distinct().toList()
                + " distinctLevel=" + provinces.stream().map(RegionInfo::getLevelName).distinct().toList());
        check("省级 parent 均为 100000", !provinces.isEmpty()
                && provinces.stream().allMatch(r -> "100000".equals(r.getParent())));
        check("无 properties 为空的垃圾区划条目", provinces.stream()
                .allMatch(r -> !r.getAdcode().isEmpty() && !r.getName().isEmpty()));

        RegionInfo root = provider.getTree(1);
        check("根节点 parent 保持 null", root != null && root.getParent() == null);

        System.out.println("[AlibabaRegionParentTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

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
