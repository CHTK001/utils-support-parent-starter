"""Check and fix format violations: multi-statement single-line."""
import os, re, sys
sys.stdout.reconfigure(encoding='utf-8')

EXAMPLE_ROOT = os.path.join('utils-support-extra-parent', 'utils-support-example-starter',
                            'src', 'main', 'java', 'com', 'chua', 'example')

FIXED = 0
TOTAL = 0

for root, dirs, files in os.walk(EXAMPLE_ROOT):
    for fn in sorted(files):
        if not fn.endswith('Example.java'):
            continue
        path = os.path.join(root, fn)
        c = open(path, encoding='utf-8').read()
        lines = c.split('\n')
        new_lines = []
        file_fixed = False
        for i, line in enumerate(lines):
            s = line.strip()
            if s.startswith('//') or s.startswith('*') or s.startswith('import') or s.startswith('package'):
                new_lines.append(line)
                continue
            # Count semicolons outside strings
            in_str = False
            str_ch = None
            semi = 0
            for ci, ch in enumerate(s):
                if not in_str and ch in ('"', "'"):
                    in_str = True
                    str_ch = ch
                elif in_str and ch == str_ch and (ci == 0 or s[ci-1] != '\\'):
                    in_str = False
                elif not in_str and ch == ';':
                    semi += 1
            if semi >= 2:
                TOTAL += 1
                file_fixed = True
                # Split by semicolons
                parts = []
                in_str2 = False
                str_ch2 = None
                current = []
                for ci, ch in enumerate(s):
                    if not in_str2 and ch in ('"', "'"):
                        in_str2 = True
                        str_ch2 = ch
                        current.append(ch)
                    elif in_str2 and ch == str_ch2 and (ci == 0 or s[ci-1] != '\\'):
                        in_str2 = False
                        current.append(ch)
                    elif not in_str2 and ch == ';':
                        parts.append(''.join(current).strip())
                        current = []
                    else:
                        current.append(ch)
                if current:
                    parts.append(''.join(current).strip())

                indent = line[:len(line) - len(line.lstrip())]
                for part in parts:
                    if part:
                        new_lines.append(indent + part)
                FIXED += 1
            else:
                new_lines.append(line)
        if file_fixed:
            open(path, 'w', encoding='utf-8').write('\n'.join(new_lines))
            rel = os.path.relpath(path, EXAMPLE_ROOT).replace('\\', '/')
            print(f'Fixed: {rel}')

print(f'\nTotal single-line multi-statement: {TOTAL}, fixed: {FIXED}')
