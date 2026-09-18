package com.chua.mock.support.provider;

import com.chua.common.support.mock.MockEnvironment;
import com.chua.common.support.mock.MockString;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.ast.support.annotation.AutoSpi;

import javax.annotation.Nonnull;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
* 图片 Mock 生成器
*
* <p>基于占位图片 API 生成随机图片 URL：
* <ul>
*   <li>未指定关键词时使用 picsum.photos 随机图片，如
*       {@code https://picsum.photos/seed/ab3k9m2x/800/600}，
*       固定 seed 可保证同一链接返回同一张图片（可复现）；</li>
*   <li>指定关键词时使用 loremflickr.com 按主题搜索图片，如
*       {@code https://loremflickr.com/800/600/cat}。</li>
* </ul>
* 返回的是图片服务地址而非图片二进制内容。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"image", "image-url", "img", "img-url", "picture"})
@AutoSpi(value = "com.chua.common.support.mock.MockString")
public class ImageMockString implements MockString {

    /**
    * 常见图片宽度池
    */
    private static final int[] WIDTHS = {640, 750, 800, 1024, 1280, 1920};
    /**
    * 常见图片高度池
    */
    private static final int[] HEIGHTS = {480, 500, 600, 720, 768, 1080};
    /**
    * 最小尺寸（环境指定为有效正方形尺寸时的下限）
    */
    private static final int SIZE_MIN = 10;
    /**
    * 最大尺寸（环境指定为有效正方形尺寸时的上限）
    */
    private static final int SIZE_MAX = 2048;
    /**
    * 种子字符池
    */
    private static final char[] SEED_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    /**
    * 默认种子长度
    * @param environment 环境
    * @return 获取字符串的结果
    */
    private static final int SEED_LENGTH = 8;

    @Override
    @Nonnull
    public String getString(@Nonnull MockEnvironment environment) {
        int width;
        int height;
        int size = environment.length();
        if (size >= SIZE_MIN && size <= SIZE_MAX) {
            width = size;
            height = size;
        } else {
            width = WIDTHS[environment.nextInt(WIDTHS.length)];
            height = HEIGHTS[environment.nextInt(HEIGHTS.length)];
        }
        String keyword = environment.getKeyword();
        if (StringUtils.isNotBlank(keyword)) {
            return keywordUrl(keyword, width, height);
        }
        return randomUrl(environment, width, height);
    }

    /**
    * 生成随机图片 URL（picsum.photos）。
    *
    * @param environment Mock 环境
    * @param width       图片宽度
    * @param height      图片高度
    * @return 图片 URL
    */
    private static String randomUrl(MockEnvironment environment, int width, int height) {
        String seed = randomSeed(environment);
        return "https://picsum.photos/seed/" + seed + "/" + width + "/" + height;
    }

    /**
    * 生成关键词图片 URL（loremflickr.com）。
    *
    * @param keyword 关键词
    * @param width   图片宽度
    * @param height  图片高度
    * @return 图片 URL
    */
    private static String keywordUrl(String keyword, int width, int height) {
        return "https://loremflickr.com/" + width + "/" + height + "/" + encode(keyword);
    }

    /**
    * 对关键词做 URL 编码（UTF-8）。
    *
    * @param keyword 关键词
    * @return 编码后的关键词
    */
    private static String encode(String keyword) {
        try {
            return URLEncoder.encode(keyword, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return keyword;
        }
    }

    /**
    * 生成随机图片种子字符串。
    *
    * @param environment Mock 环境
    * @return 种子字符串
    */
    private static String randomSeed(MockEnvironment environment) {
        StringBuilder builder = new StringBuilder(SEED_LENGTH);
        for (int i = 0; i < SEED_LENGTH; i++) {
            builder.append(SEED_CHARS[environment.nextInt(SEED_CHARS.length)]);
        }
        return builder.toString();
    }
}
