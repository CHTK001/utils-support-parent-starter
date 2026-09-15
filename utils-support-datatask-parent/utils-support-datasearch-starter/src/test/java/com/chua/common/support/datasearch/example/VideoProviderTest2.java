package com.chua.common.support.datasearch.example;

import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.impl.DuanJuWangResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.IKanTvResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.MacCmsResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.MuouResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.WanouResourceProvider;
import com.chua.common.support.datasearch.video.spi.impl.WuJiResourceProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 剩余 provider 单跑诊断（duanjuw/muou/maccms/wanou/wuji/ikantv）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoProviderTest2 {

    private VideoProviderTest2() {
    }

    /**
     * 测试入口。
     *
     * @param args 关键词
     */
    public static void main(String[] args) throws Exception {
        String keyword = args.length > 0 ? args[0] : "流浪地球";
        Map<String, com.chua.common.support.datasearch.video.spi.ResourceProvider> map =
                new LinkedHashMap<>();
        map.put("duanjuw", new DuanJuWangResourceProvider());
        map.put("ikantv", new IKanTvResourceProvider());
        map.put("maccms", new MacCmsResourceProvider());
        map.put("muou", new MuouResourceProvider());
        map.put("wanou", new WanouResourceProvider());
        map.put("wuji", new WuJiResourceProvider());

        var writer = new OutputStreamWriter(new FileOutputStream(new File("D:\\ch\\project\\test-video-report2.txt")), StandardCharsets.UTF_8);
        for (var e : map.entrySet()) {
            String name = e.getKey();
            long t0 = System.currentTimeMillis();
            com.chua.common.support.datasearch.network.lang.code.ReturnPageResult<
                    com.chua.common.support.datasearch.video.model.VideoInfoResult> r;
            try {
                VideoSearch qs = new VideoSearch(keyword);
                qs.setPage(1);
                qs.setPageSize(10);
                r = e.getValue().searchResource(qs);
            } catch (Throwable t) {
                writer.write("[ERROR] " + name + " EXC " + t + "\n");
                writer.flush();
                continue;
            }
            long cost = System.currentTimeMillis() - t0;
            if (r.isSuccess()) {
                int n = r.getData() == null || r.getData().getData() == null ? 0 : r.getData().getData().size();
                writer.write("[OK] " + name + " " + cost + "ms items=" + n + "\n");
            } else {
                writer.write("[ERROR] " + name + " " + cost + "ms " + r.getMessage() + "\n");
            }
            writer.flush();
        }
        writer.close();
        System.out.println("done");
    }
}
