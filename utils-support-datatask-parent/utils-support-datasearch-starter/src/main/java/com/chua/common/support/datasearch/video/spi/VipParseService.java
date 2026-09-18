package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Collections;
import java.util.Map;

/**
 * VIP 解析服务。
 *
 * <p>统一入口，通过 SPI 机制按来源查找合适的 {@link VipParser} 实现，
 * 调用 {@link VipParser#parse} 完成 VIP 解析。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class VipParseService {

    /**
    * 解析视频播放地址。
    *
    * @param source 视频来源编码
    * @param url    视频播放页 URL
    * @return 解析结果
    */
    public com.chua.common.support.datasearch.video.model.VipParseResult parse(String source, String url) {
        Map<String, VipParser> parsers = ServiceProvider.of(VipParser.class).list();
        if (parsers == null || parsers.isEmpty()) {
            return com.chua.common.support.datasearch.video.model.VipParseResult.error("无可用 VIP 解析器");
        }
        VipParser parser = parsers.get(source);
        if (parser == null) {
            // 尝试模糊匹配
            for (Map.Entry<String, VipParser> entry : parsers.entrySet()) {
                if (entry.getValue().supports(source)) {
                    parser = entry.getValue();
                    break;
                }
            }
        }
        if (parser == null) {
            return com.chua.common.support.datasearch.video.model.VipParseResult.error("无匹配来源: " + source);
        }
        com.chua.common.support.datasearch.video.model.VipParseRequest request =
                new com.chua.common.support.datasearch.video.model.VipParseRequest(url, source);
        return parser.parse(request);
    }
}
