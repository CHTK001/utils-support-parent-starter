"""
批量扫描 example-starter 中所有 Example 文件的 P3C 违规项。
"""
import os, re, sys
from collections import Counter

sys.stdout.reconfigure(encoding='utf-8')

EXAMPLE_ROOT = os.path.join('utils-support-extra-parent', 'utils-support-example-starter',
                            'src', 'main', 'java', 'com', 'chua', 'example')

VIOLATIONS = []

def rel(path):
    return os.path.relpath(path, EXAMPLE_ROOT).replace('\\', '/')

def add(file, line, rule, severity, desc):
    VIOLATIONS.append((file, line, rule, severity, desc))

def check_file(path):
    content = open(path, encoding='utf-8').read()
    lines = content.split('\n')
    fname = rel(path)

    # ---- 体系预检 ----
    base = os.path.basename(path)
    if not base.endswith('Example.java'):
        add(fname, 1, '体系', '[强制]', f'文件名不以 Example 结尾: {base}')
        return

    class_match = re.search(r'(?:public\s+)?(?:abstract\s+)?(?:final\s+)?class\s+(\w+)', content)
    if class_match:
        cls_name = class_match.group(1)
        if not cls_name.endswith('Example'):
            add(fname, class_match.start(1)+1, '体系', '[强制]',
                f'类名不以 Example 结尾: {cls_name}')

    has_main = bool(re.search(r'public\s+static\s+void\s+main\s*\(', content))
    is_abstract = bool(re.search(r'abstract\s+class', content))
    is_base = 'BaseExample' in content or 'SimpleEngineDataSource' in base
    if not has_main and not is_abstract and not is_base:
        add(fname, 1, '体系', '[强制]', '缺少 public static void main 入口')

    if '@Test' in content or 'assertEquals' in content or 'assertThat' in content:
        add(fname, 1, '体系', '[强制]', '发现 JUnit 注解或断言（@Test/assertEquals/assertThat）')

    # 多行代码/注释压缩
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if s.startswith('//') and s.count('//') >= 2:
            add(fname, i, '格式', '[强制]', f'同行堆叠多条注释: {s[:80]}')
        if not s.startswith(('import ', 'package ', '//', '*', '*/', '@')):
            in_str = False
            str_ch = None
            semi = 0
            for ci, ch in enumerate(s):
                if not in_str and ch in ('"', "'"):
                    in_str = True; str_ch = ch
                elif in_str and ch == str_ch and (ci == 0 or s[ci-1] != '\\'):
                    in_str = False
                elif not in_str and ch == ';':
                    semi += 1
            if semi >= 2:
                add(fname, i, '格式', '[强制]', f'多语句压缩一行: {s[:80]}')

    # System.out.println
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if ('System.out.println' in s or 'System.err.println' in s):
            if '[PASS]' not in s and '[FAIL]' not in s:
                add(fname, i, '日志', '[强制]', '使用 System.out.println，应改用 SLF4J log.info()')

    # System.exit 位置
    in_main = False
    brace_depth = 0
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if re.search(r'public\s+static\s+void\s+main\s*\(', s):
            in_main = True
        brace_depth += s.count('{') - s.count('}')
        if '{' in s and '}' not in s:
            pass
        if 'System.exit(' in s and not in_main:
            add(fname, i, '体系', '[强制]', 'System.exit() 不在 main 方法中')

    # 直接反射 API
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if s.startswith('//') or s.startswith('*'): continue
        for pat in ['Class.forName(', '.getMethod(', '.getField(', '.invoke(',
                     'Proxy.newProxyInstance', 'LambdaMetafactory', 'MethodHandles.lookup']:
            if pat in s and 'ReflectUtils' not in fname:
                add(fname, i, '1.10', '[强制]', f'直接使用 java.lang.reflect API: {s[:80]}')
                break

    # 空实现方法检测
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if s.startswith('//') or s.startswith('*'): continue
        # 检测 return null/0/false/emptyList 等
        if re.search(r'return\s+(null|0|false|\'\');', s):
            # 确认不是 @Override
            prev_ctx = ''.join(lines[max(0,i-5):i])
            if '@Override' not in prev_ctx:
                add(fname, i, '1.9', '[强制]', f'非 Override 方法直接 return null/0/false: {s[:60]}')
        if re.search(r'return\s+Collections\.empty(List|Map|Set);', s):
            prev_ctx = ''.join(lines[max(0,i-5):i])
            if '@Override' not in prev_ctx:
                add(fname, i, '1.9', '[强制]', f'非 Override 方法返回 empty collection: {s[:60]}')

    # 类 Javadoc 检查
    class_match2 = re.search(r'/\*\*(.*?)\*/', content, re.DOTALL)
    if not class_match2:
        add(fname, 1, '1.8', '[强制]', '类缺少 Javadoc 注释')
    else:
        javadoc = class_match2.group(1)
        if '@author' not in javadoc or '@since' not in javadoc:
            add(fname, class_match2.start(1)+1, '1.8', '[强制]',
                '类 Javadoc 缺少 @author 或 @since')

    # 魔法值（同一字符串出现5次以上）
    string_literals = re.findall(r'"([^"]{3,})"', content)
    lit_counts = Counter(string_literals)
    for lit, count in lit_counts.items():
        if count >= 5 and not lit.startswith('$') and not lit.startswith('/'):
            add(fname, 1, '1.2', '[强制]',
                f'魔法值 "{lit}" 出现 {count} 次，应提取为常量')

    # long 字面量小写 l
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if s.startswith('//'): continue
        if re.search(r'\b\d+l\b', s):
            add(fname, i, '1.2', '[强制]', f'long 字面量使用小写 l: {s[:60]}')

    # SimpleDateFormat static
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if 'static' in s and 'SimpleDateFormat' in s:
            add(fname, i, '并发', '[强制]', 'SimpleDateFormat 不应声明为 static 变量')

    # Executors 工厂方法
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if re.search(r'Executors\.new(Fixed|Cached|Single|NewFixed)', s):
            add(fname, i, '并发', '[强制]', f'禁止使用 Executors 工厂方法: {s[:80]}')

    # static 可变字段
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if s.startswith('//'): continue
        if re.search(r'static\s+(?!final\b)(?!String\b)(?!int\b)(?!long\b)(?!double\b)(?!boolean\b)\w+\s+\w+\s*=', s):
            if re.search(r'(List|Map|Set|StringBuilder|ArrayList|HashMap|HashSet)\s*<', s):
                add(fname, i, '静态字段', '[强制]', f'static 非 final 可变字段: {s[:80]}')

    # 裸类型
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if s.startswith('//') or s.startswith('import'): continue
        if re.search(r'\b(List|Map|Set|Collection)\s+\w+\s*=', s) and '<' not in s:
            add(fname, i, '泛型', '[强制]', f'裸类型 (raw type): {s[:80]}')

    # 工具类缺少 private 构造
    class_matches = re.findall(r'public\s+class\s+(\w+)\s*\{', content)
    for cls_name in class_matches:
        if re.search(rf'private\s+{re.escape(cls_name)}\s*\(\s*\)', content):
            continue
        has_static = bool(re.search(r'public\s+static', content))
        has_instance = bool(re.search(r'public\s+(?!static\b)[\w<>\[\],\s]+\s+\w+\s*\(', content))
        if has_static and not has_instance:
            add(fname, 1, '工具类构造', '[强制]', f'纯静态工具类 {cls_name} 缺少 private 构造函数')

    # 全限定名静态调用（未使用 import static）
    static_imports = re.findall(r'import\s+static\s+([\w.]+)\.(?:\*|\w+)', content)
    has_fq_call = bool(re.search(r'com\.chua\.common\.support\.utils\.\w+\.\w+\(', content))
    if has_fq_call and not static_imports:
        add(fname, 1, 'Java25简化', '[强制]', '未使用 import static，使用全限定名调用静态方法')

    # Lombok 冲突
    if '@Data' in content or '@Getter' in content or '@Slf4j' in content:
        if re.search(r'public\s+boolean\s+equals\s*\(', content):
            add(fname, 1, 'Lombok', '[强制]', f'{os.path.basename(path)} 使用 @Data/@Getter 但同时手写 equals()')
        if re.search(r'public\s+int\s+hashCode\s*\(', content):
            add(fname, 1, 'Lombok', '[强制]', f'{os.path.basename(path)} 使用 @Data/@Getter 但同时手写 hashCode()')
        if re.search(r'public\s+String\s+toString\s*\(', content):
            add(fname, 1, 'Lombok', '[强制]', f'{os.path.basename(path)} 使用 @Data 但同时手写 toString()')

    # SQL 拼接
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if s.startswith('//') or 'import' in s: continue
        if re.search(r'(SELECT|INSERT|UPDATE|DELETE)', s, re.IGNORECASE) and '+' in s:
            add(fname, i, 'SQL', '[强制]', f'SQL 字符串拼接: {s[:80]}')
        if re.search(r'String\.format\s*\(\s*["\'].*(SELECT|INSERT|UPDATE|DELETE)', s, re.IGNORECASE):
            add(fname, i, 'SQL', '[强制]', f'SQL 通过 String.format 拼接: {s[:80]}')

    # 敏感信息硬编码
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if s.startswith('//'): continue
        if re.search(r'(password|passwd|secret|api_key|apiKey|token|privateKey)\s*=\s*["\'][^"\']{3,}["\']', s, re.IGNORECASE):
            if '${' not in s:
                add(fname, i, '安全', '[强制]', f'疑似硬编码敏感信息: {s[:80]}')

    # Serializable 缺少 serialVersionUID
    serializable_classes = re.findall(r'class\s+(\w+)\s+implements\s+.*?Serializable', content)
    for sc in serializable_classes:
        if 'serialVersionUID' not in content:
            add(fname, 1, '序列化', '[强制]', f'实现 Serializable 的类 {sc} 缺少 serialVersionUID')

    # 循环中创建线程
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if 'new Thread(' in s:
            prev = ''.join(lines[max(0,i-10):i-1])
            if 'for' in prev or 'while' in prev:
                add(fname, i, '并发', '[强制]', '循环中创建线程，应使用线程池')

    # 空 catch
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if re.search(r'catch\s*\(\s*\w+\s+\w+\s*\)\s*\{\s*\}', s):
            add(fname, i, '异常控制流', '[强制]', f'空 catch 块: {s[:60]}')

    # 资源未关闭（try-with-resources 检查）
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if re.search(r'(InputStream|Reader|Writer|Connection|Channel|Client|Socket)\s+\w+\s*=\s*new\s+', s):
            # 检查是否在 try-with-resources 中（前几行有 try (）
            ctx = ''.join(lines[max(0,i-5):i])
            if 'try (' not in ctx and 'try(' not in ctx:
                add(fname, i, '资源关闭', '[强制]', f'Closeable 资源未使用 try-with-resources: {s[:80]}')

    # instanceof 硬编码分发
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if s.startswith('//'): continue
        # instanceof + 强转的模式
        if re.search(r'instanceof\s+\w+\s+\w+\s*\)', s) or \
           re.search(r'\(\s*\w+\s*\)\s*[A-Z]\w+', s):
            # 简单启发：跳过明确的 instanceof 类型判断（如 instanceof String s）
            if 'instanceof' in s and 'var' not in s and 'switch' not in s:
                pass  # 留作推荐，避免误报

    # 方法参数数量
    for i, line in enumerate(lines, 1):
        s = line.strip()
        params = re.search(r'public\s+(?:static\s+)?[\w<>\[\],\s]+\s+(\w+)\s*\(([^)]*)\)', s)
        if params:
            mname = params.group(1)
            args_str = params.group(2).strip()
            if args_str and mname != 'main':
                args = [a.strip() for a in args_str.split(',') if a.strip()]
                if len(args) > 5:
                    add(fname, i, '参数数量', '[推荐]',
                        f'方法 {mname}({len(args)} 参数) 应提取为 record 参数对象')

    # 注释完整性：字段无注释
    for i, line in enumerate(lines, 1):
        s = line.strip()
        if re.match(r'private\s+(?!static\s+final|static\s+)([\w<>\[\],\s]+)\s+\w+\s*;', s):
            prev = lines[i-2].strip() if i > 1 else ''
            if not prev.startswith('//') and not prev.startswith('*') and not prev.startswith('/**'):
                add(fname, i, '1.8', '[强制]', f'字段缺少行内注释: {s[:60]}')


