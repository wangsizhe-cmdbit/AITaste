package com.AITaste.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


/**
 * 面试高频考点 & 实战答案（基于此 SimpleRedisLock 分布式锁实现）
 * ============================================================================
 * 1. 什么是分布式锁？为什么要用分布式锁？
 *    答：分布式锁用于控制分布式系统中多个进程对共享资源的互斥访问。
 *       在单机多线程中可使用 JVM 锁（synchronized、ReentrantLock），
 *       但多实例部署时需要跨进程的锁，Redis 分布式锁是常见解决方案。
 *
 * 2. Redis 实现分布式锁的要点有哪些？
 *    答：① 互斥性：同一时刻只有一个客户端能持有锁；
 *       ② 避免死锁：设置锁的过期时间，防止持有锁的客户端宕机后锁永不释放；
 *       ③ 锁标识：每个客户端持有唯一标识，释放时只能释放自己的锁；
 *       ④ 原子性：加锁和解锁的操作必须原子（如 setnx + expire 或 Lua 脚本）。
 *
 * 3. 本实现中 tryLock 如何保证原子性？setIfAbsent 有什么特点？
 *    答：使用 Redis 命令 SET key value NX EX seconds，该命令原子性地判断 key 不存在则设置并指定过期时间。
 *       Spring Data Redis 的 opsForValue().setIfAbsent(key, value, timeout, unit) 底层封装了该命令，
 *       避免了先 setnx 再 expire 两步操作可能出现的中间状态（若第一步成功、第二步失败则导致死锁）。
 *
 * 4. 锁的 value（threadId）为什么使用 UUID + 线程ID？
 *    答：UUID 保证全局唯一（多机多线程），拼接当前线程 ID 是为了区分同一 JVM 中不同线程。
 *       释放锁时需要校验该值，确保只有加锁的线程才能释放锁，防止误删其他线程的锁。
 *
 * 5. 为什么 unlock 要使用 Lua 脚本？不用 Lua 会有什么问题？
 *    答：释放锁的步骤：获取锁的 value → 判断是否与当前线程标识一致 → 若一致则删除 key。
 *       这三个操作如果分开执行（先 get 判断，再 delete），不是原子操作。
 *       可能出现：线程 A 判断 value 一致，此时锁刚好过期并被线程 B 获得，
 *       然后线程 A 执行 delete 误删了线程 B 的锁。Lua 脚本可以保证这些操作在 Redis 服务端原子执行。
 *
 * 6. Lua 脚本的内容大致是什么？本实现中 unlock.lua 应如何编写？
 *    答：参考标准写法：
 *       if redis.call("get", KEYS[1]) == ARGV[1] then
 *           return redis.call("del", KEYS[1])
 *       else
 *           return 0
 *       end
 *       通过 KEYS[1] 传入锁的 key，ARGV[1] 传入当前线程标识，原子完成校验和删除。
 *
 * 7. 锁的超时时间如何设置？如果业务执行时间超过锁的过期时间怎么办？
 *    答：超时时间需要根据业务执行时间合理预估，一般设置为略大于平均执行时间。
 *       若业务执行超时，锁会自动释放，导致其他线程获得锁，产生并发问题。
 *       解决方案：① 使用 Redisson 的“看门狗”机制自动续期；
 *       ② 自己实现定时任务在锁快过期时续期（需小心处理续期原子性）。
 *
 * 8. 该锁是否支持可重入？为什么？
 *    答：不支持。可重入锁需要记录同一线程加锁的次数，本实现中同一线程再次 tryLock 会因 key 已存在而失败。
 *       如需可重入，可以将 value 设计为 hash 结构，存储线程标识和计数，并增加重入逻辑（类似 Redisson）。
 *
 * 9. 该锁是公平锁还是非公平锁？
 *    答：非公平锁。Redis 的 setnx 并不保证先请求的线程先获得锁，完全依赖网络延迟和 Redis 命令执行顺序。
 *       若需公平锁，可结合队列（如 Redisson 的 FairLock）。
 *
 * 10. Redis 分布式锁的缺点？如何改进？
 *     答：① Redis 主从架构下，主节点宕机可能丢失锁（异步复制导致锁数据未同步到从节点）。
 *         改进：使用 RedLock 算法（多 Redis 实例）或直接使用 ZooKeeper/etcd 等强一致性组件。
 *       ② 锁的过期时间难以精确设置，过长影响容灾，过短可能提前释放。
 *         改进：自动续期（看门狗） + 手动释放。
 *
 * 实战要点：
 * - 加锁时必须指定过期时间，避免死锁。
 * - 锁的 value 必须全局唯一，防止误删。
 * - 释放锁必须使用 Lua 脚本保证原子性。
 * - 建议使用 Redisson 等成熟客户端，它封装了重试、续期、可重入等高级特性。
 * - 分布式锁的超时时间应设置为“业务最大执行时间 + 缓冲”，并在业务代码中捕获异常确保最终释放。
 * - 不要将分布式锁作为唯一的数据安全手段，应与数据库乐观锁、业务幂等等结合。
 * ============================================================================
 */


public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),// KEYS[1]
                ID_PREFIX + Thread.currentThread().getId());// ARGV[1]
    }
    /*@Override
    public void unlock() {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标示是否一致
        if(threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
