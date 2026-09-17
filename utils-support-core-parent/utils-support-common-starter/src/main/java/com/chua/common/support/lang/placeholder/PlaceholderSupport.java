package com.chua.common.support.lang.placeholder;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

/**
* 占位符支持类，用于解析字符串中的变量、函数调用、三元表达式和数组/Map访问。
* <h2>支持的语法格式：</h2>
* <ul>
*   <li>${key} - 解析系统属性或环境变量</li>
*   <li>${key:defaultValue} - 如果 key 不存在，则使用默认值</li>
*   <li>${key[0]} - 解析数组或列表索引 (如 "1,2,3")</li>
*   <li>${key[field]} - 解析 Map 字段 (如 "{a:1,b:2}")</li>
*   <li>${condition ? 'true' : 'false'} - 三元运算符表达式</li>
*   <li>${now(yyyy-MM-dd)} - 获取当前时间并格式化</li>
*   <li>${uuid} - 生成 UUID</li>
*   <li>${random(100)} - 生成指定范围内的随机数</li>
*   <li>${upper(text)} - 将文本转换为大写</li>
*   <li>${lower(text)} - 将文本转换为小写</li>
*   <li>${length(text)} - 获取文本长度</li>
* </ul>
*
* @author CH
* @since 2026/07/14
 */
@Setter
public class PlaceholderSupport {

    /** Default_placeholder_prefix */
    public static final String DEFAULT_PLACEHOLDER_PREFIX = "${";
    /** Default_placeholder_suffix */
    public static final String DEFAULT_PLACEHOLDER_SUFFIX = "}";
    /** Default_value_separator */
    public static final String DEFAULT_VALUE_SEPARATOR = ":";

    @Getter
    /** Placeholderprefix */
    private String placeholderPrefix = DEFAULT_PLACEHOLDER_PREFIX;
    @Getter
    /** Placeholdersuffix */
    private String placeholderSuffix = DEFAULT_PLACEHOLDER_SUFFIX;
    @Getter
    /** 值separator */
    private String valueSeparator = DEFAULT_VALUE_SEPARATOR;
    @Getter
    /** Ignoreunresolvableplaceholders */
    private boolean ignoreUnresolvablePlaceholders = false;
    /** Trimvalues */
    private boolean trimValues = true;

    /**
    * 是否启用内置函数功能 (如 now, uuid, random 等)
    */
    @Getter
    /** Function是否启用 */
    private boolean functionEnabled = true;

    /**
    * 是否启用三元表达式功能 (如 condition ? trueVal : falseVal)
    */
    @Getter
    /** Ternary是否启用 */
    private boolean ternaryEnabled = true;

    /**
    * 是否启用数组和 Map 的方括号访问功能 (如 key[index])
    */
    @Getter
    /** 数组access是否启用 */
    private boolean arrayAccessEnabled = true;

    @Getter
    @Setter
    @Accessors(chain = true)
    /** 解析器 */
    private PlaceholderResolver resolver = new SystemPropertyPlaceholderResolver();

    /**
    * 用户自定义函数的注册表
    */
    private final Map<String, Function<String, String>> functions = new HashMap<>();

    /** 创建 PlaceholderSupport 实例 */
    public PlaceholderSupport() {
        registerFunction("now", this::nowFunction);
        registerFunction("uuid", this::uuidFunction);
        registerFunction("random", this::randomFunction);
        registerFunction("upper", this::upperFunction);
        registerFunction("lower", this::lowerFunction);
        registerFunction("length", this::lengthFunction);
    }

    /**
    * 创建 PlaceholderSupport 实例
    * @param placeholderPrefix placeholderPrefix
    * @param String String
    * @param String String
    */
    public PlaceholderSupport(String placeholderPrefix, String placeholderSuffix, String valueSeparator) {
        this();
        this.placeholderPrefix = placeholderPrefix;
        this.placeholderSuffix = placeholderSuffix;
        this.valueSeparator = valueSeparator;
    }

