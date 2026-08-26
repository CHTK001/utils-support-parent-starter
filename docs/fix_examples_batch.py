"""
Fix common issues across all Example Java files:
1. Add private constructor to pure static classes
2. Fix empty catch blocks (add log.warn)
3. Fix System.out.println -> log.info (excluding [PASS]/[FAIL] lines)
"""
import os, re, sys

sys.stdout.reconfigure(encoding='utf-8')

EXAMPLE_ROOT = os.path.join('utils-support-extra-parent', 'utils-support-example-starter',
                            'src', 'main', 'java', 'com', 'chua', 'example')

FIXED = {'constructors': 0, 'catch_blocks': 0, 'sysout': 0, 'exit': 0}

def fix_file(path):
    content = open(path, encoding='utf-8').read()
    orig = content
    fname = os.path.relpath(path, EXAMPLE_ROOT).replace('\\', '/')
    changed = False

    # === 1. Add private constructor to pure static classes ===
    # Check if class has only static methods and no private ctor
    class_match = re.search(r'public\s+class\s+(\w+)', content)
    if class_match:
        cls_name = class_match.group(1)
        has_static = bool(re.search(r'public\s+static', content))
        has_instance = bool(re.search(r'public\s+(?!static\b)[\w<>\[\],\s]+\s+\w+\s*\(', content))
        has_private_ctor = bool(re.search(rf'private\s+{re.escape(cls_name)}\s*\(', content))
        if has_static and not has_instance and not has_private_ctor:
            # Find the opening brace of the class and insert private ctor
            open_brace = content.find('{', class_match.end())
            if open_brace > 0:
                insert_pos = open_brace + 1
                ctor = f'\n    private {cls_name}() {{ }}\n'
                content = content[:insert_pos] + ctor + content[insert_pos:]
                FIXED['constructors'] += 1
                changed = True

    # === 2. Fix empty catch blocks ===
    # Pattern: catch (...) {} or catch (...) { ignored } with no statements
    empty_catch_pattern = re.compile(
        r'(catch\s*\([^)]+\)\s*\{)(\s*\})',
        re.DOTALL
    )
    def fix_empty_catch(m):
        prefix = m.group(1)
        # Get the exception variable name
        exc_match = re.search(r'catch\s*\(\s*\w+\s+(\w+)\s*\)', prefix)
        var_name = exc_match.group(1) if exc_match else 'e'
        return prefix + f'\n            log.warn("Caught: {{}}", {var_name}.getMessage());\n        ' + m.group(2)
    
    new_content = empty_catch_pattern.sub(fix_empty_catch, content)
    if new_content != content:
        FIXED['catch_blocks'] += 1
        changed = True
        content = new_content

    # === 3. Fix System.out.println -> log.info (exclude [PASS]/[FAIL] structured output) ===
    def replace_sysout(m):
        line = m.group(0)
        stripped = line.strip()
        # Keep [PASS]/[FAIL] lines as-is (they're structured output)
        if '[PASS]' in stripped or '[FAIL]' in stripped:
            return line
        # Replace System.out.println with log.info
        inner = stripped.replace('System.out.println(', '').rstrip(');')
        return line.replace(stripped, f'log.info({inner})')
    
    new_content = re.sub(r'System\.out\.println\((.+?)\);', replace_sysout, content)
    if new_content != content:
        diff = len(content) - len(new_content)
        FIXED['sysout'] += max(1, diff // 10)  # approximate count
        changed = True
        content = new_content

    if changed:
        open(path, 'w', encoding='utf-8').write(content)
        return True
    return False


def main():
    count = 0
    for root, dirs, files in os.walk(EXAMPLE_ROOT):
        for f in sorted(files):
            if f.endswith('Example.java'):
                path = os.path.join(root, f)
                if fix_file(path):
                    count += 1

    print(f"=== 修复完成 ===")
    print(f"修改文件数: {count}")
    print(f"  添加private构造: {FIXED['constructors']}")
    print(f"  修复空catch块: {FIXED['catch_blocks']}")
    print(f"  替换System.out.println: {FIXED['sysout']}")


if __name__ == '__main__':
    main()
