package com.chua.cad.support.dxf;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DXF 解析器（纯 Java，零外部依赖）。
 *
 * <p>DXF 是纯文本的「分组码 → 值」成对结构：每两行一组，偶数行为分组码
 * （纯数字），奇数行为值。整份文件由 {@code SECTION} / {@code ENDSEC} 划分，
 * 本解析器只取 {@code HEADER} 与 {@code ENTITIES} 两段。</p>
 *
 * <p><b>为什么自己写</b>：DXF 是 Autodesk 公开交换格式，结构跨 decades 稳定；
 * 渲染一张图纸真正需要的只有十几种基础图元。引入外部库（kabeja 等）既要背
 * GPL 与停止维护的风险，又比自己解析更重。</p>
 *
 * <p><b>已知限制</b>（如实说明，不假装支持）：</p>
 * <ul>
 *   <li>仅支持 ASCII DXF；二进制 DXF 不解析</li>
 *   <li>只渲染可直绘的基础图元；INSERT / SPLINE / 3DFACE / DIMENSION / HATCH
 *       等会记入 {@link DxfDocument#unsupportedEnts()} 并跳过</li>
 *   <li>不解析图层属性表（颜色 / 线宽 / 线型），所有图元同色同宽渲染</li>
 *   <li>按 UTF-8 解码；DXF 传统为 ANSI 编码，中文图层名可能乱码，几何不受影响</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DxfParser {

    /**
     * 单份 DXF 允许的最大字节数（防御超大文件 OOM，约 64 MB）
     */
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    /**
     * 单份 DXF 允许的最大图元数（防御构造炸弹：几十万图元会撑爆 SVG）
     */
    private static final int MAX_ENTITIES = 200_000;

    /**
     * 文字实体的默认字高（DXF 允许省略 40 组）
     */
    private static final double DEFAULT_TEXT_HEIGHT = 2.5;

    /**
     * {@code $INSUNITS} 单位代码 → 中文名称
     */
    private static final Map<Integer, String> UNIT_LABELS = Map.ofEntries(
            Map.entry(0, "未指定"),
            Map.entry(1, "英寸"),
            Map.entry(2, "英尺"),
            Map.entry(4, "毫米"),
            Map.entry(5, "厘米"),
            Map.entry(6, "米"),
            Map.entry(8, "微米"),
            Map.entry(9, "mil（密耳）"),
            Map.entry(10, "码"),
            Map.entry(11, "埃"),
            Map.entry(12, "纳米"),
            Map.entry(13, "微米（μm）"),
            Map.entry(14, "分米"),
            Map.entry(15, "十米"),
            Map.entry(16, "百米"),
            Map.entry(17, "千兆米"),
            Map.entry(18, "天文单位"),
            Map.entry(19, "光年"),
            Map.entry(20, "秒差距")
    );

    /**
     * 会跳过（不渲染）的实体类型集合
     */
    private static final Set<String> KNOWN_UNSUPPORTED = Set.of(
            "INSERT", "SPLINE", "3DFACE", "3DSOLID", "REGION", "BODY",
            "DIMENSION", "LEADER", "MLINE", "RAY", "XLINE", "HATCH",
            "IMAGE", "WIPEOUT", "TOLERANCE", "MLEADER", "MULTILEADER",
            "ACAD_TABLE", "LIGHT", "VIEWPORT", "SOLID", "TRACE", "ATTRIB"
    );

    /**
     * 可渲染的实体类型
     */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "LINE", "POINT", "CIRCLE", "ARC", "ELLIPSE",
            "LWPOLYLINE", "POLYLINE", "TEXT", "MTEXT"
    );

    /**
     * 解析 DXF 字节内容。
     *
     * @param content DXF 字节
     * @return 解析结果
     * @throws IOException 内容为空、过大或结构非法时抛出
     */
    public DxfDocument parse(byte[] content) throws IOException {
        if (content == null) {
            throw new IOException("DXF 内容为空");
        }
        if (content.length > MAX_BYTES) {
            throw new IOException("DXF 文件过大（" + content.length + " 字节），超过 "
                    + MAX_BYTES + " 上限");
        }
        if (!looksLikeDxf(content)) {
            throw new IOException("不是有效的 DXF（未找到 SECTION 段标记）");
        }
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(content),
                StandardCharsets.UTF_8)) {
            return doParse(new PairReader(new BufferedReader(reader)));
        }
    }

    /**
     * 粗判字节内容是否含 DXF 的 SECTION 标记（ASCII 快速扫描）
     *
     * @param content DXF 字节
     * @return true 表示形似 DXF
     */
    private boolean looksLikeDxf(byte[] content) {
        // SECTION 出现在文件前部（HEADER 段最前），只扫前 64 KB 足够
        int limit = Math.min(content.length, 64 * 1024);
        byte[] needle = "SECTION".getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (content[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 一个「分组码 + 值」对
     *
     * @param code  分组码
     * @param value 值文本
     */
    private record Pair(int code, String value) {
    }

    /**
     * 基于栈的分组码读取器。
     *
     * <p>用一个可回退的读取游标代替「预读全部分组码」：DXF 是「码、值」交替
     * 的流式结构，实体解析到下一个 {@code 0} 就结束，而结束的那一对需要交还
     * 给外层循环。用显式回退栈表达「这一对我看过了但没消费」，
     * 比在解析器上挂可变字段更安全——后者会让解析器不可重入，
     * 一旦被多线程共享就会出现难查的串数据。</p>
     */
    private static final class PairReader {

        /**
         * 底层读取器
         */
        private final BufferedReader reader;

        /**
         * 回退栈：最近 push 的在最前
         */
        private final Deque<Pair> pushback = new ArrayDeque<>();

        /**
         * 构造读取器
         *
         * @param reader 底层读取器
         */
        PairReader(BufferedReader reader) {
            this.reader = reader;
        }

        /**
         * 读取下一个「分组码 + 值」对
         *
         * @return 分组码对；文件结束返回 null
         * @throws IOException 读取失败
         */
        Pair next() throws IOException {
            Pair pending = pushback.pollFirst();
            if (pending != null) {
                return pending;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int code = DxfParser.parseCode(trimmed);
                if (code < 0) {
                    // 分组码位置出现非数字：结构已偏离预期，跳过该行继续找
                    continue;
                }
                String value = reader.readLine();
                return new Pair(code, value == null ? "" : value);
            }
            return null;
        }

        /**
         * 退回一对，供上一层循环重新处理
         *
         * @param pair 分组码对
         */
        void pushBack(Pair pair) {
            pushback.addFirst(pair);
        }
    }

    /**
     * 实际解析流程
     *
     * @param reader 分组码读取器
     * @return 解析结果
     * @throws IOException 读取失败
     */
    private DxfDocument doParse(PairReader reader) throws IOException {
        Map<String, String> headerVars = new LinkedHashMap<>();
        List<DxfEntity> entities = new ArrayList<>();
        Set<String> unsupported = new LinkedHashSet<>();
        double[] extMin = {Double.NaN, Double.NaN};
        double[] extMax = {Double.NaN, Double.NaN};

        String section = null;
        Pair pair;
        while ((pair = reader.next()) != null) {
            int code = pair.code();
            String value = pair.value();
            if (code == 0) {
                if ("SECTION".equalsIgnoreCase(value)) {
                    section = readSectionName(reader);
                } else if ("ENDSEC".equalsIgnoreCase(value)) {
                    section = null;
                } else if ("ENTITIES".equals(section)) {
                    // 实体类型标记：这一对的分组码已消费，继续读该实体的属性
                    DxfEntity entity = readEntity(reader, value, unsupported);
                    if (entity != null && entities.size() < MAX_ENTITIES) {
                        entities.add(entity);
                    }
                } else if ("EOF".equalsIgnoreCase(value)) {
                    break;
                }
                continue;
            }
            if ("HEADER".equals(section) && code == 9) {
                String name = value.toUpperCase(Locale.ENGLISH);
                String varValue = readVariableValue(reader);
                if (varValue != null) {
                    headerVars.put(name, varValue);
                    applyHeaderVar(name, varValue, extMin, extMax);
                }
            }
        }

        Bounds bounds = computeBounds(entities, extMin, extMax);
        int units = intOf(headerVars.get("$INSUNITS"));
        DxfHeader header = new DxfHeader(
                headerVars.getOrDefault("$ACADVER", "未知"),
                units,
                UNIT_LABELS.getOrDefault(units, "未指定"),
                bounds.headerMinX(), bounds.headerMinY(),
                bounds.headerMaxX(), bounds.headerMaxY());
        Map<String, Integer> layerCount = new LinkedHashMap<>();
        for (DxfEntity e : entities) {
            layerCount.merge(e.layer(), 1, Integer::sum);
        }
        return new DxfDocument(header, entities, layerCount,
                bounds.minX(), bounds.minY(), bounds.maxX(), bounds.maxY(),
                new ArrayList<>(unsupported));
    }

    /**
     * 读取 SECTION 之后的段名（HEADER / ENTITIES / TABLES …）
     *
     * @param reader 读取器
     * @return 段名；读取失败返回 null
     * @throws IOException 读取失败
     */
    private String readSectionName(PairReader reader) throws IOException {
        Pair pair = reader.next();
        if (pair == null || pair.code() != 2) {
            return null;
        }
        return pair.value();
    }

    /**
     * 从文件头变量中提取图形范围
     *
     * @param name   变量名
     * @param value  变量值
     * @param extMin 范围最小值缓冲
     * @param extMax 范围最大值缓冲
     */
    private void applyHeaderVar(String name, String value, double[] extMin, double[] extMax) {
        // $EXTMIN / $EXTMAX 形如 "1.0E+20, 2.0E+20, 0.0"，取前两个分量（X/Y）
        if (!"$EXTMIN".equals(name) && !"$EXTMAX".equals(name)) {
            return;
        }
        String[] parts = value.split(",");
        if (parts.length < 2) {
            return;
        }
        double x = doubleOf(parts[0]);
        double y = doubleOf(parts[1]);
        if ("$EXTMIN".equals(name)) {
            extMin[0] = x;
            extMin[1] = y;
        } else {
            extMax[0] = x;
            extMax[1] = y;
        }
    }

    /**
     * 读取一个完整的 DXF 实体。
     *
     * <p>入口已消费「0 + 实体类型」这一对；本方法从实体属性开始读，遇到下一个
     * {@code 0}（下一个实体或 ENDSEC）时把该对回退给外层循环。</p>
     *
     * @param reader      读取器
     * @param entityType  实体类型（已消费）
     * @param unsupported 不支持类型收集器
     * @return 解析出的图元；不支持的类型返回 null
     * @throws IOException 读取失败
     */
    private DxfEntity readEntity(PairReader reader, String entityType,
                                 Set<String> unsupported) throws IOException {
        String type = entityType.toUpperCase(Locale.ENGLISH);
        if (!SUPPORTED_TYPES.contains(type)) {
            if (KNOWN_UNSUPPORTED.contains(type)) {
                unsupported.add(type);
            }
            skipEntity(reader);
            return null;
        }

        String layer = "0";
        List<double[]> points = new ArrayList<>();
        boolean closed = false;
        double firstX = 0, firstY = 0, secondX = 0, secondY = 0;
        double radiusX = 0, radiusY = 0;
        double startAngle = 0, endAngle = 0, textHeight = 0;
        StringBuilder text = new StringBuilder();

        Pair pair;
        while ((pair = reader.next()) != null) {
            int code = pair.code();
            String value = pair.value();
            if (code == 0) {
                // 下一个实体 / ENDSEC / EOF：回退给外层
                reader.pushBack(pair);
                break;
            }
            switch (code) {
                // 8 = 图层名
                case 8 -> layer = value.trim();
                // 10 = 主点 X。LINE 用 11/21 作终点，故 10 恒为主点；
                // 折线类则每来一个 10 就新增一个顶点，等 20 补其 Y
                case 10 -> {
                    if (isPolyline(type)) {
                        points.add(new double[]{doubleOf(value), 0});
                    } else {
                        firstX = doubleOf(value);
                    }
                }
                // 20 = 主点 Y。折线的 Y 必须落到刚加的最后一个顶点上，
                // 否则 10/20 交错时会把多个顶点写成同一个
                case 20 -> {
                    if (isPolyline(type)) {
                        if (!points.isEmpty()) {
                            points.getLast()[1] = doubleOf(value);
                        }
                    } else {
                        firstY = doubleOf(value);
                    }
                }
                // 11/21 = 终点（LINE）
                case 11 -> secondX = doubleOf(value);
                case 21 -> secondY = doubleOf(value);
                // 40 = 半径（CIRCLE/ARC）、主轴长（ELLIPSE）、字高（TEXT）
                case 40 -> {
                    if ("TEXT".equals(type) || "MTEXT".equals(type)) {
                        textHeight = doubleOf(value);
                    } else {
                        radiusX = doubleOf(value);
                    }
                }
                // 41 = 次轴长（ELLIPSE）
                case 41 -> radiusY = doubleOf(value);
                // 50/51 = 起止角（ARC）；ELLIPSE 的 50 是主轴角，语义不同，
                // 归档到 startAngle 后在构造时按类型改名
                case 50 -> startAngle = doubleOf(value);
                case 51 -> endAngle = doubleOf(value);
                // 1/3 = 文字内容（3 组是多行 TEXT 的续行）
                case 1, 3 -> text.append(value);
                // 70 = 闭合标志（LWPOLYLINE 首两位：1=闭合）
                case 70 -> {
                    if (isPolyline(type)) {
                        closed = (intOf(value) & 1) == 1;
                    }
                }
                // 90 = 顶点总数（仅用于校验，读取器按实际 10/20 对数取点）
                default -> {
                    // 其余分组码（图层属性、样式表引用等）对渲染无影响
                }
            }
        }

        return switch (type) {
            case "LINE" -> DxfEntity.line(layer, firstX, firstY, secondX, secondY);
            case "POINT" -> DxfEntity.point(type, layer, firstX, firstY);
            case "CIRCLE" -> DxfEntity.arc(type, layer, firstX, firstY, radiusX, 0, 360);
            case "ARC" -> DxfEntity.arc(type, layer, firstX, firstY, radiusX, startAngle, endAngle);
            // ELLIPSE 的 50 组是主轴角，不是起始角
            case "ELLIPSE" -> new DxfEntity(type, layer, firstX, firstY, 0, 0, radiusX, radiusY,
                    0, 0, 0, startAngle, false, List.of(), "");
            case "LWPOLYLINE", "POLYLINE" -> DxfEntity.polyline(type, layer, points, closed);
            default -> DxfEntity.text(type, layer, firstX, firstY,
                    textHeight > 0 ? textHeight : DEFAULT_TEXT_HEIGHT, text.toString());
        };
    }

    /**
     * 是否为折线类实体（其 10/20 为顶点序列而非单点）
     *
     * @param type 实体类型
     * @return true 表示折线类
     */
    private boolean isPolyline(String type) {
        return "LWPOLYLINE".equals(type) || "POLYLINE".equals(type);
    }

    /**
     * 跳过当前实体的剩余内容（读到下一个 0 分组码为止，该对交还外层）
     *
     * @param reader 读取器
     * @throws IOException 读取失败
     */
    private void skipEntity(PairReader reader) throws IOException {
        Pair pair;
        while ((pair = reader.next()) != null) {
            if (pair.code() == 0) {
                reader.pushBack(pair);
                return;
            }
        }
    }

    /**
     * 读取一个 HEADER 变量的值。
     *
     * <p><b>不要跳过 70 组</b>：DXF 的 HEADER 段里，分组码 70 恰恰是
     * 整型变量的值所在（{@code 9/$INSUNITS} → {@code 70/6} 表示单位为米）。
     * 把 70 一律当标志跳过会导致所有整型变量读成 0。正确的做法是取 9 之后<b>第一个</b>分组码
     * 对的值，因为 DXF 规定一个变量紧跟其值，中间不再插入其他变量。</p>
     *
     * @param reader 读取器
     * @return 值文本；读取失败返回 null
     * @throws IOException 读取失败
     */
    private String readVariableValue(PairReader reader) throws IOException {
        Pair pair = reader.next();
        if (pair == null) {
            return null;
        }
        if (pair.code() == 0) {
            // 变量没有值（异常文件）：交还外层
            reader.pushBack(pair);
            return null;
        }
        return pair.value();
    }

    /**
     * 解析分组码文本
     *
     * @param text 文本
     * @return 分组码；非法返回 -1
     */
    private static int parseCode(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 宽松解析浮点数
     *
     * @param text 文本
     * @return 数值；非法返回 0
     */
    private double doubleOf(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 宽松解析整数
     *
     * @param text 文本
     * @return 数值；非法返回 0
     */
    private int intOf(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(text.trim());
            } catch (NumberFormatException e2) {
                return 0;
            }
        }
    }

    /**
     * 图形范围（文件头声明值 + 图元实算值）
     *
     * @param headerMinX 文件头声明的最小 X
     * @param headerMinY 文件头声明的最小 Y
     * @param headerMaxX 文件头声明的最大 X
     * @param headerMaxY 文件头声明的最大 Y
     * @param minX       图元实算最小 X
     * @param minY       图元实算最小 Y
     * @param maxX       图元实算最大 X
     * @param maxY       图元实算最大 Y
     */
    private record Bounds(double headerMinX, double headerMinY, double headerMaxX, double headerMaxY,
                          double minX, double minY, double maxX, double maxY) {
    }

    /**
     * 计算图形范围：按图元实算，文件头声明值仅作参考一并带出
     *
     * @param entities 图元
     * @param extMin   文件头最小值
     * @param extMax   文件头最大值
     * @return 范围
     */
    private Bounds computeBounds(List<DxfEntity> entities, double[] extMin, double[] extMax) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (DxfEntity e : entities) {
            switch (e.type()) {
                case "LINE" -> {
                    minX = Math.min(minX, Math.min(e.startX(), e.endX()));
                    minY = Math.min(minY, Math.min(e.startY(), e.endY()));
                    maxX = Math.max(maxX, Math.max(e.startX(), e.endX()));
                    maxY = Math.max(maxY, Math.max(e.startY(), e.endY()));
                }
                case "CIRCLE", "ARC" -> {
                    double r = e.radiusX();
                    minX = Math.min(minX, e.startX() - r);
                    minY = Math.min(minY, e.startY() - r);
                    maxX = Math.max(maxX, e.startX() + r);
                    maxY = Math.max(maxY, e.startY() + r);
                }
                case "ELLIPSE" -> {
                    double rx = e.radiusX();
                    double ry = e.radiusY();
                    minX = Math.min(minX, e.startX() - rx);
                    minY = Math.min(minY, e.startY() - ry);
                    maxX = Math.max(maxX, e.startX() + rx);
                    maxY = Math.max(maxY, e.startY() + ry);
                }
                case "LWPOLYLINE", "POLYLINE" -> {
                    for (double[] p : e.points()) {
                        minX = Math.min(minX, p[0]);
                        minY = Math.min(minY, p[1]);
                        maxX = Math.max(maxX, p[0]);
                        maxY = Math.max(maxY, p[1]);
                    }
                }
                case "POINT", "TEXT", "MTEXT" -> {
                    minX = Math.min(minX, e.startX());
                    minY = Math.min(minY, e.startY());
                    maxX = Math.max(maxX, e.startX());
                    maxY = Math.max(maxY, e.startY());
                }
                default -> {
                    // 未支持类型不参与范围计算
                }
            }
        }
        if (entities.isEmpty() || maxX < minX || maxY < minY) {
            // 无图元或范围退化：归零，避免下游算出 NaN 缩放矩阵
            minX = 0;
            minY = 0;
            maxX = 0;
            maxY = 0;
        }
        return new Bounds(
                Double.isNaN(extMin[0]) ? 0 : extMin[0],
                Double.isNaN(extMin[1]) ? 0 : extMin[1],
                Double.isNaN(extMax[0]) ? 0 : extMax[0],
                Double.isNaN(extMax[1]) ? 0 : extMax[1],
                minX, minY, maxX, maxY);
    }

    /**
     * 创建 DXF 解析器
     *
     * @return 解析器实例
     */
    public static DxfParser create() {
        return new DxfParser();
    }

    /**
     * 私有构造，请改用 {@link #create()}
     */
    private DxfParser() {
    }
}
