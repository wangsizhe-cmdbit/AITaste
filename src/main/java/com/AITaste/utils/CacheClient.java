package com.AITaste.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static com.AITaste.utils.RedisConstants.*;



/**
 * 面试高频考点 & 实战答案（基于此 CacheClient 缓存工具类实现）
 * ============================================================================
 * 1. 这个工具类主要封装了哪些缓存解决方案？
 *    答：① 普通缓存写入（set）；
 *       ② 逻辑过期缓存写入（setWithLogicalExpire）；
 *       ③ 解决缓存穿透的查询（queryWithPassThrough）：缓存空对象 + 短TTL；
 *       ④ 解决缓存击穿的查询（queryWithLogicalExpire）：逻辑过期 + 互斥锁 + 异步重建。
 *
 * 2. queryWithPassThrough 如何解决缓存穿透？为什么还要缓存空值？
 *    答：当数据库查询结果为空时，将空字符串（""）写入 Redis，并设置较短的过期时间（CACHE_NULL_TTL）。
 *       这样后续相同 key 的请求会直接返回 null，不会穿透到数据库，避免恶意攻击或大量不存在 key 的请求压垮 DB。
 *
 * 3. queryWithLogicalExpire 如何解决缓存击穿？它的核心思想是什么？
 *    答：核心思想是“逻辑过期”：不设置 Redis 物理过期时间，而是在 value 中增加 expireTime 字段。
 *       查询时若发现逻辑过期，则尝试获取互斥锁，成功则提交异步线程去数据库加载新数据并更新缓存（重置逻辑过期时间），
 *       当前线程立即返回旧数据。这样既保证了高可用（不阻塞请求），又避免了大量线程同时重建缓存。
 *
 * 4. 逻辑过期方案中，为什么使用独立线程池 CACHE_REBUILD_EXECUTOR？
 *    答：缓存重建涉及数据库查询和 Redis 写操作，属于 IO 密集型任务，使用线程池可以避免频繁创建线程的开销，
 *       同时控制并发重建的线程数量（本示例为 10），防止过多重建任务耗尽系统资源。
 *
 * 5. 互斥锁 tryLock 是如何实现的？锁的过期时间为什么是 10 秒？
 *    答：tryLock 使用 Redis 的 setIfAbsent 命令（底层 SET NX EX），key 为 "lock:shop:" + id，
 *       值为 "1"，过期时间 10 秒。10 秒是合理的预估重建时间（数据库查询 + 网络 IO），
 *       若重建超过 10 秒，锁会自动释放，避免死锁；但也可能导致另一个线程再次重建，造成短暂重复。
 *
 * 6. 为什么 unlock 没有使用 Lua 脚本？会有原子性问题吗？
 *    答：本示例的 unlock 只是简单删除锁 key。由于锁的值固定为 "1"，没有校验当前线程是否持有锁，
 *       存在误删其他线程锁的风险（例如线程 A 持有锁但执行超时，锁自动释放，线程 B 获得锁，
 *       然后线程 A 执行 unlock 删除线程 B 的锁）。生产环境应使用带 value 校验的 Lua 脚本释放锁。
 *       这里简化了，因为逻辑过期方案中锁只用于控制异步重建，且重建线程结束后才释放，冲突概率较低。
 *
 * 7. queryWithLogicalExpire 中，为什么获取锁失败后不等待，而是直接返回旧数据？
 *    答：逻辑过期方案的设计目标是“可用性优先”：即使缓存已过期，也不阻塞用户请求，
 *       直接返回旧数据，同时由成功获取锁的线程异步更新缓存。这样用户体验不受影响，
 *       而互斥锁方案（queryWithMutex）会阻塞等待，可能造成大量线程堆积。
 *
 * 8. 如果数据库更新后，逻辑过期的缓存如何保证最终一致性？
 *    答：本工具类只负责查询，未提供主动更新逻辑。通常配合 Cache Aside 模式：
 *       更新数据库时，删除物理缓存（若使用逻辑过期，则需更新逻辑过期时间或直接删除 key）。
 *       因为逻辑过期缓存没有物理过期时间，必须由业务主动更新（例如在 update 方法中调用 delete 或 setWithLogicalExpire）。
 *
 * 9. Function<ID, R> dbFallback 的作用是什么？为什么使用函数式编程？
 *    答：dbFallback 是一个函数式接口，允许调用方传入数据库查询逻辑（如 id -> shopService.getById(id)）。
 *       这样 CacheClient 不需要依赖具体的业务 Service，实现了解耦和复用，符合“模板方法”设计模式。
 *
 * 10. 普通 set 方法和 setWithLogicalExpire 的区别？
 *     答：普通 set 设置 Redis 物理过期时间（expire），到期自动删除；
 *       setWithLogicalExpire 不设置物理过期，而是将数据封装到 RedisData 对象中，附带 expireTime 字段，
 *       由业务代码判断是否逻辑过期，适用于需要异步刷新且不允许缓存物理消失的场景。
 *
 * 实战要点：
 * - 缓存穿透、击穿、雪崩是面试必考点，应能清晰说出解决方案及代码实现。
 * - 逻辑过期适用于对数据实时性要求不高、但要求高可用的场景（如商品详情、配置信息）。
 * - 互斥锁的锁 key 必须与业务资源关联（如锁商品 ID），避免不同资源相互影响。
 * - 异步重建线程池需合理配置核心线程数，避免过多线程竞争数据库连接。
 * - 注意空值缓存应设置较短 TTL（如 2 分钟），防止内存被无效 key 占满。
 * - 生产环境中建议使用 Redisson 等成熟框架，避免手写锁导致的 bug。
 * - 测试时需要模拟高并发场景，验证缓存击穿时只有一个线程查询数据库。
 * ============================================================================
 */


@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private final ExecutorService asyncRefreshPool = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 调用位置：CacheWarmerService.refreshShopCache
    // 调用位置：queryWithPassThrough（内部）
    // 预热时主动写入缓存；查询时从数据库加载后写入。
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 逻辑过期 set
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 逻辑过期查询（防击穿核心）
    public <R, ID> R queryWithLogicalExpire(String key, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        // 从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R data = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 未逻辑过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未逻辑过期，直接返回店铺信息
            return data;
        }

        // 已逻辑过期，异步刷新
        asyncRefreshPool.submit(() -> {
            String lockKey = LOCK_SHOP_REFRESH_KEY + key;
            Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(lock)) {
                try {
                    // 双重检查，避免重复刷新
                    String freshJson = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(freshJson)) {
                        RedisData freshData = JSONUtil.toBean(freshJson, RedisData.class);
                        if (freshData.getExpireTime().isAfter(LocalDateTime.now())) {
                            return;
                        }
                    }
                    R newData = dbFallback.apply(id);
                    if (newData != null) {
                        setWithLogicalExpire(key, newData, time, unit);
                        log.debug("异步刷新缓存成功: {}", key);
                    }
                } catch (Exception e) {
                    log.error("异步刷新缓存失败: {}", key, e);
                } finally {
                    stringRedisTemplate.delete(lockKey);
                }
            }
        });
        // 立即返回旧数据
        return data;
    }

    // 调用位置：ShopServiceImpl.update（延迟删除）
    // 店铺更新时删除缓存（根据热度立即或延迟删除）。
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }
}