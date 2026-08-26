"""
Fix magic values: extract repeated strings to constants.
Only fixes strings appearing 5+ times in the same file.
"""
import os, re, sys
from collections import Counter

sys.stdout.reconfigure(encoding='utf-8')

EXAMPLE_ROOT = os.path.join('utils-support-extra-parent', 'utils-support-example-starter',
                            'src', 'main', 'java', 'com', 'chua', 'example')

FIXED = 0

def safe_const_name(s):
    """Convert a string literal to a valid Java constant name."""
    name = re.sub(r'[^a-zA-Z0-9]', '_', s).strip('_')
    if not name or name[0].isdigit():
        name = 'V' + name
    if len(name) > 30:
        name = name[:30]
    return name.upper()

def fix_file(path):
    global FIXED
    content = open(path, encoding='utf-8').read()
    orig = content

    # Find all string literals (3+ chars, not starting with / or $)
    strings = re.findall(r'"([^"]{3,})"', content)
    lit_counts = Counter(s for s in strings
                         if not s.startswith('/') and not s.startswith('$')
                         and not s.startswith('http') and not s.startswith('com.'))

    repeats = [(lit, cnt) for lit, cnt in lit_counts.items() if cnt >= 5]
    if not repeats:
        return False

    repeats.sort(key=lambda x: -x[1])
    top_repeats = repeats[:5]  # Limit to top 5 per file

    # Insert constants after class declaration
    class_match = re.search(r'(public\s+(?:abstract\s+)?(?:final\s+)?class\s+(\w+))\s*\{', content)
    if not class_match:
        return False

    insert_pos = content.find('{', class_match.end()) + 1
    const_lines = []
    replacements = []

    for lit, cnt in top_repeats:
        const_name = safe_const_name(lit)
        # Escape for Java string
        java_escaped = lit.replace('\\', '\\\\').replace('"', '\\"')
        const_decl = f'\n    /** Repeated {cnt} times in this file */\n    private static final String {const_name} = "{java_escaped}";'
        const_lines.append(const_decl)
        replacements.append((lit, const_name))

    # Insert constants
    insert_text = ''.join(const_lines)
    content = content[:insert_pos] + insert_text + content[insert_pos:]

    # Replace occurrences (skip the constant declarations themselves)
    for lit, const_name in replacements:
        # Count how many replacements we need (total occurrences minus 1 for the declaration)
        total = content.count(f'"{lit}"')
        if total <= 1:
            continue
        # Replace all but one (the declaration)
        # Find all positions of the literal
        positions = []
        search_from = 0
        while True:
            pos = content.find(f'"{lit}"', search_from)
            if pos < 0:
                break
            positions.append(pos)
            search_from = pos + 1
        # Skip the first occurrence (constant declaration)
        for pos in positions[1:]:
            content = content[:pos] + const_name + content[pos + len(lit) + 2:]  # +2 for quotes
        FIXED += 1

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
    print(f"Fixed magic values in {count} files, {FIXED} extractions")

if __name__ == '__main__':
    main()
