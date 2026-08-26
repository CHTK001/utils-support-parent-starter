"""
Apply P3C fixes safely to example-starter files.
Order: 1. Add @Slf4j if using log OR System.out.println
       2. Add private constructor to pure static classes
       3. Fix System.out.println -> log.info
       4. Fix empty catch blocks
"""
import os, re, sys
sys.stdout.reconfigure(encoding='utf-8')

EXAMPLE_ROOT = os.path.join('utils-support-extra-parent', 'utils-support-example-starter',
                            'src', 'main', 'java', 'com', 'chua', 'example')

stats = {'slf4j': 0, 'constructor': 0, 'sysout': 0, 'catch': 0}

def fix_file(path):
    content = open(path, encoding='utf-8').read()
    orig = content
    changed = False

    # 1. Add @Slf4j if using log OR System.out.println
    has_log = bool(re.search(r'\blog\.(info|error|warn)\(', content))
    has_sysout = bool(re.search(r'System\.out\.println', content))
    has_slf4j = '@Slf4j' in content
    has_import = 'import lombok.extern.slf4j.Slf4j' in content
    
    if (has_log or has_sysout) and (not has_slf4j or not has_import):
        if not has_import:
            # Add import after last import
            last_import = max((m.end() for m in re.finditer(r'import [^\n]+;\n', content)), default=0)
            if last_import > 0:
                content = content[:last_import] + 'import lombok.extern.slf4j.Slf4j;\n' + content[last_import:]
                changed = True
        if not has_slf4j:
            # Add @Slf4j before class declaration
            class_match = re.search(r'(\s*)(public\s+(?:abstract\s+)?(?:final\s+)?class\s+\w+)', content)
            if class_match:
                insert_pos = class_match.start(2)
                content = content[:insert_pos] + '@Slf4j\n' + content[insert_pos:]
                changed = True
        if changed:
            stats['slf4j'] += 1

    # 2. Add private constructor to pure static classes
    class_match = re.search(r'public\s+class\s+(\w+)', content)
    if class_match:
        cls_name = class_match.group(1)
        has_static = bool(re.search(r'public\s+static', content))
        has_instance = bool(re.search(r'public\s+(?!static\b)[\w<>\[\],\s]+\s+\w+\s*\(', content))
        has_ctor = bool(re.search(rf'private\s+{re.escape(cls_name)}\s*\(', content))
        if has_static and not has_instance and not has_ctor:
            open_brace = content.find('{', class_match.end())
            if open_brace > 0:
                insert_pos = open_brace + 1
                ctor = f'\n    private {cls_name}() {{ }}\n'
                content = content[:insert_pos] + ctor + content[insert_pos:]
                changed = True
                stats['constructor'] += 1

    # 3. Fix System.out.println -> log.info (NOT printf)
    def replace_sysout(m):
        stripped = m.group(0).strip()
        if '[PASS]' in stripped or '[FAIL]' in stripped:
            return m.group(0)
        return stripped.replace('System.out.println(', 'log.info(')
    new_content = re.sub(r'System\.out\.println(?!\w)\((.+?)\);', replace_sysout, content)
    if new_content != content:
        stats['sysout'] += 1
        content = new_content
        changed = True

    # 4. Fix empty catch blocks
    def fix_empty_catch(m):
        prefix = m.group(1)
        exc_match = re.search(r'catch\s*\(\s*\w+\s+(\w+)\s*\)', prefix)
        var_name = exc_match.group(1) if exc_match else 'e'
        return prefix + f'\n            log.warn("Caught: {{}}", {var_name}.getMessage());\n        ' + m.group(2)
    new_content = re.sub(r'(catch\s*\([^)]+\)\s*\{)(\s*\})', fix_empty_catch, content)
    if new_content != content:
        stats['catch'] += 1
        content = new_content
        changed = True

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
                    rel = os.path.relpath(path, EXAMPLE_ROOT).replace('\\', '/')
                    changes = []
                    if stats.get('slf4j', 0) > 0: changes.append('slf4j')
                    if stats.get('constructor', 0) > 0: changes.append('ctor')
                    if stats.get('sysout', 0) > 0: changes.append('sysout')
                    if stats.get('catch', 0) > 0: changes.append('catch')
                    print(f'  {rel}: {",".join(changes)}')
    print(f"\nTotal: {count} files")
    for k, v in stats.items():
        print(f'  {k}: {v}')


if __name__ == '__main__':
    main()
