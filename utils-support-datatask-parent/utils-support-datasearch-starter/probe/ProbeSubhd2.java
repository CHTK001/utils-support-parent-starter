package probe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProbeSubhd2 {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        // 关键词：盗梦空间（有大量字幕）
        String url = "https://subhd.tv/search/" + java.net.URLEncoder.encode("盗梦空间", java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://subhd.tv/")
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String html = resp.body();
        java.nio.file.Files.write(java.nio.file.Path.of("probe-subhd2.txt"), html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("status=" + resp.statusCode() + " len=" + html.length());

        // 找"搜索结果"标题之后的区域
        int idx = html.indexOf("的搜索结果");
        System.out.println("search-results-idx=" + idx);
        String region = idx >= 0 ? html.substring(idx, Math.min(html.length(), idx + 15000)) : html;
        Pattern p = Pattern.compile("<a[^>]*href=\"(/d/[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Matcher m = p.matcher(region);
        int n = 0;
        while (m.find() && n < 8) {
            String inner = m.group(2).replaceAll("<[^>]+>", " ").trim().replaceAll("\\s+", " ");
            System.out.println("  " + m.group(1) + " || " + inner.substring(0, Math.min(60, inner.length())));
            n++;
        }
        System.out.println("region matches=" + n);
    }
}
