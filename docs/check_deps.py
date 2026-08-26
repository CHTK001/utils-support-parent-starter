import os, re

example_root = os.path.join('utils-support-extra-parent', 'utils-support-example-starter', 'src', 'main', 'java', 'com', 'chua', 'example')
print('root:', example_root)
print('exists:', os.path.exists(example_root))

print("\n=== Examples with test-friendly patterns ===")
for root, dirs, files in os.walk(example_root):
    for f in files:
        if not f.endswith('Example.java'):
            continue
        path = os.path.join(root, f)
        content = open(path, encoding='utf-8').read()
        has_main = 'public static void main' in content
        has_runttest = 'runTest(' in content or 'boolean run(' in content
        has_verify = 'verify' in content.lower()
        has_assert = '[PASS]' in content or '[FAIL]' in content
        rel = os.path.relpath(path, example_root).replace('\\', '/')
        flags = []
        if has_main: flags.append('main')
        if has_runttest: flags.append('runttest')
        if has_verify: flags.append('verify')
        if has_assert: flags.append('assert')
        print(f"{rel:60s} [{','.join(flags)}]")