def main():
    if not os.path.exists(EXAMPLE_ROOT):
        print(f'ERROR: not found {EXAMPLE_ROOT}')
        sys.exit(1)

    count = 0
    for root, dirs, files in os.walk(EXAMPLE_ROOT):
        for f in sorted(files):
            if f.endswith('Example.java'):
                check_file(os.path.join(root, f))
                count += 1

    print(f'=== 扫描完成: {count} 个 Example 文件 ===\n')

    total = len(VIOLATIONS)
    forced = sum(1 for v in VIOLATIONS if '[强制]' in v[2])
    recommended = sum(1 for v in VIOLATIONS if '[推荐]' in v[2])

    print(f'总违规数: {total}  （[强制] {forced} / [推荐] {recommended}）\n')

    by_file = {}
    for file, line, rule, severity, desc in VIOLATIONS:
        by_file.setdefault(file, []).append((line, rule, severity, desc))

    for fname in sorted(by_file.keys()):
        items = by_file[fname]
        print(f'--- {fname} ({len(items)} 条) ---')
        for line, rule, sev, desc in sorted(items, key=lambda x: x[0]):
            print(f'  L{line:4d} [{rule:10s}] {sev}  {desc}')
        print()

    by_rule = {}
    for file, line, rule, sev, desc in VIOLATIONS:
        by_rule.setdefault(rule, []).append((file, line, desc))

    print('=== 按规则分组 ===')
    for rule in sorted(by_rule.keys()):
        items = by_rule[rule]
        sevs = set(v[2] for v in VIOLATIONS if v[2] == rule)
        print(f'\n[{rule}] ({len(items)} 条):')
        for file, line, desc in items[:5]:
            print(f'  {file}:{line}  {desc}')
        if len(items) > 5:
            print(f'  ... +{len(items)-5} more')


if __name__ == '__main__':
    main()
