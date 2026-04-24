package com.AITaste.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.AITaste.VO.UserVoucherVO;
import com.AITaste.dto.Result;
import com.AITaste.entity.Voucher;
import com.AITaste.entity.VoucherOrder;
import com.AITaste.mapper.VoucherOrderMapper;
import com.AITaste.service.*;
import com.AITaste.utils.RedisIdWorker;
import com.AITaste.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * 面试高频考点 & 实战答案（基于此秒杀下单服务实现）
 * ============================================================================
 * 1. 秒杀场景下如何防止库存超卖？
 *    答：采用 Redis Lua 脚本原子性扣减库存（先检查库存是否充足，再扣减），
 *       脚本执行过程中不会被其他命令打断，保证了库存操作的原子性。
 *       同时数据库更新时使用乐观锁（stock > 0 条件），双重保障。
 *
 * 2. 如何防止同一用户重复下单？
 *    答：在 Lua 脚本中利用 Redis 的 Set 结构记录用户是否已购买某优惠券，
 *       脚本中先判断用户是否已存在购买记录，若已存在则直接返回失败。
 *       此外，在异步创建订单时还使用 Redisson 分布式锁（key = "lock:order:" + userId）
 *       再次校验，作为兜底防止并发情况下脚本执行后的重复下单。
 *
 * 3. 为什么使用 Redis Stream 消息队列？有哪些优点？
 *    答：Stream 是 Redis 5.0 引入的消息队列，支持持久化、消费者组、消息确认（ACK）、
 *        pending 消息重放等特性。相比 List 或 Pub/Sub，它更可靠（不丢消息），
 *       支持多个消费者竞争消费，并且可以回溯未确认的消息，非常适合秒杀异步下单场景。
 *
 * 4. 异步下单的整体流程是怎样的？
 *    答：① 用户发起秒杀请求，Lua 脚本在 Redis 中完成库存扣减和用户购买资格校验；
 *       ② 校验通过后，立即返回订单 id（订单 id 由全局 ID 生成器生成）；
 *       ③ 同时将订单消息发送到 Redis Stream（stream.orders）；
 *       ④ 后台独立线程（VoucherOrderHandler）轮询消费 Stream 中的消息，真正写入数据库；
 *       ⑤ 消费成功则 XACK 确认消息；若消费异常，将消息放入 pending-list 稍后重试。
 *
 * 5. 为什么要使用 Redis 全局 ID 生成器？
 *    答：保证订单 id 在分布式系统中全局唯一、递增趋势、高性能。
 *       本实现使用 Redis 的 INCR 命令结合时间戳生成 64 位长整型 ID。
 *
 * 6. Redisson 分布式锁的作用？锁的粒度是否合理？
 *    答：在异步创建订单时，用分布式锁防止同一用户短时间内创建多个订单（虽然 Lua 脚本已做限制，
 *       但为了应对极端情况如消息重复消费、脚本执行后数据库落库前再次请求等）。锁粒度是每个用户一个锁，
 *       既保证了同一用户串行下单，又不影响不同用户并发，是合理的。
 *
 * 7. 如何处理消息消费失败？pending-list 机制是什么？
 *    答：如果消费消息时抛异常（如数据库宕机），当前消息不会被 XACK，会留在 pending-list 中。
 *       handlePendingList 方法会持续尝试读取 pending-list（ReadOffset.from("0")）并重新处理，
 *       直到成功。这样可以最大程度保证消息不丢失。
 *
 * 8. 为什么要区分正常消费和 pending-list 消费？
 *    答：正常消费使用 ReadOffset.lastConsumed()，只读取未被组内任何消费者读取过的消息；
 *       若出现异常，pending-list 中的消息需要单独用 ReadOffset.from("0") 读取并重试。
 *       这样设计避免了正常消费流被阻塞，同时保证了异常消息的重试。
 *
 * 9. 数据库创建订单前为什么还要检查订单是否存在？
 *    答：作为最终一致性校验，防止由于 Redis 脚本或消息队列重复投递等原因导致重复下单。
 *       同时利用数据库唯一索引（user_id + voucher_id）也可以作为最后的防重保障。
 *
 * 10. 如何保证库存扣减和订单创建的最终一致性？
 *     答：Redis 扣减库存是第一步，数据库扣减是第二步。如果数据库扣减失败（如库存不足），
 *        则不会创建订单，Redis 中已扣减的库存如何恢复？实际设计中，可以通过定时任务或
 *        反向消息恢复；更常见的是把 Redis 库存视为“预扣”，后续真正下单成功才最终确认，
 *        若下单失败则异步回滚 Redis 库存。本示例简化了该流程，生产环境需考虑回滚机制。
 *
 * 实战要点：
 * - 秒杀接口应优先返回结果，复杂逻辑异步化，提高吞吐量。
 * - Lua 脚本必须原子性，避免竞态条件。
 * - Redis Stream 消费者组需要提前创建（XGROUP CREATE），本示例假设已创建。
 * - 分布式锁的 tryLock 需要设置超时时间，防止死锁（示例中未显式设置，实际应设置）。
 * - 消息消费逻辑应具备幂等性，重复消费不会产生副作用（通过数据库唯一索引保证）。
 * - 线程池 VoucherOrderHandler 是单线程的，保证了订单串行落库，避免数据库压力过大。
 * - 注意消息队列的积压监控，必要时动态增加消费者。
 * ============================================================================
 */

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private IUserService userService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserProfileUpdater userProfileUpdater;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private IShopService shopService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private VoucherOrderHandler voucherOrderHandler;

    @PostConstruct
    private void init() {
        try {
            stringRedisTemplate.opsForStream().createGroup("stream.orders", "g1");
        } catch (Exception e) {
            // 可能组已存在，忽略异常
            log.debug("消费者组已存在或创建失败", e);
        }
        voucherOrderHandler = new VoucherOrderHandler();
        SECKILL_ORDER_EXECUTOR.submit(voucherOrderHandler);
    }

    @PreDestroy
    public void destroy() {
        if (voucherOrderHandler != null) {
            voucherOrderHandler.stop();  // 通知线程停止
        }
        SECKILL_ORDER_EXECUTOR.shutdownNow();
        log.info("秒杀订单处理线程已关闭");
    }

    private class VoucherOrderHandler implements Runnable {
        private volatile boolean running = true;

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    // 1. 读取消息（阻塞 2 秒）
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    if (list.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 2. 尝试创建订单
                    boolean success = createVoucherOrderSafe(voucherOrder);
                    // 3. 无论成功还是业务失败，都要确认消息（XACK）
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                    if (!success) {
                        log.warn("订单创建失败（业务原因），已确认并丢弃消息: {}", record.getId());
                    }
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 对于系统异常（如网络超时），不确认消息，让消息留在 pending 中稍后重试
                    // 但需要避免无限重试，可以增加延迟或限制重试次数
                    try {
                        Thread.sleep(1000); // 延迟 1 秒后继续
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        private void handlePendingList() {
            while (running) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrderSafe(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending订单异常", e);
                    if (e instanceof IllegalStateException && e.getMessage().contains("destroyed")) {
                        running = false;
                        break;
                    }
                }
            }
        }
    }

    @Override
    public boolean createVoucherOrderSafe(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            log.warn("重复下单，用户: {}", userId);
            return false;
        }

        try {
            // 查询订单（只统计未使用和已使用的订单）
            int count = Math.toIntExact(query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .in("status", 0, 1)
                    .count());
            if (count > 0) {
                log.warn("用户已购买过该秒杀券，用户: {}, 券: {}", userId, voucherId);
                return false;
            }

            // 扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                log.warn("数据库库存不足，券: {}", voucherId);
                return false;
            }

            // 扣减余额
            Voucher voucher = voucherService.getById(voucherId);
            int payValue = Math.toIntExact(voucher.getPayValue());
            try {
                userService.updateBalance(userId, -payValue);
            } catch (Exception e) {
                log.error("余额不足，用户ID: {}, 优惠券ID: {}", userId, voucherId);
                return false;
            }
            // ========== 生成二维码内容 ==========
            String qrCode = "VOUCHER_" + voucherOrder.getId();   // id 已经是字符串
            voucherOrder.setQrCode(qrCode);
            // ======================================
            // 创建订单
            save(voucherOrder);
            // ========== 异步更新用户画像 ==========
            userProfileUpdater.updateFromVoucherOrder(voucherOrder, voucher);
            return true;
            // ========================================
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.返回订单id
        return Result.ok(String.valueOf(orderId));
    }

    @Override
    public Result orderCommonVoucher(Long voucherId) {

        // 1. 查询优惠券
        Voucher voucher = voucherService.getById(voucherId);
        Long userId = UserHolder.getUser().getId();

        // 2. 扣减余额（购买价 pay_value）
        int payValue = Math.toIntExact(voucher.getPayValue());
        try {
            userService.updateBalance(userId, -payValue);
        } catch (Exception e) {
            log.warn("用户 {} 余额不足，无法购买优惠券 {}", userId, voucherId);
            return Result.fail("余额不足");
        }

        // 3. 生成订单
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder order = new VoucherOrder();
        order.setId(String.valueOf(orderId));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(VoucherOrder.STATUS_UNUSED);
        // ========== 新增：生成二维码内容 ==========
        String qrCode = "VOUCHER_" + orderId;   // 规则：VOUCHER_ + 订单ID
        order.setQrCode(qrCode);
        // ======================================
        save(order);
        // ========== 新增：异步更新用户画像 ==========
        userProfileUpdater.updateFromVoucherOrder(order, voucher);
        // ========================================

        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public Result receiveVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 1. 查询优惠券
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }

        // 2. 校验是否为满减券（type=2）
        if (voucher.getType() != 2) {
            return Result.fail("该券不可免费领取");
        }

        // 3. 校验库存（如果有库存限制）
        if (voucher.getStock() != null && voucher.getStock() <= 0) {
            return Result.fail("库存不足");
        }

        // 4. 防止重复领取（同一用户同一券限领一张）
        int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherId).count());
        if (count > 0) {
            return Result.fail("您已领取过该优惠券");
        }

        // 5. 创建订单（支付金额为0）
        VoucherOrder order = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        order.setId(String.valueOf(orderId));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(0);
        order.setCreateTime(LocalDateTime.now());
        order.setExpireTime(LocalDateTime.now().plusDays(30));
        order.setQrCode("VOUCHER_" + orderId); // 生成二维码内容

        // 6. 扣减库存（如果有）
        if (voucher.getStock() != null) {
            boolean success = voucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!success) {
                return Result.fail("库存不足，领取失败");
            }
        }

        // 7. 保存订单
        save(order);

        // 8. 更新用户画像（可选，注意需要注入 userProfileUpdater）
        userProfileUpdater.updateFromVoucherOrder(order, voucher);

        return Result.ok(orderId);
    }
    public Result orderVoucher(Long id) {
        Voucher voucher = voucherService.getById(id);
        if (voucher.getStatus() != 1) {
            return Result.fail("优惠券已下架");
        }
        if (voucher.getType() == 0) {
            return orderCommonVoucher(id);
        } else if(voucher.getType() == 1) {
            return seckillVoucher(id);
        } else if(voucher.getType() == 2) {
            return receiveVoucher(id);
        } else {
            return Result.fail("未知的优惠券类型");
        }
    }

    @Override
    public Result getUserVouchers() {
        Long userId = UserHolder.getUser().getId();
        log.debug("查询用户 {} 的所有优惠券", userId);
        List<UserVoucherVO> list = voucherOrderMapper.selectUserVouchers(userId, null);
        fillStatusDesc(list);
        return Result.ok(list);
    }

    @Override
    public Result getUserVouchersByStatus(Integer status) {
        Long userId = UserHolder.getUser().getId();
        log.debug("查询用户 {} 的状态 {} 优惠券", userId, status);
        List<UserVoucherVO> list = voucherOrderMapper.selectUserVouchers(userId, status);
        fillStatusDesc(list);
        return Result.ok(list);
    }

    /**
     * 根据 status 数字填充文字描述
     */
    private void fillStatusDesc(List<UserVoucherVO> list) {
        for (UserVoucherVO vo : list) {
            if (vo.getStatus() == null) {
                vo.setStatusDesc("未知");
                continue;
            }
            switch (vo.getStatus()) {
                case VoucherOrder.STATUS_UNUSED:
                    vo.setStatusDesc("未使用");
                    break;
                case VoucherOrder.STATUS_USED:
                    vo.setStatusDesc("已使用");
                    break;
                case VoucherOrder.STATUS_EXPIRED:
                    vo.setStatusDesc("已过期");
                    break;
                case VoucherOrder.STATUS_CANCELLED:
                    vo.setStatusDesc("已取消");
                    break;
                default:
                    vo.setStatusDesc("未知");
            }
        }
    }

    @Transactional
    public void updateExpiredStatus() {
        // 更新 status = 0 且 expire_time < NOW() 的记录为 status = 2
        int updated = baseMapper.update(null,
                new LambdaUpdateWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getStatus, 0)
                        .lt(VoucherOrder::getExpireTime, LocalDateTime.now())
                        .set(VoucherOrder::getStatus, 2)
        );
        if (updated > 0) {
            log.info("定时任务：更新了 {} 条过期优惠券订单状态为 2", updated);
        }
    }

    @Override
    @Transactional
    public Result useVoucher(String orderId) {
        // 1. 查询订单
        VoucherOrder order = this.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        // 2. 校验当前用户是否是该订单的主人
        Long userId = UserHolder.getUser().getId();
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权操作");
        }
        // 3. 校验状态：必须为 0（未使用）
        if (order.getStatus() != VoucherOrder.STATUS_UNUSED) {
            return Result.fail("优惠券已使用或已过期");
        }
        // 4. 校验有效期（如果订单表有 expire_time 字段）
        if (order.getExpireTime() != null && order.getExpireTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠券已过期");
        }
        // 5. 更新状态为 1（已使用），记录使用时间
        order.setStatus(1);
        order.setUseTime(LocalDateTime.now());
        boolean updated = this.updateById(order);
        if (updated) {
            // 增加店铺销量
            Voucher voucher = voucherService.getById(order.getVoucherId());
            if (voucher != null && voucher.getShopId() != null) {
                shopService.incrSold(voucher.getShopId());
                log.info("核销优惠券成功，店铺 {} 销量+1", voucher.getShopId());
            }
            return Result.ok("核销成功");
        } else {
            return Result.fail("核销失败，请稍后重试");
        }
    }

    @Override
    @Transactional
    public Result cancelOrder(String orderId) {
        // 1. 查询订单
        VoucherOrder order = this.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        // 2. 校验当前用户
        Long userId = UserHolder.getUser().getId();
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权操作");
        }
        // 3. 校验状态：必须为 0（未使用）
        if (order.getStatus() != VoucherOrder.STATUS_UNUSED) {
            return Result.fail("只有未使用的优惠券才能取消");
        }
        // 4. 校验有效期：已过期不能取消
        if (order.getExpireTime() != null && order.getExpireTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠券已过期，无法取消");
        }
        // 5. 查询优惠券信息，获取支付金额
        Voucher voucher = voucherService.getById(order.getVoucherId());
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 6. 恢复余额
        int payValue = Math.toIntExact(voucher.getPayValue());
        userService.updateBalance(userId, payValue);
        // 7. 更新状态为已取消
        order.setStatus(VoucherOrder.STATUS_CANCELLED);
        // 8. 记录取消时间
        order.setRefundTime(LocalDateTime.now());
        boolean updated = this.updateById(order);
        if (!updated) {
            return Result.fail("取消失败，请稍后重试");
        }
        // 9. 如果优惠券是秒杀券（type=1），需要恢复库存（数据库 + Redis）
        if (voucher.getType() == 1) {
            // 9.1 恢复数据库库存（tb_seckill_voucher）
            boolean dbSuccess = seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", voucher.getId())
                    .update();
            if (!dbSuccess) {
                log.warn("恢复数据库库存失败，订单号：{}", orderId);
                // 注意：这里不抛异常，因为订单已取消，后续可人工处理
            }
            // 9.2 恢复 Redis 库存（秒杀时扣减的 key）
            String redisKey = "seckill:stock:" + voucher.getId();
            Long newStock = stringRedisTemplate.opsForValue().increment(redisKey);
            log.debug("恢复 Redis 库存成功，当前库存：{}", newStock);
            // 9.3 关键：从防重复下单的 Set 集合中移除该用户
            String orderKey = "seckill:order:" + order.getVoucherId();
            Long removed = stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());
            if (removed == 0) {
                log.warn("从防重复集合中移除用户失败，订单号：{}，用户：{}，key：{}", orderId, userId, orderKey);
            } else {
                log.debug("取消订单后已从防重复集合中移除用户，订单号：{}，用户：{}", orderId, userId);
            }
        }
        return Result.ok("取消成功");
    }
}
