package com.chua.common.support.datasearch.video;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.impl.Douban2ResourceProvider;

import java.util.List;

/**
 * douban2 资源提供者联调测试（真实抓取 douban.com）。
 *
 * <p>回归点：豆瓣综合搜索（{@code /search?query=}）是 JS 渲染页，整页正则会把
 * “登录/注册”等导航链接当成搜索结果返回 10 条假数据。修复后必须：</p>
 * <ol>
 *   <li>走影视分类 {@code cat=1002} 并解析 {@code div.result}；</li>
 *   <li>只接受指向豆瓣详情页的链接，禁止出现“登录/注册”；</li>
 *   <li>标题、详情链接、平台标记齐全。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Douban2ResourceProviderLiveTest {

    /**
     * 通过数
     */
    private static int pass = 0;

    /**
     * 失败数
     */
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        Douban2ResourceProvider provider = new Douban2ResourceProvider();

        ReturnPageResult<VideoInfoResult> response = provider.searchResource(new VideoSearch("流浪地球"));
        check("关键词[流浪地球]检索成功", response.isSuccess());
        List<VideoInfoResult> list = response.getData() == null || response.getData().getData() == null
                ? List.of() : response.getData().getData();
        check("有结果", !list.isEmpty());
        if (list.isEmpty()) {
            summary();
            return;
        }

        list.stream().limit(3).forEach(it -> System.out.println("        " + it.getVideoName()
                + " | " + it.getVideoUrl()));
        check("标题均非空", list.stream().allMatch(it -> !it.getVideoName().isBlank()));
        check("不包含导航假数据（登录/注册）",
                list.stream().noneMatch(it -> it.getVideoName().contains("登录")
                        || it.getVideoName().contains("注册")));
        check("链接均指向豆瓣", list.stream().allMatch(it -> it.getVideoUrl() != null
                && it.getVideoUrl().contains("douban.com/")));
        check("平台标记为 douban2",
                list.stream().allMatch(it -> "douban2".equals(it.getVideoPlatform())));
        check("空关键词快速失败", !provider.searchResource(new VideoSearch("")).isSuccess());

        summary();
    }

    /**
     * 输出汇总并在失败时退出非零。
     */
    private static void summary() {
        System.out.println("[Douban2ResourceProviderLiveTest] pass=" + pass + " fail=" + fail);
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
