package com.chua.deeplearning.support.weka.data;

import com.chua.deeplearning.support.weka.WekaException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 文件 -> {@link WekaInstanceData} 加载器。
 *
 * <p>按逗号分隔解析（支持双引号包裹与 "" 转义），自动推断各列类型：
 * 非空值均可解析为数值时推断为数值特征，否则推断为类别特征。
 * 加载结果不含标签 / 目标列，需通过 {@link WekaInstanceData#withLabelColumn(String)} 或
 * {@link WekaInstanceData#withTargetColumn(String)} 指定。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class WekaCsvLoader {

    private WekaCsvLoader() {
        throw new UnsupportedOperationException("工具类，禁止实例化");
    }

    /**
     * 读取带表头的 CSV 文件。
     *
     * @param csvFile CSV 文件路径
     * @return 数据对象（特征类型已自动推断）
     * @throws WekaException 文件读取失败或数据行为空
     */
    public static WekaInstanceData load(Path csvFile) {
        return load(csvFile, true);
    }

    /**
     * 读取 CSV 文件。
     *
     * @param csvFile   CSV 文件路径
     * @param hasHeader 第一行是否为表头
     * @return 数据对象（特征类型已自动推断）
     * @throws WekaException 文件读取失败或数据行为空
     */
    public static WekaInstanceData load(Path csvFile, boolean hasHeader) {
        List<String> lines;
        try {
            lines = Files.readAllLines(csvFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WekaException("CSV 读取失败: " + csvFile, e);
        }
        int start = hasHeader ? 1 : 0;
        if (start >= lines.size() || lines.subList(start, lines.size()).stream().allMatch(String::isBlank)) {
            throw new WekaException("CSV 无数据行: " + csvFile);
        }
        List<List<String>> cellRows = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                cellRows.add(splitLine(lines.get(i)));
            }
        }
        int numCols = cellRows.get(0).size();
        List<String> names = new ArrayList<>(numCols);
        if (hasHeader) {
            List<String> header = splitLine(lines.get(0));
            for (int i = 0; i < numCols; i++) {
                String name = i < header.size() ? header.get(i) : "col_" + i;
                names.add(stripBom(name).trim());
            }
        } else {
            for (int i = 0; i < numCols; i++) {
                names.add("col_" + i);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(cellRows.size());
        for (List<String> cells : cellRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < numCols; c++) {
                String cell = c < cells.size() ? cells.get(c).trim() : "";
                row.put(names.get(c), cell.isEmpty() ? null : cell);
            }
            rows.add(row);
        }
        List<FeatureColumn> features = new ArrayList<>(numCols);
        for (int c = 0; c < numCols; c++) {
            boolean numeric = true;
            for (Map<String, Object> row : rows) {
                Object value = row.get(names.get(c));
                if (value != null) {
                    try {
                        Double.parseDouble((String) value);
                    } catch (NumberFormatException e) {
                        numeric = false;
                        break;
                    }
                }
            }
            features.add(numeric
                    ? FeatureColumn.numeric(names.get(c))
                    : FeatureColumn.categorical(names.get(c)));
        }
        return WekaInstanceData.of(features, rows);
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    /**
     * 按逗号拆分一行 CSV，支持双引号包裹字段与 "" 转义。
     *
     * @param line CSV 行
     * @return 字段列表
     */
    private static List<String> splitLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }
}