    /**
    * 注册自定义函数
    *
    * @param name    函数名称
    * @param function 函数实现
    */
    public void registerFunction(String name, Function<String, String> function) {
        functions.put(name, function);
    }

    /**
    * 解析单个占位符名称（不包含前缀和后缀）
    *
    * @param placeholderName 占位符名称部分，例如 "key" 或 "now(date)"
    * @return 解析后的结果字符串，如果无法解析则返回 null
    */
    public String resolvePlaceholder(String placeholderName) {
        // 1. 优先处理三元表达式：${condition ? 'true' : 'false'}
        if (ternaryEnabled && placeholderName.contains("?") && placeholderName.contains(":")) {
            String result = resolveTernary(placeholderName);
            if (result != null) {
                return result;
            }
        }

        // 2. 处理函数调用：${func(args)}
        if (functionEnabled && placeholderName.contains("(") && placeholderName.endsWith(")")) {
            int parenIndex = placeholderName.indexOf('(');
            String funcName = placeholderName.substring(0, parenIndex);
            String args = placeholderName.substring(parenIndex + 1, placeholderName.length() - 1);

            Function<String, String> function = functions.get(funcName);
            if (function != null) {
                return function.apply(args);
            }
        }

        // 3. 处理数组/Map 访问：${key[0]} 或 ${key[field]}
        if (arrayAccessEnabled && placeholderName.contains("[") && placeholderName.endsWith("]")) {
            String result = resolveArrayAccess(placeholderName);
            if (result != null) {
                return result;
            }
        }

        // 4. 最后尝试通过 Resolver 进行常规解析
        if (resolver != null) {
            return resolver.resolvePlaceholder(placeholderName);
        }
        return null;
    }

