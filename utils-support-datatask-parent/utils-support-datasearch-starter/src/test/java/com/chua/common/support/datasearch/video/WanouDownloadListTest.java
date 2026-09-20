package com.chua.common.support.datasearch.video;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoDownload;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.impl.WanouResourceProvider;
import com.chua.common.support.lang.code.PageResult;

import java.util.List;

/**
 * Wanou 下载列表联调测试（访问 MacCMS 采集接口真实数据）。
 *
 * <p>回归点：{@code WanouResourceProvider} 曾用 {@code registerDownload} 把
 * {@code vod_down_from}/{@code vod_down_url}（{@code $$$} 分隔）解析成
 * {@code List<VideoDownload>} 后直接 {@code return result}，从未调用
 * {@code result.setDownloadList(...)}，导致整份下载列表被丢弃。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class WanouDownloadListTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        ReturnPageResult<VideoInfoResult> page =
                new WanouResourceProvider().searchResource(new VideoSearch("繁花"));
        PageResult<VideoInfoResult> pr = page.getData();
        List<VideoInfoResult> list = (pr == null || pr.getData() == null) ? List.of() : pr.getData();
        check("接口可返回条目", !list.isEmpty());
        if (list.isEmpty()) {
            System.out.println("[WanouDownloadListTest] pass=" + pass + " fail=" + fail);
            return;
        }

        long withDownloads = list.stream()
                .filter(r -> r.getDownloadList() != null && !r.getDownloadList().isEmpty())
                .count();
        check("downloadList 已挂到实体（修复前恒为 null）", withDownloads > 0);
        System.out.println("      items=" + list.size() + " withDownloads=" + withDownloads);
        long maxDownloads = list.stream()
                .filter(r -> r.getDownloadList() != null)
                .mapToLong(r -> r.getDownloadList().size())
                .max().orElse(0);
        check("多平台条目被拆成多条下载项", maxDownloads >= 2);
        System.out.println("      maxDownloadsPerItem=" + maxDownloads);

        list.stream()
                .filter(r -> r.getDownloadList() != null && !r.getDownloadList().isEmpty())
                .findFirst()
                .ifPresent(r -> {
                    List<?> downloads = r.getDownloadList();
                    VideoDownload first = (VideoDownload) downloads.get(0);
                    System.out.println("      " + r.getVideoName() + " downloads=" + downloads.size()
                            + " firstName=" + first.getVideoDownloadName()
                            + " firstUrl=" + first.getVideoDownloadUrl());
                    check("每条下载项都带名称与地址", downloads.stream().allMatch(d ->
                            d instanceof VideoDownload v
                                    && v.getVideoDownloadName() != null && !v.getVideoDownloadName().isEmpty()
                                    && v.getVideoDownloadUrl() != null && !v.getVideoDownloadUrl().isEmpty()));
                    check("$$$ 分隔符已拆开（名称与单条地址）", downloads.stream().allMatch(d ->
                            d instanceof VideoDownload v
                                    && !v.getVideoDownloadName().contains("$$$")
                                    && !v.getVideoDownloadUrl().contains("$$$")));
                });

        System.out.println("[WanouDownloadListTest] pass=" + pass + " fail=" + fail);
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
