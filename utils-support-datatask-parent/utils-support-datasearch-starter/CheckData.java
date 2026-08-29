import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.spi.ServiceProvider;
import java.util.*;

public class CheckData {
    public static void main(String[] a) throws Exception {
        ServiceProvider<ResourceProvider> sp = ServiceProvider.of(ResourceProvider.class);
        String[] names = {"BILIBILI", "WUJI", "CZZY", "GAOQING888", "DOUBAN", "WANOU", "HDMOLI"};
        for (String n : names) {
            ResourceProvider p = sp.getExtension(n);
            if (p == null) { System.out.println(n+": null"); continue; }
            var r = p.searchResource(new VideoSearch("流浪地球"));
            int size = r != null && r.getData() != null && r.getData().getData() != null ? r.getData().getData().size() : 0;
            System.out.println("\n=== " + n + " (rows=" + size + ") ===");
            if (size > 0) {
                List<VideoInfoResult> list = r.getData().getData();
                for (int i = 0; i < Math.min(3, list.size()); i++) {
                    VideoInfoResult v = list.get(i);
                    System.out.println("  ["+i+"] title="+v.getVideoName()+
                        " platform="+v.getVideoPlatform()+
                        " desc="+(v.getVideoDescription()!=null?v.getVideoDescription().substring(0,Math.min(60,v.getVideoDescription().length())):"null"));
                }
            } else {
                System.out.println("  msg="+r.getMessage());
            }
        }
    }
}
