package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import java.util.regex.Pattern;

@Spi("duoduo")
public class DuoduoResourceProvider extends HtmlScraperResourceProvider {
    public DuoduoResourceProvider() { super(); }
    @Override protected String searchUrl(String kw) {
        return "https://www.duoduo.cc/search?keyword=" + kw;
    }
    @Override protected Pattern resultPattern() {
        return Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>\\s*([^<]{2,})</a>.*?([\\d.]+\\s*[A-Z]{2})", Pattern.DOTALL);
    }
}
