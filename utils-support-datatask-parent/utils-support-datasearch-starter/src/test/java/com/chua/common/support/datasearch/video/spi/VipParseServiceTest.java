package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.video.model.VipParseRequest;
import com.chua.common.support.datasearch.video.model.VipParseResult;
import com.chua.common.support.datasearch.video.spi.impl.DirectVipParser;

/**
 * VIP 解析服务集成测试。
 *
 * <p>验证 {@link VipParseService} 按来源路由到正确的 {@link VipParser}，
 * 以及 {@link DirectVipParser} 对直链/非直链格式的解析结果。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class VipParseServiceTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     */
    public static void main(String[] args) {
        VipParseService svc = new VipParseService();

        // 1. direct source + 直链（.mp4）
        VipParseResult r1 = svc.parse("direct", "https://cdn.example.com/video/sample.mp4");
        check(r1.isSuccess(), "direct 直链解析应成功");
        check(r1.getPlayAddresses() != null && !r1.getPlayAddresses().isEmpty(),
                "direct 直链应返回播放地址列表");
        check("直链".equals(r1.getPlayAddresses().get(0).getVideoPlayAddressName()),
                "播放地址名称应为 直链");

        // 2. direct source + m3u8
        VipParseResult r2 = svc.parse("direct", "https://hls.example.com/live/index.m3u8");
        check(r2.isSuccess(), "direct m3u8 解析应成功");

        // 3. direct source + 非直链 URL（.html 页面）
        VipParseResult r3 = svc.parse("direct", "https://www.bilibili.com/video/BV1xxxx");
        check(!r3.isSuccess(), "非直链 URL 解析应失败");
        check(r3.getErrorMessage() != null && r3.getErrorMessage().contains("非直链"),
                "错误信息应说明非直链格式");

        // 4. 未知来源 → 无匹配解析器
        VipParseResult r4 = svc.parse("unknown-source-xyz", "https://cdn.example.com/a.mp4");
        check(!r4.isSuccess(), "未知来源应解析失败");
        check(r4.getErrorMessage() != null && r4.getErrorMessage().contains("无匹配来源"),
                "错误信息应说明无匹配来源");

        // 5. direct 空 URL
        VipParseResult r5 = svc.parse("direct", "   ");
        check(!r5.isSuccess(), "空 URL 解析应失败");

        // 6. supports 语义验证
        DirectVipParser directParser = new DirectVipParser();
        check(!directParser.supports("json"), "DirectVipParser 不支持 json source");
        check(directParser.name().equals("direct"), "DirectVipParser name 应为 direct");

        // 7. VipParseRequest 模型 setter/getter
        VipParseRequest req = new VipParseRequest("https://a.com/b.mp4", "direct");
        req.setForceRefresh(true);
        req.setTimeoutMs(30000);
        check("https://a.com/b.mp4".equals(req.getUrl()), "VipParseRequest getUrl");
        check("direct".equals(req.getSource()), "VipParseRequest getSource");
        check(req.isForceRefresh(), "VipParseRequest isForceRefresh");
        check(req.getTimeoutMs() == 30000, "VipParseRequest getTimeoutMs");

        System.out.println("VipParseServiceTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 校验。
     *
     * @param cond cond（布尔开关）
     * @param msg 消息，不允许为 null
     */
    private static void check(boolean cond, String msg) {
        if (cond) {
            pass++;
            System.out.println("  ok - " + msg);
        } else {
            fail++;
            System.out.println("  FAIL - " + msg);
        }
    }
}
