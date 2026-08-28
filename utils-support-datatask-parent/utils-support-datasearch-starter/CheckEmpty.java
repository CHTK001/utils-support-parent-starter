import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.spi.ServiceProvider;
public class CheckEmpty {
    public static void main(String[] a) throws Exception {
        ServiceProvider<ResourceProvider> sp = ServiceProvider.of(ResourceProvider.class);
        String[] names = {"HUNHEPAN","DIEDIAO","BTSOU","AHHHHFS","HAOQU","CILIZHU","CILIGOU"};
        for (String n : names) {
            ResourceProvider p = sp.getExtension(n);
            if (p == null) { System.out.println(n+": null"); continue; }
            var r = p.searchResource(new VideoSearch("电影"));
            int size = r != null && r.getData() != null && r.getData().getData() != null ? r.getData().getData().size() : 0;
            System.out.println(n+":"+size);
        }
    }
}