    /**
    * 解析三元表达式
    *
    * @param expr 表达式字符串，如 "status == 1 ? 'success' : 'failed'"
    * @return 计算结果，失败返回 null
    */
    private String resolveTernary(String expr) {
        try {
            // 找到 '?' 的位置
            int questionIndex = expr.indexOf('?');
            // 找到 ':' 的位置 (需忽略引号内的冒号)
            int colonIndex = findColonIndex(expr, questionIndex);

            if (questionIndex < 0 || colonIndex < 0) {
                return null;
            }

            String condition = expr.substring(0, questionIndex).trim();
            String trueValue = expr.substring(questionIndex + 1, colonIndex).trim();
            String falseValue = expr.substring(colonIndex + 1).trim();

            // 评估条件
            boolean conditionResult = evaluateCondition(condition);

            // 根据条件选择结果并去除引号
            String result = conditionResult ? trueValue : falseValue;
            return removeQuotes(result);
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * 在表达式中查找第一个不在引号内的冒号位置
    *
    * @param expr      表达式字符串
    * @param startIndex 开始搜索的位置
    * @return 冒号的索引，未找到返回 -1
    */
    private int findColonIndex(String expr, int startIndex) {
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = startIndex + 1; i < expr.length(); i++) {
            char c = expr.charAt(i);

            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                }
            } else {
                if (c == '\'' || c == '"') {
                    inQuote = true;
                    quoteChar = c;
                } else if (c == ':') {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
    * 评估条件的真假
    *
    * @param condition 条件字符串
    * @return true 或 false
    */
    private boolean evaluateCondition(String condition) {
        condition = condition.trim();

        // 空条件视为 false
        if (condition.isEmpty()) {
            return false;
        }

        // 直接判断布尔字面量
        if ("true".equalsIgnoreCase(condition)) {
            return true;
        }
        if ("false".equalsIgnoreCase(condition)) {
            return false;
        }

        // 处理比较运算符 (==, !=, >, <, >=, <=)
        if (condition.contains(">") || condition.contains("<") || condition.contains("==") || condition.contains("!=")) {
            return evaluateComparison(condition);
        }

        // 处理非空字符串检查 (单引号或双引号包裹)
        if (condition.startsWith("'") || condition.startsWith("\"")) {
            String value = removeQuotes(condition);
            return !value.isEmpty();
        }

        // 尝试通过 Resolver 解析变量名
        if (resolver != null) {
            String value = resolver.resolvePlaceholder(condition);
            return value != null && !value.isEmpty();
        }

        return false;
    }

    /**
    * 执行具体的比较运算
    *
    * @param condition 包含运算符的条件字符串
    * @return 比较结果
    */
    private boolean evaluateComparison(String condition) {
        String operator;
        String left;
        String right;

        // 识别运算符并分割左右操作数 (注意顺序，先匹配长运算符)
        if (condition.contains("==")) {
            operator = "==";
            int idx = condition.indexOf("==");
            left = condition.substring(0, idx).trim();
            right = condition.substring(idx + 2).trim();
        } else if (condition.contains("!=")) {
            operator = "!=";
            int idx = condition.indexOf("!=");
            left = condition.substring(0, idx).trim();
            right = condition.substring(idx + 2).trim();
        } else if (condition.contains(">=")) {
            operator = ">=";
            int idx = condition.indexOf(">=");
            left = condition.substring(0, idx).trim();
            right = condition.substring(idx + 2).trim();
        } else if (condition.contains("<=")) {
            operator = "<=";
            int idx = condition.indexOf("<=");
            left = condition.substring(0, idx).trim();
            right = condition.substring(idx + 2).trim();
        } else if (condition.contains(">")) {
            operator = ">";
            int idx = condition.indexOf(">");
            left = condition.substring(0, idx).trim();
            right = condition.substring(idx + 1).trim();
        } else if (condition.contains("<")) {
            operator = "<";
            int idx = condition.indexOf("<");
            left = condition.substring(0, idx).trim();
            right = condition.substring(idx + 1).trim();
        } else {
            return false;
        }

        // 解析左侧变量值 (可能是变量引用或字面量)
        left = resolveVariableValue(left);
        // 右侧通常被视为字面量，去除引号
        right = removeQuotes(right);

        // 尝试按数字比较
        try {
            double leftNum = Double.parseDouble(left);
            double rightNum = Double.parseDouble(right);
            return compareNumbers(leftNum, rightNum, operator);
        } catch (NumberFormatException ignored) {
        }

        // 按字符串比较
        return compareStrings(left, right, operator);
    }

    /**
    * 解析变量值：如果是引号包裹则去引号，否则尝试通过 Resolver 解析
    */
    private String resolveVariableValue(String value) {
        if (value.startsWith("'") || value.startsWith("\"")) {
            return removeQuotes(value);
        }
        if (resolver != null) {
            String resolved = resolver.resolvePlaceholder(value);
            if (resolved != null) {
                return resolved;
            }
        }
        return value;
    }

    /**
    * 数值比较辅助方法
    */
    private boolean compareNumbers(double left, double right, String operator) {
        return switch (operator) {
            case ">" -> left > right;
            case "<" -> left < right;
            case ">=" -> left >= right;
            case "<=" -> left <= right;
            case "==" -> left == right;
            case "!=" -> left != right;
            default -> false;
        };
    }

    /**
    * 字符串比较辅助方法
    */
    private boolean compareStrings(String left, String right, String operator) {
        return switch (operator) {
            case "==" -> left.equals(right);
            case "!=" -> !left.equals(right);
            default -> false;
        };
    }

    /**
    * 解析数组或 Map 的方括号访问
    *
    * @param expr 表达式，如 "myList[0]" 或 "myMap[key]"
    * @return 解析结果
    */
    private String resolveArrayAccess(String expr) {
        try {
            int bracketIndex = expr.indexOf('[');
            String key = expr.substring(0, bracketIndex);
            String index = expr.substring(bracketIndex + 1, expr.length() - 1);

            if (resolver == null) {
                return null;
            }

            // 获取 key 对应的值
            String value = resolver.resolvePlaceholder(key);
            if (value == null) {
                return null;
            }

            // 如果是 List 格式 (以 [ 开头，] 结尾)
            if (value.startsWith("[") && value.endsWith("]")) {
                return resolveArrayIndex(value, index);
            }

            // 如果是 Map 格式 (以 { 开头，} 结尾)
            if (value.startsWith("{") && value.endsWith("}")) {
                return resolveMapAccess(value, index);
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * 从字符串形式的列表中根据索引取值
    */
    private String resolveArrayIndex(String arrayStr, String indexStr) {
        try {
            int index = Integer.parseInt(indexStr);
            String content = arrayStr.substring(1, arrayStr.length() - 1);
            String[] items = content.split(",");
            if (index >= 0 && index < items.length) {
                return removeQuotes(items[index].trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /**
    * 从字符串形式的 Map 中根据 Key 取值
    */
    private String resolveMapAccess(String mapStr, String key) {
        String content = mapStr.substring(1, mapStr.length() - 1);
        String[] entries = content.split(",");
        for (String entry : entries) {
            String[] kv = entry.split(":");
            if (kv.length == 2) {
                String k = removeQuotes(kv[0].trim());
                if (k.equals(key)) {
                    return removeQuotes(kv[1].trim());
                }
            }
        }
        return null;
    }

    /**
    * 移除字符串首尾的单引号或双引号
    */
    private String removeQuotes(String str) {
        if (str == null) {
            return null;
        }
        if ((str.startsWith("'") && str.endsWith("'")) || (str.startsWith("\"") && str.endsWith("\""))) {
            return str.substring(1, str.length() - 1);
        }
        return str;
    }

    // ==================== 内置函数实现 ====================

    /**
    * 获取当前时间格式化后的字符串
    * @param format 日期格式，默认为 "yyyy-MM-dd HH:mm:ss"
    */
    private String nowFunction(String format) {
        if (format == null || format.isEmpty()) {
            format = "yyyy-MM-dd HH:mm:ss";
        }
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(format));
    }

    /**
    * 生成 UUID
    */
    private String uuidFunction(String args) {
        return UUID.randomUUID().toString();
    }

    /**
    * 生成随机整数
    * @param args 上界 (默认 100)
    */
    private String randomFunction(String args) {
        int bound = 100;
        try {
            String value = removeQuotes(args);
            bound = Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }
        return String.valueOf(new Random().nextInt(bound));
    }

    /**
    * 转大写
    */
    private String upperFunction(String args) {
        String value = removeQuotes(args);
        return value != null ? value.toUpperCase() : "";
    }

    /**
    * 转小写
    */
    private String lowerFunction(String args) {
        String value = removeQuotes(args);
        return value != null ? value.toLowerCase() : "";
    }

    /**
    * 获取字符串长度
    */
    private String lengthFunction(String args) {
        String value = removeQuotes(args);
        return value != null ? String.valueOf(value.length()) : "0";
    }


    // ==================== 链式配置方法 ====================
    /**
    * 启用函数功能
    */
    public PlaceholderSupport functionEnable() {
        this.functionEnabled = true;
        return this;
    }

    /**
    * 禁用函数功能
    */
    public PlaceholderSupport functionDisable() {
        this.functionEnabled = false;
        return this;
    }

    /**
    * 启用三元表达式功能
    */
    public PlaceholderSupport ternary() {
        this.ternaryEnabled = true;
        return this;
    }

    /**
    * 禁用三元表达式功能
    */
    public PlaceholderSupport ternaryDisable() {
        this.ternaryEnabled = false;
        return this;
    }

    /**
    * 启用数组/Map 访问功能
    */
    public PlaceholderSupport arrayAccess() {
        this.arrayAccessEnabled = true;
        return this;
    }

    /**
    * 禁用数组/Map 访问功能
    */
    public PlaceholderSupport arrayAccessDisable() {
        this.arrayAccessEnabled = false;
        return this;
    }

    /**
    * 设置忽略不可解析的占位符
    */
    public PlaceholderSupport ignoreUnresolvable() {
        this.ignoreUnresolvablePlaceholders = true;
        return this;
    }
}
