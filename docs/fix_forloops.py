"""
Fix for-loop format violations: expand single-line for loops.
Pattern: for (...) { ... } on single line or with minimal body.
"""
import os, re, sys
sys.stdout.reconfigure(encoding='utf-8')

EXAMPLE_ROOT = os.path.join('utils-support-extra-parent', 'utils-support-example-starter',
                            'src', 'main', 'java', 'com', 'chua', 'example')

FIXED = 0

def fix_file(path):
    global FIXED
    content = open(path, encoding='utf-8').read()
    orig = content
    lines = content.split('\n')
    result = []
    i = 0
    while i < len(lines):
        line = lines[i]
        s = line.strip()
        # Match: for (...) { ... } single line
        m = re.match(r'^(\s*)for\s*\(([^)]+)\)\s*\{\s*(.+?)\s*\}\s*$', s)
        if m:
            indent = m.group(1)
            params = m.group(2)
            body = m.group(3)
            result.append(f'{indent}for ({params}) {{')
            result.append(f'{indent}    {body}')
            result.append(f'{indent}}}')
            FIXED += 1
            i += 1
            continue
        # Match: for (...) { ... } with body on next line but still one-liner pattern
        # e.g., for (int i = 0; i < n; i++) { \n   ... \n }
        m2 = re.match(r'^(\s*)for\s*\(([^)]+)\)\s*\{\s*$', s)
        if m2 and i + 1 < len(lines):
            next_line = lines[i + 1].strip()
            # Check if next non-empty line is just }
            next_idx = i + 1
            while next_idx < len(lines) and not lines[next_idx].strip():
                next_idx += 1
            if next_idx < len(lines) and lines[next_idx].strip() == '}':
                # Single-line for with empty body - expand it
                indent = m2.group(1)
                params = m2.group(2)
                result.append(f'{indent}for ({params}) {{')
                result.append(f'{indent}    // empty loop body')
                result.append(f'{indent}}}')
                FIXED += 1
                i += 2
                continue
        result.append(line)
        i += 1
    
    new_content = '\n'.join(result)
    if new_content != orig:
        open(path, 'w', encoding='utf-8').write(new_content)
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
    print(f"Fixed for-loop format in {count} files, {FIXED} expansions")

if __name__ == '__main__':
    main()
