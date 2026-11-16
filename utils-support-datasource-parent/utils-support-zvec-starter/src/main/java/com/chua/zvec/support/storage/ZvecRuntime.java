package com.chua.zvec.support.storage;

import lombok.extern.slf4j.Slf4j;
import org.zvec.binding.ConfigData;
import org.zvec.binding.LogLevel;
import org.zvec.binding.Zvec;

/**
 * Zvec 原生库全局生命周期管理。
 *
 * <p>zvec 的 {@code initialize} / {@code shutdown} 是进程级状态，而 SPI 每次创建
 * {@link ZvecVectorStorage} 都会打开一个集合。若每个实例各自 shutdown，会踩掉其他实例；
 * 若谁都不 shutdown，原生线程池又无法释放。因此用引用计数统一管理：</p>
 * <ul>
 *   <li>首个实例打开时初始化，最后一个实例关闭时释放；</li>
 *   <li>只释放「本模块自己初始化」的库——宿主若已先初始化 zvec，{@link Zvec#isInitialized()}
 *       为 true，本模块不接管，也不在关闭时调用 shutdown。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
final class ZvecRuntime {

    /**
     * 生命周期锁。
     */
    private static final Object LOCK = new Object();

    /**
     * 当前打开中的集合数量。
     */
    private static int refCount;

    /**
     * 是否由本模块完成初始化。
     */
    private static boolean owned;

    private ZvecRuntime() {
    }

    /**
     * 占用一次引用，必要时初始化原生库。
     */
    static void acquire() {
        synchronized (LOCK) {
            if (refCount == 0 && !Zvec.isInitialized()) {
                try (ConfigData config = new ConfigData()) {
                    config.setConsoleLog(LogLevel.WARN);
                    Zvec.initialize(config);
                    owned = true;
                    log.info("[zvec] 原生库已初始化，version={}", Zvec.getVersion());
                }
            }
            refCount++;
        }
    }

    /**
     * 释放一次引用，归零时关闭由本模块初始化的原生库。
     */
    static void release() {
        synchronized (LOCK) {
            if (refCount == 0) {
                return;
            }
            refCount--;
            if (refCount == 0 && owned) {
                try {
                    Zvec.shutdown();
                } catch (RuntimeException e) {
                    log.warn("[zvec] 原生库关闭失败: {}", e.getMessage());
                } finally {
                    owned = false;
                }
            }
        }
    }
}
