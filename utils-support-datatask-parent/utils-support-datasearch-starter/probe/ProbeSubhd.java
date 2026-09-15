package probe;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 探测 subhd.tv HTML 搜索页的结构，确认 fallback 正则能否命中。
 */
public class ProbeSubhd {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://subhd.tv/search/%E6%B5%81%E6%B6%AA%E5%9C%B0%E7%90%83"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Referer", "https://subhd.tv/")
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String html = resp.body();
        Path p = Path.of("probe-subhd-tv.txt");
        Files.write(p, html.getBytes(StandardCharsets.UTF_8));
        System.out.println("status=" + resp.statusCode() + " len=" + html.length());

        // 旧的、过于严格的正则
        Pattern oldPat = Pattern.compile(
                "<a[^>]*href=\"(/d/[^\"]+)\"[^>]*>\\s*<div[^>]*class=\"[^\"]*title[^\"]*\"[^>]*>([^<]+)</div>",
                Pattern.DOTALL);
        Matcher m1 = oldPat.matcher(html);
        int c1 = 0;
        while (m1.find()) c1++;
        System.out.println("old regex hits: " + c1);

        // 新的、更宽泛的正则
        Pattern newPat = Pattern.compile("<a[^>]*href=\"(/d/[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Matcher m2 = newPat.matcher(html);
        int c2 = 0;
        while (m2.find()) c2++;
        System.out.println("new regex hits: " + c2);
        for (int i = 0; i < 5 && m2.find(); i++) {
            String inner = m2.group(2).replaceAll("<[^>]+>", " ").trim();
            System.out.println("  " + m2.group(1) + " || " + inner);
        }
    }
}
