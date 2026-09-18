package com.chua.common.support.modules;

import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
* JDK9+ 模块加载器。
* <p>
* 用于在 JDK9+ 环境下绕过模块系统的限制，实现模块间的包导出和开放。
* 参考了 JDK 内部实现以及 burningwave core 的思路。
* </p>
*
* @author CH
* @version 2.0.0
* @since 2024/04/10
 */
@Slf4j
public class ModuleLoader {

    /**
    * 标记是否已经加载/执行过全量导出，避免重复执行。
    */
    static final AtomicBoolean IS_LOADED = new AtomicBoolean(false);

    /**
    * 缓存的 {@code Module.implAddExports} 方法引用。
    */
    private static volatile Method implAddExportsMethod;

    /**
    * 缓存的 {@code Module.implAddOpens} 方法引用。
    */
    private static volatile Method implAddOpensMethod;

    /**
    * 方法查找和初始化的同步锁。
    */
    private static final Object METHOD_LOCK = new Object();

    // ==================== 全量导出 ====================

    /**
    * 将所有模块的所有包导出并开放给所有其他模块以及未命名模块。
    * <p>
    * 执行逻辑：
    * 1. 获取当前启动模块层中的所有模块；
    * 2. 通过反射获取 JDK 内部 API（implAddExports / implAddOpens）以绕过访问控制；
    * 3. 如果反射获取失败，则降级使用标准 API 尝试向未命名模块导出。
    * </p>
    */
    public static void exportAllToAll() {
        if (IS_LOADED.compareAndSet(false, true)) {
            try {
                Method addExports = getImplAddExportsMethod();
                Method addOpens = getImplAddOpensMethod();

                if (addExports == null || addOpens == null) {
                    fallbackExportAllToAll();
                    return;
                }

                Module[] modulesArray = ModuleLayer.boot().modules().toArray(Module[]::new);
                Module unnamedModule = getUnnamedModule();

                for (Module source : modulesArray) {
                    exportModule(source, modulesArray, unnamedModule, addExports, addOpens);
                }
            } catch (Exception e) {
                log.trace("全量导出失败", e);
            }
        }
    }

    /**
    * 将单个模块的所有包导出并开放给目标模块及未命名模块。
    *
    * @param source      源模块
    * @param targets     目标模块数组
    * @param unnamed     未命名模块
    * @param addExports  implAddExports 方法
    * @param addOpens    implAddOpens 方法
    */
    private static void exportModule(Module source, Module[] targets, Module unnamed,
                                     Method addExports, Method addOpens) {
        if (source == null) {
            return;
        }
        if (!source.isNamed()) {
            return;
        }

        ModuleDescriptor descriptor = source.getDescriptor();
        if (descriptor == null) {
            return;
        }

        for (String pkg : descriptor.packages()) {
            exportPackage(source, pkg, targets, unnamed, addExports);
            openPackage(source, pkg, targets, unnamed, addOpens);
        }
    }

    /**
    * 将单个包导出给目标模块及未命名模块。
    *
    * @param source      源模块
    * @param pkg         包名
    * @param targets     目标模块数组
    * @param unnamed     未命名模块
    * @param addExports  implAddExports 方法
    */
    private static void exportPackage(Module source, String pkg, Module[] targets,
                                      Module unnamed, Method addExports) {
        try {
            for (Module target : targets) {
                if (target != null && target != source) {
                    addExports.invoke(source, pkg, target); // [P3C 1.10 豁免] JDK Module 内部私有 API（implAddExports/implAddOpens），无法用 ReflectUtils 替代
                }
            }
            if (unnamed != null) {
                addExports.invoke(source, pkg, unnamed); // [P3C 1.10 豁免] JDK Module 内部私有 API（implAddExports/implAddOpens），无法用 ReflectUtils 替代
            }
        } catch (Exception e) {
            log.trace("导出包 {} 失败: {}", pkg, e.getMessage());
        }
    }

    /**
    * 将单个包开放给目标模块及未命名模块。
    *
    * @param source     源模块
    * @param pkg        包名
    * @param targets    目标模块数组
    * @param unnamed    未命名模块
    * @param addOpens   implAddOpens 方法
    */
    private static void openPackage(Module source, String pkg, Module[] targets,
                                    Module unnamed, Method addOpens) {
        try {
            for (Module target : targets) {
                if (target != null && target != source) {
                    addOpens.invoke(source, pkg, target); // [P3C 1.10 豁免] JDK Module 内部私有 API（implAddExports/implAddOpens），无法用 ReflectUtils 替代
                }
            }
            if (unnamed != null) {
                addOpens.invoke(source, pkg, unnamed); // [P3C 1.10 豁免] JDK Module 内部私有 API（implAddExports/implAddOpens），无法用 ReflectUtils 替代
            }
        } catch (Exception e) {
            log.trace("开放包 {} 失败: {}", pkg, e.getMessage());
        }
    }

