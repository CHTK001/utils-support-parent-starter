package com.chua.common.support.concurrent.lock;

import lombok.Builder;
import lombok.Data;


/**
* 分布式锁配置类，用于定义锁的获取和释放策略。
*
* @author CH
* @since 2025-11-26
 */
@Data
@Builder
public class LockSetting {

    /**
    * 锁的唯一标识名称，用于区分不同的业务场景或资源。
     */
    private String name;

    /**
    * 是否使用公平锁模式。
    * true：请求锁的线程将严格按照请求顺序获得锁（FIFO）。
    * false：不保证顺序，性能通常优于公平锁。
    * 默认值为 false（非公平锁）。
     */
    @Builder.Default
    /**
    * 是否公平模式
     */
    private boolean fair = false;

    /**
    * 获取锁时等待的最大时间（单位：毫秒）。
    * 如果在此时间内无法获取锁，则抛出异常或返回失败。
    * 值为 0 表示立即尝试获取，不等待。
    * 默认值为 0。
     */
    @Builder.Default
    /**
    * 等待时间（毫秒）
     */
    private long waitTime = 0;

    /**
    * 锁的租约时间/自动续期时间（单位：毫秒）。
    * 表示获取锁后，锁保持有效的时间长度。
    * 值为 -1 表示永不过期，需手动释放；正值表示超时自动释放或需要续期。
    * 默认值为 -1（永不过期）。
     */
    @Builder.Default
    /**
    * 租约时间（毫秒）
     */
    private long leaseTime = -1;

    /**
    * 是否为可重入锁。
    * true：同一线程可以多次获取该锁而不阻塞。
    * false：不可重入，同一线程再次获取会阻塞。
    * 默认值为 true（可重入）。
     */
    @Builder.Default
    /** Reentrant */
    private boolean reentrant = true;

    /**
    * 锁的类型标识，用于指定具体的实现类（如 redis, filesystem, object 等）。
     */
    private String lockType;

    /**
    * 远程地址
    *
    * <p>用于分布式锁的远程服务连接，例如 Redis 的 {@code redis://127.0.0.1:6379}、
    * Zookeeper 的 {@code 192.168.1.100:2181} 等。
    * 当锁类型需要连接远程服务时必填。
     */
    private String remoteAddress;

    /**
    * 远程服务用户名
    *
    * <p>用于分布式锁的远程服务认证，例如 Redis 的 ACL 用户、Sentinel 的命令空间等。
    * 当远程服务需要身份验证时填写。
     */
    private String username;

    /**
    * 远程服务密码
    *
    * <p>用于分布式锁的远程服务认证，例如 Redis 的密码、Zookeeper 的认证信息等。
    * 当远程服务需要身份验证时填写。
     */
    private String password;

    /**
    * 连接超时时间（毫秒）
    *
    * <p>建立与远程服务连接的最大等待时间。
    * 默认 5000ms，值为 0 表示不超时。
     */
    @Builder.Default
    /** Connection超时 */
    private long connectionTimeout = 5000;

    /**
    * 扩展参数对象，用于传递特定锁实现所需的额外配置信息。
     */
    private Object extension;
}

