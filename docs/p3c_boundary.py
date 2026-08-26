import os, re, sys
sys.stdout.reconfigure(encoding='utf-8')

print("=== 跨模块边界扫描 ===")
# 检查其他模块是否出现 *Example* / *Test* / *Verify* / *Diag* 类
for root_dir in ['utils-support-core-parent', 'utils-support-datasource-parent', 'utils-support-cloud-parent']:
    if not os.path.exists(root_dir):
        continue
    for subdir in os.listdir(root_dir):
        full = os.path.join(root_dir, subdir)
        if not os.path.isdir(full):
            continue
        src_main = os.path.join(full, 'src', 'main', 'java')
        if not os.path.exists(src_main):
            continue
        for r, d, files in os.walk(src_main):
            for f in files:
                if re.search(r'Example|Test|Verify|Diag', f) and f.endswith('.java'):
                    rel = os.path.relpath(os.path.join(r, f), '.')
                    print(f'越界类: {rel}')

print("\n=== 非 example-starter pom.xml 中的测试依赖 ===")
for root_dir in ['utils-support-core-parent', 'utils-support-datasource-parent', 'utils-support-cloud-parent']:
    if not os.path.exists(root_dir):
        continue
    for subdir in sorted(os.listdir(root_dir)):
        full = os.path.join(root_dir, subdir)
        if not os.path.isdir(full):
            continue
        pom = os.path.join(full, 'pom.xml')
        if not os.path.exists(pom):
            continue
        content = open(pom, encoding='utf-8').read()
        deps = re.findall(r'<dependency>(.*?)</dependency>', content, re.DOTALL)
        issues = []
        for d in deps:
            a = re.search(r'<artifactId>(.*?)</artifactId>', d)
            g = re.search(r'<groupId>(.*?)</groupId>', d)
            sc = re.search(r'<scope>(.*?)</scope>', d)
            if a:
                aname = a.group(1)
                if any(k in aname.lower() for k in ['junit', 'mockito']):
                    scope = sc.group(1) if sc else 'compile'
                    issues.append(f'{aname} [{scope}]')
        if issues:
            print(f'  {subdir}: {issues}')

print("\n=== annotationProcessorPaths 缺失（Lombok Java 25+）===")
for root_dir in ['utils-support-core-parent', 'utils-support-datasource-parent', 'utils-support-cloud-parent']:
    if not os.path.exists(root_dir):
        continue
    for subdir in sorted(os.listdir(root_dir)):
        full = os.path.join(root_dir, subdir)
        if not os.path.isdir(full):
            continue
        pom = os.path.join(full, 'pom.xml')
        if not os.path.exists(pom):
            continue
        content = open(pom, encoding='utf-8').read()
        has_lp = 'annotationProcessorPaths' in content
        has_lb = 'lombok' in content
        if has_lb and not has_lp:
            print(f'  {subdir}: lombok=True, annotationProcessorPaths=False')
