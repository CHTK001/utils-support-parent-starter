import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class QuickMock {
    public static void main(String[] a) throws Exception {
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ss.getLocalPort();
        System.out.println("PORT " + port);
        Thread t = new Thread(() -> {
            try {
                ServerSocket s2 = new ServerSocket(port);
                Socket sock = s2.accept();
                System.out.println("accepted");
                BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
                StringBuilder req = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null && !line.isEmpty()) req.append(line).append("\n");
                System.out.println("REQ: " + req);
                PrintWriter w = new PrintWriter(sock.getOutputStream(), true);
                w.print("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 5\r\n\r\n{\"x\":1}");
                w.flush();
                sock.close();
                ss.close();
                System.out.println("done writing");
            } catch (Exception e) { e.printStackTrace(); }
        });
        t.start();
        Thread.sleep(300);
        HttpClient hc = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpResponse<String> resp = hc.send(
            HttpRequest.newBuilder().uri(new URI("http://127.0.0.1:" + port + "/v1/apps?pageNo=1&pageItems=1"))
                .timeout(Duration.ofSeconds(2)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        System.out.println("CODE: " + resp.statusCode());
        System.out.println("BODY: " + resp.body());
    }
}
