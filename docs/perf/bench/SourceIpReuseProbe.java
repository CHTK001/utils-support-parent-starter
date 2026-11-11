import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** 验证：不同源 IP 复用同一源端口、连同一目标端口，能否同时持有。 */
public class SourceIpReuseProbe {
    public static void main(String[] args) throws Exception {
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        int target = listener.getLocalPort();
        Thread acceptor = new Thread(() -> {
            try {
                while (!listener.isClosed()) {
                    try (Socket s = listener.accept()) {
                        Thread.sleep(2000);
                    }
                }
            } catch (Exception ignored) {
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        int sourcePort = 45000;
        List<Socket> held = new ArrayList<>();
        for (int octet = 1; octet <= 5; octet++) {
            String srcIp = "127.0.0." + octet;
            try {
                Socket c = new Socket();
                c.bind(new InetSocketAddress(srcIp, sourcePort));
                c.connect(new InetSocketAddress("127.0.0.1", target), 3000);
                held.add(c);
                System.out.println("  HELD OK " + srcIp + ":" + sourcePort + " -> " + target
                        + " remote=" + c.getRemoteSocketAddress());
            } catch (Exception e) {
                System.out.println("  HELD FAIL " + srcIp + ":" + sourcePort + " "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        System.out.println("held=" + held.size() + "/5");
        for (Socket s : held) {
            s.close();
        }
        listener.close();
    }
}