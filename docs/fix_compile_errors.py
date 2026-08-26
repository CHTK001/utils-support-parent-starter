"""Fix compilation errors in example-starter."""
import sys, os, re
sys.stdout.reconfigure(encoding='utf-8')

# 1. Fix DFineExample.java - log.info(result) where result is DetectedObjects
f1 = 'utils-support-extra-parent/utils-support-example-starter/src/main/java/com/chua/example/network/DFineExample.java'
if os.path.exists(f1):
    c = open(f1, encoding='utf-8').read()
    c = c.replace('log.info(result);', 'log.info(result.toString());')
    open(f1, 'w', encoding='utf-8').write(c)
    print('Fixed DFineExample: log.info(result) -> log.info(result.toString())')

# 2. Fix TextBsrExample.java - ReflectUtils.getMethod doesn't exist, remove unused variable
f2 = 'utils-support-extra-parent/utils-support-example-starter/src/main/java/com/chua/example/onnx/TextBsrExample.java'
if os.path.exists(f2):
    c = open(f2, encoding='utf-8').read()
    # Remove the getMethod line (line 45) since it's unused
    c = re.sub(r'\s*java\.lang\.reflect\.Method setScale = ReflectUtils\.getMethod\(target\.getClass\(\), "setScale", int\.class\);\n', '\n', c)
    open(f2, 'w', encoding='utf-8').write(c)
    print('Fixed TextBsrExample: removed unused getMethod call')

# 3. Fix RetryExample.java - fail(String, String) doesn't exist, need to add overload
f3 = 'utils-support-extra-parent/utils-support-example-starter/src/main/java/com/chua/example/taskretry/RetryExample.java'
if os.path.exists(f3):
    c = open(f3, encoding='utf-8').read()
    # Add fail(String, String) overload after the existing fail method
    old_fail = 'private static boolean fail(String name, Exception e) {'
    new_fail = '''private static boolean fail(String name, Exception e) {
        log.info("[FAIL] {} {}", name, e.getMessage());
        return false;
    }

    /** Helper: fail with message string */
    private static boolean fail(String name, String msg) {
        log.info("[FAIL] {} {}", name, msg);
        return false;
    }'''
    c = c.replace(old_fail + '\n        log.info("[FAIL] " + name + ": " + e.getMessage());\n        return false;\n    }', new_fail)
    open(f3, 'w', encoding='utf-8').write(c)
    print('Fixed RetryExample: added fail(String, String) overload')

# 4. Fix AsyncDeduplicateExample.java - method references throwing Exception can't be BooleanSupplier
f4 = 'utils-support-extra-parent/utils-support-example-starter/src/main/java/com/chua/example/taskasync/AsyncDeduplicateExample.java'
if os.path.exists(f4):
    c = open(f4, encoding='utf-8').read()
    c = c.replace(
        'passed &= timed("asyncSupplyRunAndBatch", AsyncDeduplicateExample::asyncSupplyRunAndBatch);',
        'passed &= timed("asyncSupplyRunAndBatch", () -> asyncSupplyRunAndBatch());'
    )
    c = c.replace(
        'passed &= timed("ttlExpiryAllowsReprocess", AsyncDeduplicateExample::ttlExpiryAllowsReprocess);',
        'passed &= timed("ttlExpiryAllowsReprocess", () -> ttlExpiryAllowsReprocess());'
    )
    open(f4, 'w', encoding='utf-8').write(c)
    print('Fixed AsyncDeduplicateExample: wrapped method refs in lambdas')

# 5. Fix FaceEnrollSearchBranchExample.java - log.info(sb) where sb is StringBuilder
f5 = 'utils-support-extra-parent/utils-support-example-starter/src/main/java/com/chua/example/face/FaceEnrollSearchBranchExample.java'
if os.path.exists(f5):
    c = open(f5, encoding='utf-8').read()
    c = c.replace('log.info(sb);', 'log.info(sb.toString());')
    open(f5, 'w', encoding='utf-8').write(c)
    print('Fixed FaceEnrollSearchBranchExample: log.info(sb) -> log.info(sb.toString())')

print('Done.')
