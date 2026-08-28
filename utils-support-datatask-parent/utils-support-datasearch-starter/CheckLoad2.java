import com.chua.common.support.datasearch.video.spi.VideoProviderRegistry;
public class CheckLoad2 {
    public static void main(String[] a) {
        VideoProviderRegistry.initBlockedResources();
        System.out.println("count: " + VideoProviderRegistry.getBlockedNames().size());
    }
}
