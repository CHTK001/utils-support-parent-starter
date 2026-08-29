import com.chua.common.support.datasearch.music.spi.MusicSourceProvider;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSearchResult;
import com.chua.common.support.datasearch.music.model.MusicPlaylistSummary;
import com.chua.common.support.datasearch.music.model.MusicPlaylistDetail;
import com.chua.common.support.spi.ServiceProvider;

public class TestMusic2 {
    public static void main(String[] a) throws Exception {
        ServiceProvider<MusicSourceProvider> sp = ServiceProvider.of(MusicSourceProvider.class);
        MusicSourceProvider tx = sp.getExtension("TX");
        MusicSourceProvider netease = sp.getExtension("NETEASE");
        for (String kw : new String[]{"周杰伦", "邓紫棋", "陈奕迅", "林俊杰", "薛之谦", "Taylor Swift", "Adele", "朴树"}) {
            System.out.println("\n=== 关键词: " + kw + " ===");
            try {
                MusicPlaylistSearchResult r = tx.searchPlaylists(kw, 1, 10);
                int size = r != null && r.getPlaylists() != null ? r.getPlaylists().size() : 0;
                System.out.println("  TX (QQ音乐) playlists: " + size);
                if (size > 0) {
                    MusicPlaylistSummary pl = r.getPlaylists().get(0);
                    System.out.println("    sample: " + pl.getTitle() + " (id=" + pl.getPlaylistId() + ", tracks=" + pl.getTrackCount() + ", author=" + pl.getAuthor() + ")");
                }
            } catch (Exception e) {
                System.out.println("  TX ERROR: " + e.getMessage().substring(0, Math.min(60, e.getMessage().length())));
            }
            try {
                MusicPlaylistSearchResult r = netease.searchPlaylists(kw, 1, 10);
                int size = r != null && r.getPlaylists() != null ? r.getPlaylists().size() : 0;
                System.out.println("  NETEASE (网易云) playlists: " + size);
                if (size > 0) {
                    MusicPlaylistSummary pl = r.getPlaylists().get(0);
                    System.out.println("    sample: " + pl.getTitle() + " (tracks=" + pl.getTrackCount() + ")");
                }
            } catch (Exception e) {
                System.out.println("  NETEASE ERROR: " + e.getMessage().substring(0, Math.min(60, e.getMessage().length())));
            }
        }
    }
}
