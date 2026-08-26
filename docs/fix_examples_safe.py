"""
Safe fixes for Example Java files:
1. Add private constructor to pure static classes
2. Fix empty catch blocks (add log.warn)
3. Fix System.out.println -> log.info (excluding [PASS]/[FAIL] structured output)
NOT doing: magic value extraction (breaks syntax), format fixes (breaks syntax)
"""
import os, re, sys

sys.stdout.reconfigure(encoding='utf-8')

EXAMPLE_ROOT = os.path.join('utils-support-extra-parent', 'utils-support-example-starter',
                            'src', 'main', 'java', 'com', 'chua', 'example')

FIXED = {'constructors': 0, 'catch_blocks': 0, 'sysout': 0}

def fix_file(path):
    content = open(path, encoding='utf-8').read()
    orig = content
    fname = os.path.relpath(path, EXAMPLE_ROOT).replace('\\', '/')

    # === 1. Add private constructor to pure static classes ===
    class_match = re.search(r'public\s+class\s+(\w+)', content)
    if class_match:
        cls_name = class_match.group(1)
        has_static = bool(re.search(r'public\s+static', content))
        has_instance = bool(re.search(r'public\s+(?!static\b)[\w<>\[\],\s]+\s+\w+\s*\(', content))
        has_private_ctor = bool(re.search(rf'private\s+{re.escape(cls_name)}\s*\(', content))
        if has_static and not has_instance and not has_private_ctor:
            open_brace = content.find('{', class_match.end())
            if open_brace > 0:
                insert_pos = open_brace + 1
                ctor = f'\n    private {cls_name}() {{ }}\n'
                content = content[:insert_pos] + ctor + content[insert_pos:]
                FIXED['constructors'] += 1

    # === 2. Fix empty catch blocks ===
    def fix_empty_catch(m):
        prefix = m.group(1)
        exc_match = re.search(r'catch\s*\(\s*\w+\s+(\w+)\s*\)', prefix)
        var_name = exc_match.group(1) if exc_match else 'e'
        return prefix + f'\n            log.warn("Caught: {{}}", {var_name}.getMessage());\n        ' + m.group(2)

    new_content = re.sub(
        r'(catch\s*\([^)]+\)\s*\{)(\s*\})',
        fix_empty_catch,
        content
    )
    if new_content != content:
        FIXED['catch_blocks'] += 1
        content = new_content

    # === 3. Fix System.out.println -> log.info ===
    def replace_sysout(m):
        line = m.group(0)
        stripped = line.strip()
        if '[PASS]' in stripped or '[FAIL]' in stripped:
            return line
        inner = stripped.replace('System.out.println(', '').rstrip(');')
        return line.replace(stripped, f'log.info({inner})')

    new_content = re.sub(r'System\.out\.println\((.+?)\);', replace_sysout, content)
    if new_content != content:
        FIXED['sysout'] += 1
        content = new_content

    if content != orig:
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

    print(f"Fixed {count} files:")
    print(f"  Private constructors: {FIXED['constructors']}")
    print(f"  Empty catch blocks: {FIXED['catch_blocks']}")
    print(f"  System.out.println: {FIXED['sysout']}")


if __name__ == '__main__':
    main()
