import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.spi.ServiceProvider;

public class CheckData {
    public static void main(String[] a) throws Exception {
        ServiceProvider<ResourceProvider> sp = ServiceProvider.of(ResourceProvider.class);
        String[] names = {"DDYS","AIKANZY","PANSOU2","SOUSOU","KKS","AIPANSO","BTSOU","CILIZHU","HUNHEPAN","CZZY","GAOQING888","HDMOLI","PAN666","QUARK3","QUPANSOU","YUNSO","AHHHHFS","PANSOU"};
        for (String n : names) {
            try {
                ResourceProvider p = sp.getExtension(n);
                if (p == null) { System.out.println(n+": null"); continue; }
                var r = p.searchResource(new VideoSearch("流浪地球"));
                int size = r != null && r.getData() != null && r.getData().getData() != null ? r.getData().getData().size() : 0;
                String msg = r != null && r.getMessage() != null ? r.getMessage().substring(0, Math.min(60, r.getMessage().length())) : "";
                String sample = "";
                if (size > 0) {
                    VideoInfoResult v = r.getData().getData().get(0);
                    sample = v.getVideoName() == null ? "" : v.getVideoName().substring(0, Math.min(40, v.getVideoName().length()));
                }
                System.out.printf("%-12s rows=%-3d msg=%s | sample=%s%n", n, size, msg, sample);
            } catch (Exception e) {
                System.out.println(n+": ERROR "+e.getMessage());
            }
        }
    }
}
