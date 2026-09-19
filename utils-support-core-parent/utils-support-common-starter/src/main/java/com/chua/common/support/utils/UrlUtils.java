package com.chua.common.support.utils;

import com.chua.common.support.lang.algorithm.crypto.Hex;
import com.google.common.base.Joiner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.BitSet;
import java.util.Objects;

import static com.chua.common.support.constant.CharConstant.*;
import static com.chua.common.support.constant.CharsetConstant.UTF_8;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_EMPTY;

/**
 * URL 工具类，提供 URL 与 URI 之间的相互转换、application/x-www-form-urlencoded 格式的编解码、
 * 路径规范化（去除多余斜杠与反斜杠、拆分协议/域名/路径/参数）、以及编码后的路径提取等一站式操作。
 *
 * <p>核心能力包括：
 * <ul>
 *   <li><b>URL 规范化</b> —— 将 URL 拆分为协议头（如 {@code http://}）、域名、路径和查询参数四个部分，
 *       分别去除路径部分前后多余的斜杠和反斜杠，支持可选的对路径进行百分号编码</li>
 *   <li><b>编解码</b> —— 基于自定义 {@link SimpleUrlEncoder} 实现符合 RFC 3986 的百分号编码（{@code %XX} 十六进制格式），
 *       解码基于 JDK 的 {@link URLDecoder}，支持指定字符集</li>
 *   <li><b>URI 转换</b> —— 将 {@link URL} 转为 {@link URI}，捕获 {@link URISyntaxException} 并优雅降级</li>
 *   <li><b>路径提取</b> —— 优先通过 {@code URL → URI → getPath()} 获取解码后的路径，
 *       当 URI 路径为空时回退到 {@link URL#getPath()}</li>
 * </ul>
 *
 * <p>内部类 {@link SimpleUrlEncoder} 实现了 URI 规范定义的字符安全集（unreserved + sub-delims + ":" + "@"），
 * 在此基础上额外保留 {@code /} 以保证路径分隔符不被编码。
 *
 * @author CH
 * @see SimpleUrlEncoder
 * @see URLDecoder
 * @since 4.0.0
 */
public class UrlUtils {
    /** 创建 url工具 实例 */
    private UrlUtils() {
    }

