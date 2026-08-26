"""
Fix pom.xml issues:
1. Remove junit-jupiter/mockito deps from non-example-starter modules
2. Add annotationProcessorPaths for lombok in modules that have it but missing config
"""
import os, re, sys

sys.stdout.reconfigure(encoding='utf-8')

BASE = 'D:/ch/project/utils-support-parent-starter'

# === Part 1: Remove JUnit deps from non-example-starter modules ===
print("=== Part 1: Removing JUnit deps from non-example-starter poms ===")

JUNIT_DEPS = ['junit-jupiter-api', 'junit-jupiter-engine', 'junit-platform-launcher', 'mockito-core']
SKIP_MODULES = {'utils-support-example-starter'}

removed_count = 0
for root_dir in ['utils-support-core-parent', 'utils-support-datasource-parent', 'utils-support-cloud-parent']:
    root_path = os.path.join(BASE, root_dir)
    if not os.path.exists(root_path):
        continue
    for subdir in sorted(os.listdir(root_path)):
        full = os.path.join(root_path, subdir)
        if not os.path.isdir(full):
            continue
        if subdir in SKIP_MODULES:
            continue
        pom = os.path.join(full, 'pom.xml')
        if not os.path.exists(pom):
            continue
        content = open(pom, encoding='utf-8').read()
        original = content
        # Remove entire <dependency>...</dependency> blocks containing junit or mockito
        new_content = re.sub(
            r'\s*<!-- Test dependencies -->?\s*<dependency>\s*<groupId>org\.junit\.jupiter</groupId>\s*<artifactId>junit-jupiter-(api|engine)</artifactId>.*?</dependency>',
            '',
            content,
            flags=re.DOTALL
        )
        new_content = re.sub(
            r'\s*<dependency>\s*<groupId>org\.junit\.jupiter</groupId>\s*<artifactId>junit-jupiter-engine</artifactId>.*?</dependency>',
            '',
            new_content,
            flags=re.DOTALL
        )
        new_content = re.sub(
            r'\s*<dependency>\s*<groupId>org\.junit\.jupiter</groupId>\s*<artifactId>junit-jupiter-api</artifactId>.*?</dependency>',
            '',
            new_content,
            flags=re.DOTALL
        )
        new_content = re.sub(
            r'\s*<dependency>\s*<groupId>org\.junit\.platform</groupId>\s*<artifactId>junit-platform-launcher</artifactId>.*?</dependency>',
            '',
            new_content,
            flags=re.DOTALL
        )
        new_content = re.sub(
            r'\s*<dependency>\s*<groupId>org\.mockito</groupId>\s*<artifactId>mockito-core</artifactId>.*?</dependency>',
            '',
            new_content,
            flags=re.DOTALL
        )
        # Clean up orphaned comments
        new_content = re.sub(r'\s*<!-- Test dependencies -->\s*', '\n', new_content)
        new_content = re.sub(r'\n{3,}', '\n\n', new_content)
        if new_content != original:
            open(pom, 'w', encoding='utf-8').write(new_content)
            removed_count += 1
            print(f"  Fixed: {subdir}")

print(f"\nRemoved JUnit deps from {removed_count} modules\n")

# === Part 2: Add annotationProcessorPaths for lombok modules ===
print("=== Part 2: Adding annotationProcessorPaths for lombok modules ===")

LOMBOK_MODULES = [
    'auth', 'deeplearning', 'filesystem', 'payment', 'runtime',
    'alibaba', 'amazon', 'claude', 'cloudflare', 'dingding',
    'doubao', 'google', 'huawei', 'hunyuan', 'microsoft',
    'openai', 'qiniu', 'tencent', 'webhook', 'wechat',
    'xunfei', 'zai', 'zhipu'
]

FIXED = 0
for root_dir in ['utils-support-core-parent', 'utils-support-cloud-parent']:
    root_path = os.path.join(BASE, root_dir)
    if not os.path.exists(root_path):
        continue
    for subdir in LOMBOK_MODULES:
        prefix = 'utils-support-'
        suffix = '-starter'
        if not subdir.startswith(prefix) or not subdir.endswith(suffix):
            continue
        full = os.path.join(root_path, subdir)
        if not os.path.isdir(full):
            continue
        pom = os.path.join(full, 'pom.xml')
        if not os.path.exists(pom):
            continue
        content = open(pom, encoding='utf-8').read()
        if 'annotationProcessorPaths' in content:
            continue

        # Get lombok version
        lm = re.search(r'<lombok\.version>([^<]+)</lombok\.version>', content)
        lm_version = lm.group(1) if lm else '1.18.34'
        gp = re.search(r'<groupId>([^<]+)</groupId>', content)
        group_id = gp.group(1) if gp else 'com.chua'

        # Find <plugins> section or create one
        plugins_match = re.search(r'<plugins>(.*?)</plugins>', content, re.DOTALL)
        compiler_plugin_match = re.search(r'<plugin>\s*<groupId>org\.apache\.maven\.plugins</groupId>\s*<artifactId>maven-compiler-plugin</artifactId>.*?</plugin>', content, re.DOTALL)

        annotator_paths = f'''                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>{lm_version}</version>
                        </path>
                    </annotationProcessorPaths>'''

        if compiler_plugin_match:
            # Insert annotationProcessorPaths into existing compiler plugin
            cp = compiler_plugin_match.group(0)
            if '<configuration>' in cp:
                new_cp = cp.replace('</configuration>', annotator_paths + '\n                    </configuration>', 1)
            else:
                new_cp = cp.replace('</plugin>', annotator_paths + '\n                </configuration>\n            </plugin>', 1)
            content = content[:compiler_plugin_match.start()] + new_cp + content[compiler_plugin_match.end():]
        elif plugins_match:
            # Insert new plugin before </plugins>
            plugins_block = plugins_match.group(0)
            new_plugin = f'''                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
{annotator_paths}
                    </configuration>
                </plugin>'''
            content = content[:plugins_match.end()-len('</plugins>')] + new_plugin + content[plugins_match.end()-len('</plugins>'):]
        else:
            # Create build/plugins section
            build_match = re.search(r'<build>(.*?)</build>', content, re.DOTALL)
            if build_match:
                build_block = build_match.group(0)
                new_section = f'''<build>
    <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
{annotator_paths}
                    </configuration>
                </plugin>
    </plugins>
</build>'''
                # Remove old build section and replace
                content = content[:build_match.start()] + new_section + content[build_match.end():]
            else:
                # Add build section before </project>
                project_end = content.rfind('</project>')
                new_section = f'''    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
{annotator_paths}
                </configuration>
            </plugin>
        </plugins>
    </build>\n'''
                content = content[:project_end] + new_section + content[project_end:]

        open(pom, 'w', encoding='utf-8').write(content)
        FIXED += 1
        print(f"  Fixed: {subdir}")

print(f"\nAdded annotationProcessorPaths to {FIXED} modules")
