"""Fix MemorySqlExample.java"""
import sys, os, re
sys.stdout.reconfigure(encoding='utf-8')

f = 'utils-support-extra-parent/utils-support-example-starter/src/main/java/com/chua/example/engine/MemorySqlExample.java'
c = open(f, encoding='utf-8').read()

# Fix the CommandLine usage - replace the broken API with correct one
# The API is: CommandLine.parse(args).register(key, shortKey, desc, default)
# Methods: isHelp(), help(), get(key)

# Find and replace the main method body
old_block = c[c.find('public static void main'):c.find('private static void runSync')]
if old_block:
    # Replace the CommandLine usage
    new_block = re.sub(
        r'var cli = CommandLine\.builder\(\).*?var mode = result\.getString\("mode"\);',
        'var cli = CommandLine.parse(args).register("mode", "m", "mode: sql|reactor|all", MODE_ALL);\n'
        '        if (cli.isHelp()) { cli.help(); return; }\n'
        '        var mode = cli.get("mode");',
        old_block,
        flags=re.DOTALL
    )
    c = c.replace(old_block, new_block)
    open(f, 'w', encoding='utf-8').write(c)
    print('Fixed MemorySqlExample')
else:
    print('main method not found')