    /**
    * 将多个 URL 路径片段用 {@code /} 拼接为一个完整的 URL 并规范化。
    *
    * <p>算法步骤：
    * <ol>
    *   <li>若仅传入单个片段，无需拼接，直接返回原字符串</li>
    *   <li>否则使用 Guava 的 {@code Joiner.on('/')} 将多个片段以 {@code /} 连接</li>
    *   <li>调用 {@link #normalize(String, boolean)} 进行规范化，编码参数传 {@code false}（不对路径进行编码）</li>
    * </ol>
    *
    * <p>边界情况：
    * <ul>
    *   <li>传入空数组 —— 实际由 {@code Joiner} 拼接成空字符串，再由 {@code normalize(String, false)} 处理</li>
    *   <li>片段中包含 {@code null} —— {@code Joiner} 会将其视为字符串 {@code "null"} 参与拼接，
    *       建议调用方确保片段均非空</li>
    * </ul>
    *
    * @param url URL 路径片段数组，如 {@code {"http://example.com", "api", "v1"}}
    * @return 拼接并规范化后的 URL 字符串，各部分以 {@code /} 连接且无重复斜杠，
    *         例如 {@code "http://example.com/api/v1"}
    */
    public static String normalize(String... url) {
        if (url.length == 1) {
            return url[0];
        }
        return normalize(Joiner.on('/').join((Object[]) url), false);
    }
    /**
     * 规范化 URL 字符串，移除过多斜杠/反斜杠、路径穿越，并可选择对路径部分进行百分号编码。
     *
     * <p>算法分以下 <b>5 步</b>处理：
     * <ol>
     *   <li><b>分离协议头</b> —— 查找字符串中的首个 {@code "://"}，
     *       之前的部分（含 {@code ://}）作为 {@code protocol}，之后的部分作为 {@code body}。
     * 若不存在 {@code ://}，则视整个字符串为无协议的 主体。</li>
     *   <li><b>分离查询参数</b> —— 在 body 中查找首个 {@code ?}，
     *       {@code ?} 及之后的部分作为 {@code params} 临时移除。</li>
     *   <li><b>清理路径</b> —— 去除开头斜杠、反斜杠转正斜杠、压缩连续斜杠。</li>
     *   <li><b>防路径穿越</b> —— 剥离所有 {@code ../} 和 {@code ..\\} 片段，防止目录遍历攻击。</li>
     *   <li><b>可选路径编码</b> —— 若 {@code isEncodePath} 为 {@code true}，对路径进行百分号编码。</li>
     * </ol>
     *
     * @param url          待规范的 URL 字符串，可为 {@code null} 或空白
     * @param isEncodePath 是否对路径部分进行编码
     * @return 规范化后的 URL 字符串
     */
    public static String normalize(String url, boolean isEncodePath) {
        if (StringUtils.isBlank(url)) {
            return url;
        }
        final int sepIndex = url.indexOf("://");
        String protocol = "";
        String body = url;
        if (sepIndex > 0) {
            protocol = StringUtils.subPre(url, sepIndex + 3);
            body = StringUtils.subSuf(url, sepIndex + 3);
        }

        final int paramsSepIndex = StringUtils.indexOf(body, '?');
        String params = null;
        if (paramsSepIndex > 0) {
            params = StringUtils.subSuf(body, paramsSepIndex);
            body = StringUtils.subPre(body, paramsSepIndex);
        }

        if (!StringUtils.isBlank(body)) {
            body = body.replaceAll("^[\\\\/]+", SYMBOL_EMPTY);
            // placeAll("//+", "/");
            body = body.replace("\\", "/");
            // 防路径穿越：剥离 ../ 片段
            body = stripPathTraversal(body);
        }

        final int pathSepIndex = StringUtils.indexOf(body, '/');
        String domain = body;
        String path = null;
        if (pathSepIndex > 0) {
            domain = StringUtils.subPre(body, pathSepIndex);
            path = StringUtils.subSuf(body, pathSepIndex);
        }
        if (isEncodePath) {
            path = encode(path);
        }
        return protocol + domain + StringUtils.nullToEmpty(path) + StringUtils.nullToEmpty(params);
    }

