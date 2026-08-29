package com.chua.common.support.datasearch.music;

import com.chua.common.support.datasearch.music.model.MusicSearchResult;
import com.chua.common.support.datasearch.music.model.MusicSourceOption;
import com.chua.common.support.datasearch.music.spi.MusicSourceProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;

/**
 * 音乐检索真实数据验证:网易云音乐源搜索。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MusicSearchVerifyTest {

    /**
     * 运行验证。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        Map<String, MusicSourceProvider> all = ServiceProvider.of(MusicSourceProvider.class).list();
        System.out.println("音乐源数量: " + (all == null ? 0 : all.size()));
        if (all != null) {
            System.out.println("SPI key: " + all.keySet());
        }
        MusicSourceProvider provider = null;
        if (all != null) {
            for (Map.Entry<String, MusicSourceProvider> e : all.entrySet()) {
                if ("netease".equalsIgnoreCase(e.getKey())) {
                    provider = e.getValue();
                    break;
                }
            }
        }
        System.out.println("SPI 发现 netease: " + (provider != null));
        if (provider == null) {
            return;
        }
        MusicSourceOption source = provider.getSource();
        System.out.println("数据源: " + source.getCode() + " - " + source.getName());
        MusicSearchResult result = provider.search("周杰伦", 1, 5);
        if (result == null) {
            System.out.println("搜索返回 null");
            return;
        }
        System.out.println("搜索 周杰伦: total=" + result.getTotal() + " 返回=" + result.getTracks().size());
        result.getTracks().stream().limit(5).forEach(t ->
                System.out.printf("  %s - %s《%s》%d秒 cover=%s%n",
                        t.getTrackId(), t.getArtist(), t.getTitle(),
                        t.getDurationSeconds() == null ? 0 : t.getDurationSeconds(),
                        t.getCoverUrl() == null ? "" : t.getCoverUrl().substring(0, Math.min(40, t.getCoverUrl().length()))));
    }
}
