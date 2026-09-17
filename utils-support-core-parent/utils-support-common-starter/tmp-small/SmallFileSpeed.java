import com.chua.common.support.network.filepush.*;
import java.nio.file.*;

public class SmallFileSpeed {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("fp-small");
        Path source = root.resolve("src");
        Files.createDirectories(source);
        byte[] payload = new byte[8 * 1024];
        for (int d = 0; d < 40; d++) {
            Path dir = source.resolve("dir-" + d);
            Files.createDirectories(dir);
            for (int i = 0; i < 100; i++) Files.write(dir.resolve("f-" + i + ".dat"), payload);
        }
        int[] perConns = {1, 16, 64, 256};
        for (int perConn : perConns) {
            Path t = root.resolve("dst-" + perConn);
            FilePushConfig sc = FilePushConfig.defaults();
            sc.setHost("127.0.0.1");
            sc.setPort(0);
            sc.setTargetDir(t);
            try (FilePushServer server = new FilePushServer(sc)) {
                int port = server.start();
                FilePushConfig cc = FilePushConfig.defaults();
                cc.setHost("127.0.0.1");
                cc.setPort(port);
                cc.setSourceDir(source);
                cc.setFilesPerConnection(perConn);
                try (FilePushClient client = new FilePushClient(cc)) {
                    var r = client.push();
                    System.out.printf("perConn=%-3d success=%-6d failed=%d %d ms %.2f MB/s%n",
                            perConn, r.successCount(), r.failedCount(),
                            (long) r.elapsedMs(), r.throughputMbs());
                }
            }
        }
        Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) { }
        });
    }
}