    /**
     * 剥离路径中的穿越片段（{@code ../}、{@code ..\\}、{@code ..}）。
     *
     * <p>算法：将路径按 {@code /} 拆分，逐段检查：
     * <ul>
     *   <li>若当前段为 {@code ..}，则移除栈中最后一个有效段（若有）</li>
     *   <li>若当前段为 {@code .} 或空串，跳过</li>
     *   <li>否则压入栈</li>
     * </ul>
     *
     * @param path 已统一为正斜杠的路径
     * @return 去除穿越后的安全路径
     */
    private static String stripPathTraversal(String path) {
        String[] parts = path.split("/");
        var stack = new java.util.ArrayList<String>(parts.length);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.add(part);
            }
        }
        return String.join("/", stack);
    }
    /**
     * 使用默认 {@link SimpleUrlEncoder} 对 URL 进行百分号编码（percent-编码）。
     *
     * <p>编码格式遵循 RFC 3986 规范：安全字符（字母、数字、{@code -._~!$&'()*+,;=:@/}）保持原样，
     * 非安全字符（如中文字符、空格、特殊符号）转换为 {@code %XX} 格式，其中 {@code XX} 为该字节的无符号十六进制大写表示。
     *
     * <p><b>使用场景</b>：
     * <ul>
     *   <li>URL 路径中包含非 ASCII 字符（如中文名称）时，需对其进行编码，否则可能引发 {@code URISyntaxException}</li>
     *   <li>构建含特殊字符的查询参数时，需要对参数值进行编码</li>
     *   <li>此编码器<b>不</b>将空格转为 {@code +} 号，而是转为 {@code %20}</li>
     * </ul>
     *
     * @param url 待编码的 URL 字符串，可为 {@code null}
     * @return 编码后的 URL 字符串；若入参为 {@code null} 或空，返回原值或空串
     */
    public static String encode(String url) {
        return SimpleUrlEncoder.DEFAULT.encode(url);
    }

    /**
     * 使用 UTF-8 字符集解码 {@code application/x-www-form-urlencoded} 格式的字符串。
     *
     * <p>解码规则：将 {@code %XX}（两位十六进制字符）还原为对应的字节并根据 UTF-8 解释为原始字符。
     *
     * <p><b>使用场景</b>：
     * <ul>
     *   <li>从 HTTP 请求中获取到编码后的查询参数或表单数据时，需解码为可读字符串</li>
     *   <li>从 URL 中提取编码后的文件名、路径段时使用</li>
     * </ul>
     *
     * <p>此方法等价于 {@code URLDecoder.decode(url, StandardCharsets.UTF_8.name())}。
     *
     * @param url 待解码的 URL 字符串，不能为 {@code null}
     * @return 解码后的字符串
     * @throws UnsupportedEncodingException 当系统不支持 UTF-8 时抛出（Java 标准环境不会出现）
     * @since 3.1.2
     */
    public static String decode(String url) throws UnsupportedEncodingException {
        return decode(url, UTF_8);
    }


    /**
     * 使用指定字符集解码 {@code application/x-www-form-urlencoded} 格式字符串。
     *
     * <p>解码算法：
     * <ol>
     *   <li>遍历输入字符串，识别 {@code %} 加两位十六进制字符（如 {@code %E4%B8%AD}）的编码序列</li>
     *   <li>将两位十六进制字符转为对应的字节值</li>
     *   <li>将字节序列按照指定 {@code charset} 组装为 Java 字符</li>
     *   <li>若 {@code charset} 为 {@code null}，则不做任何解码，直接返回原始字符串</li>
     * </ol>
     *
     * <p>边界情况：
     * <ul>
     *   <li>输入不含 {@code %} 编码 —— 返回原字符串</li>
     *   <li>输入为 {@code null} —— 返回 {@code null}</li>
     *   <li>出现非法的百分号编码（如 {@code %ZZ}） —— 行为由 {@link URLDecoder} 内部实现决定</li>
     * </ul>
     *
     * @param content 待解码的内容，可为 {@code null}
     * @param charset 字符集，若为 {@code null} 则不进行解码
     * @return 解码后的字符串
     * @since 5.6.3
     */
    public static String decode(String content, Charset charset) {
        if (null == charset) {
            return content;
        }
        return URLDecoder.decode(content, charset);
    }

    /**
     * 使用指定字符集名称解码 {@code application/x-www-form-urlencoded} 格式字符串。
     *
     * <p>此方法为 {@link #decode(String, Charset)} 的便捷重载，
     * 通过 {@code Charset.forName(charset)} 将字符串转为 {@link Charset} 后调用。
     *
     * @param content 待解码的字符串，可为 {@code null}
     * @param charset 字符集名称，如 {@code "UTF-8"}、{@code "GBK"}，不能为 {@code null}
     * @return 解码后的字符串
     * @throws UnsupportedEncodingException 当 {@code charset} 名称对应的字符集不被 JVM 支持时抛出
     */
    public static String decode(String content, String charset) throws UnsupportedEncodingException {
        return decode(content, Charset.forName(charset));
    }

    /**
     * 获取 URL 的解码后路径（Decoded 路径）。
     *
     * <p><b>双层回退策略</b>：
     * <ol>
     *   <li><b>优先通过 URI 获取</b> —— 调用 {@link #toUri(URL)} 将 URL 转为 URI，
     *       再调用 {@link URI#getPath()} 获取路径。URI 的 {@code getPath()} 返回的是解码后的路径
     *       （例如原始 URL 中编码的 {@code %E4%B8%AD} 会被解码为对应的 Unicode 字符）。</li>
     *   <li><b>回退到 URL.getPath()</b> —— 若 URI 的路径为 {@code null}（可能是空路径），
     *       则回退使用 {@link URL#getPath()}。注意 {@code URL.getPath()} <b>不进行解码</b>，
     *       返回的是原始编码形式的路径字符串。</li>
     * </ol>
     *
     * <p>边界情况：
     * <ul>
     *   <li>入参为 {@code null} —— 直接返回 {@code null}</li>
     *   <li>URL 转 URI 过程抛出 {@link URISyntaxException} —— 返回 {@code null}（见 {@link #toUri(URL)}）</li>
     *   <li>URI 路径不为 null 但为空字符串 —— 不满足 {@code null != path} 条件，因此走回退逻辑调用 {@code url.getPath()}</li>
     * </ul>
     *
     * @param url 目标 URL 对象
     * @return URI 解码后的路径字符串（若可用），否则返回 URL 的原始路径；
     * 若 url 为 空 则返回 空
     * @throws NullPointerException 如果 URL 转 URI 后为 空（即转换失败），
     *         调用 {@link Objects#requireNonNull(Object)} 时抛出此异常
     * @see #toUri(URL)
     */
    public static String getDecodedPath(URL url) {
        if (null == url) {
            return null;
        }

        String path = null;
 // 优先通过 URI 获取解码后的 路径，若失败则回退到 URL 的 获取路径
        path = Objects.requireNonNull(toUri(url)).getPath();
        return (null != path) ? path : url.getPath();
    }


    /**
     * 将 {@link URL} 对象转换为 {@link URI} 对象，并优雅处理语法异常。
     *
     * <p><b>异常处理策略</b>：
     * <ol>
     *   <li>直接调用 {@link URL#toURI()}，该方法内部使用 {@code new URI(url.toString())}，
     *       因此会对 URL 字符串中的特殊字符（如未编码的空格、中文字符等）进行 URI 语法校验</li>
     *   <li>若 URL 字符串不符合 URI 语法规范（如包含非法字符），抛出 {@link URISyntaxException}</li>
     *   <li>捕获异常后，打印异常堆栈至标准错误流，返回 {@code null}</li>
     * </ol>
     *
     * <p>返回 {@code null} 意味着：
     * <ul>
     *   <li>原始 URL 包含 URI 规范不允许的字符，建议调用方先对 URL 进行编码再转换</li>
     *   <li>上游方法（如 {@link #getDecodedPath(URL)}）需要处理 null 返回值的情况</li>
     * </ul>
     *
     * @param url 目标 URL 对象，不可为 {@code null}
     * @return 对应的 URI 对象；若 URL 字符串不符合 URI 语法规范，返回 {@code null}
     */
    public static URI toUri(URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return null;
    }













    /**
     * 规范化路径字符串，确保以 {@code /} 开头。
     *
     * <p>边界情况：
     * <ul>
     *   <li>输入为 {@code null} 或空白 —— 返回 {@code "/"}</li>
     *   <li>输入已以 {@code /} 开头 —— 返回原字符串</li>
     *   <li>输入不含 {@code /} —— 前插 {@code /}</li>
     * </ul>
     *
     * @param path 待规范化的路径字符串，可为 {@code null}
     * @return 以 {@code /} 开头的路径字符串
     * @since 2026/07/18
     * @author CH
     */
    public static String normalizePath(String path) {
        if (StringUtils.isBlank(path)) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    static class SimpleUrlEncoder {
        /**
         * 默认的 URL 编码器单例，使用符合 RFC 3986 URI 规范的完整安全字符集构建。
         *
         * <p>基于 URI 规范（RFC 3986），安全字符的授权范围定义为：
         * <pre>
         *   pchar       = unreserved / pct-encoded / sub-delims / ":" / "@"
         *   unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"
         *   sub-delims  = "!" / "$" / "&amp;" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
         * </pre>
         *
         * <p>即在上述定义中出现的字符在编码时保持原样不转换。
         * 此外还额外将 {@code /} 加入安全字符集，以确保路径分隔符不会被编码为 {@code %2F}。
         *
         * @see #createDefault()
         */
        public static final SimpleUrlEncoder DEFAULT = createDefault();
        /**
         * 其中被置位的字符在编码时原样保留，不转换为百分号编码。
         */
        private final BitSet safeCharacters;

        /**
         * 构造默认编码器，创建容量为 256 的 {@code BitSet}，
         * 并初始化以下 62 个基础字符为安全字符：
         * <ul>
         *   <li>小写字母 —— {@code a}（{@code 0x61}）至 {@code z}（{@code 0x7A}），共 26 个</li>
         *   <li>大写字母 —— {@code A}（{@code 0x41}）至 {@code Z}（{@code 0x5A}），共 26 个</li>
         *   <li>数字 —— {@code 0}（{@code 0x30}）至 {@code 9}（{@code 0x39}），共 10 个</li>
         * </ul>
         *
         * <p>这些字符在任何 URI 上下文中均不需要编码，是最基础的安全字符。
         *
         * @see #addCharacter(char)
         */
        public SimpleUrlEncoder() {
            this(new BitSet(256));

            for (char i = SYMBOL_LOWER_A; i <= SYMBOL_LOWER_Z; i++) {
                addCharacter(i);
            }
            for (char i = SYMBOL_UPPER_A; i <= SYMBOL_UPPER_Z; i++) {
                addCharacter(i);
            }
            for (char i = SYMBOL_ZERO; i <= SYMBOL_NINE; i++) {
                addCharacter(i);
            }
        }

        /**
         * 使用预构建的安全字符集构造编码器（私有构造器，仅由 {@code createDefault()} 调用）。
         *
         * @param safeCharacters 已经填充了基础安全字符（字母+数字）的 {@code BitSet}
         */
        private SimpleUrlEncoder(BitSet safeCharacters) {
            this.safeCharacters = safeCharacters;
        }

        /**
         * 创建默认的 URL 编码器实例，在字母和数字的基础上，补充以下 18 个字符为安全字符：
         *
         * <p><b>添加的安全字符及依据（RFC 3986）</b>：
         * <table border="1">
         *   <tr><th>分组</th><th>字符</th><th>依据</th><th>说明</th></tr>
         *   <tr><td>unreserved</td><td>{@code - . _ ~}</td><td>RFC 3986 §2.3</td><td>这些字符在 URI 所有组件中均可直接使用，无需编码</td></tr>
         *   <tr><td>sub-delims</td><td>{@code ! $ & ' ( ) * + , ; =}</td><td>RFC 3986 §2.2</td><td>子分隔符，在 URI 的 query 和 fragment 组件中具有特殊语义，但本身无需编码</td></tr>
         *   <tr><td>gen-delims 子集</td><td>{@code : @}</td><td>RFC 3986 §2.2</td><td>冒号用于分隔 scheme 和 authority，at 符号用于 authority 中的用户信息，
         *       在路径上下文中需要被视为安全字符</td></tr>
         *   <tr><td>额外字符</td><td>{@code /}</td><td>路径特殊需求</td><td>路径分隔符，编码后变为 {@code %2F} 会改变路径结构，
         *       因此必须保留其字面形式</td></tr>
         * </table>
         *
         * <p>合计：基础 62 个（字母+数字）+ 18 个 = <b>80 个</b>安全字符。
         *
         * @return 默认的 URL 编码器，覆盖了 URI 规范中所有不需要编码的字符
         */
        public static SimpleUrlEncoder createDefault() {
            final SimpleUrlEncoder encoder = new SimpleUrlEncoder();
            encoder.addCharacter('-');
            encoder.addCharacter('.');
            encoder.addCharacter('_');
            encoder.addCharacter('~');
 // 添加 the sub-delims
            encoder.addCharacter('!');
            encoder.addCharacter('$');
            encoder.addCharacter('&');
            encoder.addCharacter('\'');
            encoder.addCharacter('(');
            encoder.addCharacter(')');
            encoder.addCharacter('*');
            encoder.addCharacter('+');
            encoder.addCharacter(',');
            encoder.addCharacter(';');
            encoder.addCharacter('=');
 // 添加 the remaining 字面量
            encoder.addCharacter(':');
            encoder.addCharacter('@');
            // Add '/' so it isn't encoded when we encode a path
            encoder.addCharacter('/');

            return encoder;
        }

        /**
         * 向安全字符集的 {@code BitSet} 中追加一个字符。
         *
         * <p>方法直接调用 {@code safeCharacters.set(c)}，将该字符的 Unicode 码点位置标记为 1。
         * 标记后，该字符在 {@link #encode(String)} 中将原样输出，不会被转换为百分号编码。
         *
         * <p>此方法在 {@link #SimpleUrlEncoder()}（初始化字母数字）和
         * {@link #createDefault()}（追加 unreserved、sub-delims 和特殊字符）中被多次调用。
         *
         * @param c 要加入安全字符集的字符
         */
        public void addCharacter(char c) {
            safeCharacters.set(c);
        }

        /**
         * 对字符串进行 URL 百分号编码（percent-编码），采用逐字符遍历的方式。
         *
         * <p><b>编码算法（逐字符处理）</b>：
         * <ol>
         *   <li><b>安全字符合法</b> —— 检查当前字符 {@code c} 是否在 {@code safeCharacters} 位集中。
         *       若已置位，直接将字符追加到输出缓冲区，不做任何转换。</li>
         *   <li><b>空格特殊处理（当前为关闭状态）</b> —— 检查 {@code encodeSpaceAsPlus} 标志（当前硬编码为 {@code false}），
         *       若为 {@code true} 且当前字符为空格（{@code ' '}），则追加 {@code '+'} 号。
         *       <br><em>当前实现中此分支永远不执行</em>，空格走第 3 步转为 {@code %20}。</li>
         *   <li><b>非安全字符编码</b> —— 将字符通过 UTF-8 {@link OutputStreamWriter} 写出为字节数组，
         *       然后对每个字节执行百分号编码：
         *       <br>遍历字节数组，每个字节先追加 {@code '%'}，再调用 {@link Hex#appendHex} 追加该字节的两位无符号十六进制大写表示
         *       <br>（例如字节值 {@code 0x20} → {@code %20}，字节值 {@code 0xE4} → {@code %E4}）。
         *       <br>编码完当前字符的所有字节后，重置 {@code ByteArrayOutputStream} 缓冲区以处理下一个字符。
         *       <br>若 {@code OutputStreamWriter} 写入或刷新时发生 {@link IOException}，则重置缓冲区并跳过该字符。
         *   </li>
         * </ol>
         *
         * <p><b>关键设计细节</b>：
         * <ul>
         *   <li>每个字符独立进行 UTF-8 编码 → 字节序列 → 百分号编码，因此多字节字符
         *       （如中文字符 {@code '中'} 的 UTF-8 为 {@code E4 B8 AD}）会被编码为 {@code %E4%B8%AD}</li>
         *   <li>安全字符集基于 RFC 3986 的 unreserved + sub-delims + ":" + "@" + "/"，
         *       确保大部分 URL 合法字符不会被意外编码</li>
         *   <li>编码输出为无符号十六进制大写（通过 {@code Hex.appendHex(buf, toEncode, false)}），
         *       符合 {@link URLDecoder} 的期望解码格式</li>
         * </ul>
         *
         * <p>此方法与 JDK 的 {@code URLEncoder.encode()} 的区别：
         * <ul>
         *   <li>JDK 实现会将 {@code -._} 以外的几乎所有非字母数字字符编码</li>
         *   <li>本实现基于 RFC 3986，保留了更多 URI 合法字符（如 {@code !$&'()*+,;=:@}）</li>
         * </ul>
         *
         * @param source 待编码的字符串，可为 {@code null}
         * @return 编码后的字符串；所有安全字符原样输出，非安全字符转为 {@code %XX} 格式
         */
        public String encode(String source) {
            final StringBuilder rewrittenPath = new StringBuilder(source.length());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(buf, UTF_8);

            int c;
            for (int i = 0; i < source.length(); i++) {
                c = source.charAt(i);
                //                     +
                boolean encodeSpaceAsPlus = false;
                if (safeCharacters.get(c)) {
                    rewrittenPath.append((char) c);
                } else if (encodeSpaceAsPlus && c == ' ') {
                    //
                    rewrittenPath.append('+');
                } else {
 // 转换 转为 外部 编码 之前 hex 转换
                    try {
                        writer.write((char) c);
                        writer.flush();
                    } catch (IOException e) {
                        buf.reset();
                        continue;
                    }

                    byte[] ba = buf.toByteArray();
                    for (byte toEncode : ba) {
 // 转换 each byte 入 the 缓冲
                        rewrittenPath.append('%');
                        Hex.appendHex(rewrittenPath, toEncode, false);
                    }
                    buf.reset();
                }
            }
            return rewrittenPath.toString();
        }

    }

}
