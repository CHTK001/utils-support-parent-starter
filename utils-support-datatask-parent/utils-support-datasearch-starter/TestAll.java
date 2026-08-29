import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.spi.ServiceProvider;
import java.util.List;

public class TestAll {
    public static void main(String[] a) throws Exception {
        ServiceProvider<ResourceProvider> sp = ServiceProvider.of(ResourceProvider.class);
        String[] names = {"MUOU", "XUEXIZHINAN", "GAOQING888"};
        for (String n : names) {
            ResourceProvider p = sp.getExtension(n);
            if (p == null) { System.out.println(n + ": NOT FOUND"); continue; }
            try {
                var r = p.searchResource(new VideoSearch("流浪地球"));
                int size = r != null && r.getData() != null && r.getData().getData() != null ? r.getData().getData().size() : 0;
                String msg = r != null && r.getMessage() != null ? r.getMessage() : "";
                System.out.println(n + " rows=" + size + (msg.isEmpty() ? "" : " msg=" + msg));
                if (size > 0) {
                    List<VideoInfoResult> list = r.getData().getData();
                    for (int i = 0; i < Math.min(2, list.size()); i++) {
                        VideoInfoResult v = list.get(i);
                        String desc = v.getVideoDescription() != null ? v.getVideoDescription().substring(0, Math.min(250, v.getVideoDescription().length())) : "";
                        System.out.println("  [" + i + "] " + v.getVideoName() + " | " + desc);
                    }
                }
            } catch (Exception e) {
                System.out.println(n + ": ERROR " + e.getMessage());
            }
        }
    }
}