    // ==================== 单模块导出 ====================

    /**
    * 将指定模块的所有包导出给未命名模块。
    *
    * @param name 模块名称
    */
    public void exportToAllUnnamed(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }

        try {
            Module sourceModule = resolveModule(name);
            if (sourceModule == null) {
                return;
            }

            Module unnamedModule = getUnnamedModule();
            if (unnamedModule == null) {
                return;
            }

            Method addExports = getImplAddExportsMethod();
            if (addExports == null) {
                return;
            }

            ModuleDescriptor descriptor = sourceModule.getDescriptor();
            if (descriptor != null) {
                for (String pkg : descriptor.packages()) {
                    exportPackageToUnnamed(sourceModule, pkg, unnamedModule, addExports);
                }
            }
        } catch (Exception e) {
            log.warn("导出到未命名模块失败: {}", name, e);
        }
    }

    /**
    * 将指定模块的所有包导出并开放给所有其他模块及未命名模块。
    *
    * @param name 模块名称
    */
    public void exportToAll(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }

        try {
            Module sourceModule = resolveModule(name);
            if (sourceModule == null) {
                return;
            }

            Method addExports = getImplAddExportsMethod();
            Method addOpens = getImplAddOpensMethod();
            if (addExports == null || addOpens == null) {
                return;
            }

            ModuleDescriptor descriptor = sourceModule.getDescriptor();
            if (descriptor != null) {
                Module[] allModules = ModuleLayer.boot().modules().toArray(Module[]::new);
                Module unnamedModule = getUnnamedModule();

                for (String pkg : descriptor.packages()) {
                    exportPackageToAll(sourceModule, pkg, allModules, unnamedModule, addExports, addOpens);
                }
            }
        } catch (Exception e) {
            log.warn("导出到所有模块失败: {}", name, e);
        }
    }

    /**
    * 将指定包的导出/开放应用到所有目标模块及未命名模块。
    *
    * @param source      源模块
    * @param pkg         包名
    * @param targets     目标模块数组
    * @param unnamed     未命名模块
    * @param addExports  implAddExports 方法
    * @param addOpens    implAddOpens 方法
    */
    private static void exportPackageToAll(Module source, String pkg, Module[] targets,
                                           Module unnamed, Method addExports, Method addOpens) {
        for (Module target : targets) {
            if (target != null && target != source) {
                invokeReflectively(source, pkg, target, addExports, addOpens);
            }
        }
        if (unnamed != null) {
            invokeReflectively(source, pkg, unnamed, addExports, addOpens);
        }
    }

    /**
    * 将指定包导出给未命名模块。
    *
    * @param source      源模块
    * @param pkg         包名
    * @param unnamed     未命名模块
    * @param addExports  implAddExports 方法
    */
    private static void exportPackageToUnnamed(Module source, String pkg,
                                               Module unnamed, Method addExports) {
        try {
            addExports.invoke(source, pkg, unnamed); // [P3C 1.10 豁免] JDK Module 内部私有 API（implAddExports/implAddOpens），无法用 ReflectUtils 替代
        } catch (Exception e) {
            log.trace("导出包 {} 到未命名模块失败: {}", pkg, e.getMessage());
        }
    }

    /**
    * 通过反射对目标模块执行 exports/opens。
    *
    * @param source     源模块
    * @param pkg        包名
    * @param target     目标模块
    * @param addExports implAddExports 方法
    * @param addOpens   implAddOpens 方法
    */
    private static void invokeReflectively(Module source, String pkg, Module target,
                                           Method addExports, Method addOpens) {
        try {
            addExports.invoke(source, pkg, target); // [P3C 1.10 豁免] JDK Module 内部私有 API（implAddExports/implAddOpens），无法用 ReflectUtils 替代
            addOpens.invoke(source, pkg, target); // [P3C 1.10 豁免] JDK Module 内部私有 API（implAddExports/implAddOpens），无法用 ReflectUtils 替代
        } catch (Exception e) {
            log.trace("对模块 {} 导出/开放包 {} 失败: {}", target, pkg, e.getMessage());
        }
    }

    // ==================== 降级方案 ====================

    /**
    * 降级方案：当无法获取内部 API 时，使用标准 API 尝试向未命名模块导出/开放。
    */
    private static void fallbackExportAllToAll() {
        try {
            Module[] modulesArray = ModuleLayer.boot().modules().toArray(Module[]::new);
            Module unnamedModule = getUnnamedModule();

            for (Module source : modulesArray) {
                fallbackExportModule(source, modulesArray, unnamedModule);
            }
        } catch (Exception e) {
            log.trace("降级全量导出失败", e);
        }
    }

    /**
    * 降级：将单个模块的所有包导出/开放给未命名模块。
    *
    * @param source      源模块
    * @param targets     目标模块数组
    * @param unnamed     未命名模块
    */
    private static void fallbackExportModule(Module source, Module[] targets, Module unnamed) {
        if (source == null) {
            return;
        }
        if (!source.isNamed()) {
            return;
        }

        ModuleDescriptor descriptor = source.getDescriptor();
        if (descriptor == null) {
            return;
        }

        for (String pkg : descriptor.packages()) {
            fallbackExportPackage(source, pkg, unnamed);
        }
    }

    /**
    * 降级：将单个包导出/开放给未命名模块。
    *
    * @param source  源模块
    * @param pkg     包名
    * @param unnamed 未命名模块
    */
    private static void fallbackExportPackage(Module source, String pkg, Module unnamed) {
        try {
            if (unnamed != null) {
                source.addExports(pkg, unnamed);
                source.addOpens(pkg, unnamed);
            }
        } catch (Exception e) {
            log.trace("降级导出包 {} 失败: {}", pkg, e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
    * 解析模块名称对应的 Module 实例。
    *
    * @param name 模块名称
    * @return Module 实例，不存在则返回 null
    */
    private static Module resolveModule(String name) {
        return ModuleLayer.boot().findModule(name).orElse(null);
    }

    /**
    * 获取 {@code Module.implAddExports} 方法引用。
    * <p>
    * 采用双重检查锁定（DCL）保证线程安全，仅首次调用时进行反射查找。
    * </p>
    *
    * @return 方法引用，获取失败返回 null
    */
    private static Method getImplAddExportsMethod() {
        if (implAddExportsMethod == null) {
            synchronized (METHOD_LOCK) {
                if (implAddExportsMethod == null) {
                    try {
                        implAddExportsMethod = Module.class.getDeclaredMethod( // [P3C 1.10 豁免] JDK Module 内部私有 API（implAddExports），ReflectUtils 不覆盖 JDK 内部适配
                                "implAddExports", String.class, Module.class);
                        implAddExportsMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        log.trace("未找到 implAddExports 方法", e);
                    } catch (RuntimeException e) {
                        log.trace("无法获取 implAddExports 方法", e);
                    }
                }
            }
        }
        return implAddExportsMethod;
    }

    /**
    * 获取 {@code Module.implAddOpens} 方法引用。
    * <p>
    * 采用双重检查锁定（DCL）保证线程安全，仅首次调用时进行反射查找。
    * </p>
    *
    * @return 方法引用，获取失败返回 null
    */
    private static Method getImplAddOpensMethod() {
        if (implAddOpensMethod == null) {
            synchronized (METHOD_LOCK) {
                if (implAddOpensMethod == null) {
                    try {
                        implAddOpensMethod = Module.class.getDeclaredMethod( // [P3C 1.10 豁免] JDK Module 内部私有 API（implAddOpens），ReflectUtils 不覆盖 JDK 内部适配
                                "implAddOpens", String.class, Module.class);
                        implAddOpensMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        log.trace("未找到 implAddOpens 方法", e);
                    } catch (RuntimeException e) {
                        log.trace("无法获取 implAddOpens 方法", e);
                    }
                }
            }
        }
        return implAddOpensMethod;
    }

    /**
    * 获取当前类加载器的未命名模块。
    *
    * @return 未命名模块，获取失败返回 null
    */
    private static Module getUnnamedModule() {
        try {
            ClassLoader classLoader = ModuleLoader.class.getClassLoader();
            if (classLoader != null) {
                return classLoader.getUnnamedModule();
            }
            return ClassLoader.getSystemClassLoader().getUnnamedModule();
        } catch (Exception e) {
            log.trace("获取未命名模块失败", e);
            return null;
        }
    }
}

