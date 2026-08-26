"""
Add annotationProcessorPaths for lombok in modules that have it but missing config.
Only fixes modules that are missing the build section entirely (most common case).
"""
import os, re, sys

sys.stdout.reconfigure(encoding='utf-8')
BASE = 'D:/ch/project/utils-support-parent-starter'

# Find all modules missing annotationProcessorPaths
to_fix = []
for root_dir in ['utils-support-core-parent', 'utils-support-cloud-parent']:
    root_path = os.path.join(BASE, root_dir)
    if not os.path.exists(root_path):
        continue
    for d in sorted(os.listdir(root_path)):
        full = os.path.join(root_path, d)
        if not os.path.isdir(full):
            continue
        pom = os.path.join(full, 'pom.xml')
        if not os.path.exists(pom):
            continue
        c = open(pom, encoding='utf-8').read()
        if 'lombok' in c and 'annotationProcessorPaths' not in c:
            to_fix.append((d, pom, c))

print(f"Found {len(to_fix)} modules to fix")

FIXED = 0
for name, pom, content in to_fix:
    # Get lombok version
    lm = re.search(r'<lombok\.version>([^<]+)</lombok\.version>', content)
    lm_version = lm.group(1) if lm else '1.18.34'

    annotator_paths = f'''                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>{lm_version}</version>
                        </path>
                    </annotationProcessorPaths>'''

    # Check if there's a <build> section
    build_match = re.search(r'<build>(.*?)</build>', content, re.DOTALL)
    if build_match:
        build_block = build_match.group(0)
        # Check if there's already a maven-compiler-plugin in build
        if 'maven-compiler-plugin' in build_block:
            # Insert into existing compiler plugin
            cp_match = re.search(r'<plugin>\s*<groupId>org\.apache\.maven\.plugins</groupId>\s*<artifactId>maven-compiler-plugin</artifactId>.*?</plugin>', build_block, re.DOTALL)
            if cp_match:
                cp = cp_match.group(0)
                if '<configuration>' in cp:
                    new_cp = cp.replace('</configuration>', annotator_paths + '\n                    </configuration>', 1)
                else:
                    new_cp = cp.replace('</plugin>', annotator_paths + '\n                    </configuration>\n                </plugin>', 1)
                new_build = build_block[:cp_match.start()] + new_cp + build_block[cp_match.end():]
                content = content[:build_match.start()] + re.sub(r'<build>.*?</build>', f'<build>{new_build}</build>', content[build_match.start():], flags=re.DOTALL)
            else:
                # Add plugin to build/plugins
                plugins_m = re.search(r'<plugins>(.*?)</plugins>', build_block, re.DOTALL)
                if plugins_m:
                    new_plugin = f'''                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
{annotator_paths}
                    </configuration>
                </plugin>'''
                    new_plugins = plugins_m.group(0).replace('</plugins>', new_plugin + '\n            </plugins>', 1)
                    content = content[:build_match.start()] + re.sub(r'<build>.*?</build>', f'<build>{build_block[:plugins_m.start()]}{new_plugins}{build_block[plugins_m.end():]}</build>', content[build_match.start():], flags=re.DOTALL)
        else:
            # Add plugin to existing build
            plugins_m = re.search(r'<plugins>(.*?)</plugins>', build_block, re.DOTALL)
            if plugins_m:
                new_plugin = f'''                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
{annotator_paths}
                    </configuration>
                </plugin>'''
                new_plugins = plugins_m.group(0).replace('</plugins>', new_plugin + '\n            </plugins>', 1)
                content = content[:build_match.start()] + re.sub(r'<build>.*?</build>', f'<build>{build_block[:plugins_m.start()]}{new_plugins}{build_block[plugins_m.end():]}</build>', content[build_match.start():], flags=re.DOTALL)
    else:
        # No build section - add one before </project>
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
    </build>
'''
        content = content[:project_end] + new_section + content[project_end:]

    open(pom, 'w', encoding='utf-8').write(content)
    FIXED += 1
    print(f"  Fixed: {name}")

print(f"\nTotal fixed: {FIXED}")
