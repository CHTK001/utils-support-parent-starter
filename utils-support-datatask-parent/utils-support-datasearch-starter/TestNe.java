import com.chua.common.support.datasearch.music.spi.MusicSourceProvider;
import com.chua.common.support.spi.ServiceProvider;
public class TestNe {
    public static void main(String[] a) throws Exception {
        ServiceProvider<MusicSourceProvider> sp = ServiceProvider.of(MusicSourceProvider.class);
        for (String n : sp.getExtensions()) System.out.println("ext: " + n);
        try {
            MusicSourceProvider p = sp.getExtension("NETEASE");
            if (p == null) { System.out.println("NETEASE not found"); return; }
            var r = p.searchPlaylists("周杰伦", 1, 10);
            System.out.println("size: " + (r != null && r.getPlaylists() != null ? r.getPlaylists().size() : -1));
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            System.out.println(sw.toString().substring(0, 500));
        }
    }
}
