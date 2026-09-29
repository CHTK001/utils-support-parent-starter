package com.chua.cad.support.dwg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * DWG 文件头解析器。
 *
 * <p><b>能力边界（务必先读，不要高估）</b>：</p>
 * <ul>
 *   <li><b>能</b>：读出 AutoCAD 版本（2000 / 2004 / 2007 / 2010 / 2013 / 2018 …）、
 *       维护版本号、发行号、文件体积。</li>
 *   <li><b>不能</b>：读出图元几何。图元数据在 DWG 的 object map 与 handle 表里，
 *       结构随版本变化且 Autodesk 未完整公开，纯 Java 无法可靠解析。</li>
 * </ul>
 *
 * <p>因此本类的产物只用于「这是什么文件」的信息展示，<b>不是</b>图纸渲染。
 * 真渲染 DWG 需要外部转换器（LibreDWG / ODA / Aspose.CAD），见模块 README。</p>
 *
 * <p>版本代码取自 Autodesk 官方图纸版本代码文档，{@code AC10xx} 系列对
 * AutoCAD 2000 之后的所有版本均为 6 字节定长，故解析不依赖具体版本偏移。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DwgHeaderParser {

    /**
     * 版本代码文本长度（{@code AC1015} 为 6 字符）
     */
    private static final int VERSION_CODE_LENGTH = 6;

    /**
     * 维护版本号所在字节的偏移（紧跟版本代码之后）
     */
    private static final int MAINTENANCE_OFFSET = VERSION_CODE_LENGTH;

    /**
     * 低于此长度的字节不可能是有效 DWG 头
     */
    private static final int MIN_HEADER_LENGTH = VERSION_CODE_LENGTH + 1;

    /**
     * DWG 版本代码 → 可读名称 + AutoCAD 发行号。
     *
     * <p>依据 Autodesk 官方「Drawing format version codes for AutoCAD」文档。
     * 发行号（R15/R18/…）取自 BricsCAD 兼容性对照表。</p>
     */
    private static final Map<String, String[]> VERSION_TABLE = Map.ofEntries(
            Map.entry("MC0.0", new String[]{"DWG Release 1.1", "R1.1"}),
            Map.entry("AC1.2", new String[]{"DWG Release 1.2", "R1.2"}),
            Map.entry("AC1.4", new String[]{"DWG Release 1.4", "R1.4"}),
            Map.entry("AC1.50", new String[]{"DWG Release 2.0", "R2.0"}),
            Map.entry("AC2.10", new String[]{"DWG Release 2.10", "R2.10"}),
            Map.entry("AC1002", new String[]{"DWG Release 2.5", "R2.5"}),
            Map.entry("AC1003", new String[]{"DWG Release 2.6", "R2.6"}),
            Map.entry("AC1004", new String[]{"DWG Release 9", "R9"}),
            Map.entry("AC1006", new String[]{"DWG Release 10", "R10"}),
            Map.entry("AC1009", new String[]{"DWG Release 11/12", "R11/12"}),
            Map.entry("AC1012", new String[]{"DWG Release 13", "R13"}),
            Map.entry("AC1014", new String[]{"DWG Release 14", "R14"}),
            Map.entry("AC1015", new String[]{"AutoCAD 2000/2000i/2002", "R15"}),
            Map.entry("AC1018", new String[]{"AutoCAD 2004/2005/2006", "R18"}),
            Map.entry("AC1021", new String[]{"AutoCAD 2007/2008/2009", "R21"}),
            Map.entry("AC1024", new String[]{"AutoCAD 2010/2011/2012", "R24"}),
            Map.entry("AC1027", new String[]{"AutoCAD 2013-2017", "R27"}),
            Map.entry("AC1032", new String[]{"AutoCAD 2018-2024", "R32"})
    );

    /**
     * 解析 DWG 头部。
     *
     * <p>只在文件长度足够时读取版本代码；不足则返回 {@link #notDwg(byte[])}
     * 描述的「无法识别」结果，<b>不抛异常</b>——调用方通常要在预览失败时
     * 回退到下载按钮，抛异常会让整个预览链路变成 500。</p>
     *
     * @param content DWG 字节内容
     * @return 头部信息；无法识别版本时 {@code versionLabel} 为「未识别的 DWG 版本」
     * @throws IOException content 为 null 时抛出
     */
    public DwgHeaderInfo parse(byte[] content) throws IOException {
        if (content == null) {
            throw new IOException("DWG 内容为空");
        }
        if (content.length < MIN_HEADER_LENGTH) {
            return unknown(content.length);
        }
        String code = readVersionCode(content);
        // 版本代码必须形如 MC0.0 或 AC10xx，否则多半不是 DWG（例如误传的 DXF）
        if (!isPlausibleVersionCode(code)) {
            return unknown(content.length);
        }
        String[] entry = VERSION_TABLE.get(code);
        int maintenance = content[MAINTENANCE_OFFSET] & 0xFF;
        if (entry == null) {
            // 版本代码格式对但未收录：如实报「已识别代码，未收录含义」，不编造版本名
            return new DwgHeaderInfo(code, "已识别的 DWG 版本（未收录名称：" + code + "）",
                    maintenance, "", content.length, true);
        }
        return new DwgHeaderInfo(code, entry[0], maintenance, entry[1], content.length, false);
    }

    /**
     * 截出版本代码的有效部分。
     *
     * <p>DWG 头部的版本字段是<b>固定 6 字节</b>，但早期版本码本身不足 6 字符
     * （{@code MC0.0}、{@code AC1.2}、{@code AC1.4} 均为 5 字符），不足处用
     * {@code NUL} 补齐。若直接读满 6 字节再校验，尾部会带上 {@code NUL} 而被
     * 字符集检查拒掉，导致这几版永远无法识别。这里从偏移 0 起取连续的可见
     * ASCII，遇 {@code NUL} 或不可见字符即停，得到真实代码长度（5 或 6）。</p>
     *
     * @param content DWG 字节内容
     * @return 有效版本代码；首字节即不可见时返回空串
     */
    private String readVersionCode(byte[] content) {
        int end = 0;
        while (end < VERSION_CODE_LENGTH) {
            int c = content[end] & 0xFF;
            if (c == 0 || c < 0x20 || c > 0x7E) {
                break;
            }
            end++;
        }
        return new String(content, 0, end, StandardCharsets.US_ASCII);
    }

    /**
     * 判断文本是否形似 DWG 版本代码。
     *
     * <p>已知代码集中在 {@code MC0.0} 与 {@code AC1xxx} / {@code AC10xx} 两类，
     * 长度 5（早期）或 6（AC10xx 系列）都合法。这里只做前缀、长度与字符集校验，
     * 不试图枚举全部取值——新版本会持续出现，校验过严会把合法文件判成非 DWG。</p>
     *
     * @param code 版本代码文本
     * @return true 表示形似版本代码
     */
    private boolean isPlausibleVersionCode(String code) {
        if (code.length() < VERSION_CODE_LENGTH - 1 || code.length() > VERSION_CODE_LENGTH) {
            return false;
        }
        if (!code.startsWith("AC") && !code.startsWith("MC")) {
            return false;
        }
        for (int i = 2; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean dot = c == '.';
            if (!digit && !dot) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造「无法识别」的头部信息。
     *
     * @param fileSize 文件字节数
     * @return 头部信息
     */
    private DwgHeaderInfo unknown(long fileSize) {
        return new DwgHeaderInfo("", "未识别的 DWG 版本", -1, "", fileSize, true);
    }

    /**
     * 该文件是否「看起来是 DWG」（仅版本代码校验，不保证后续可解析）。
     *
     * @param content 文件字节
     * @return true 表示形似 DWG
     */
    public boolean looksLikeDwg(byte[] content) {
        if (content == null || content.length < MIN_HEADER_LENGTH) {
            return false;
        }
        return isPlausibleVersionCode(readVersionCode(content));
    }

    /**
     * 创建解析器
     *
     * @return 解析器实例（无状态，可复用）
     */
    public static DwgHeaderParser create() {
        return new DwgHeaderParser();
    }

    /**
     * 私有构造，请改用 {@link #create()}
     */
    private DwgHeaderParser() {
    }
}
