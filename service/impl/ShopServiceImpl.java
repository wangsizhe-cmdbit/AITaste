package com.AITaste.service.impl;

import cn.hutool.core.util.StrUtil;
import com.AITaste.VO.ShopRecommendVO;
import com.AITaste.dto.Result;
import com.AITaste.entity.Dish;
import com.AITaste.entity.Shop;
import com.AITaste.entity.UserProfile;
import com.AITaste.mapper.ShopMapper;
import com.AITaste.service.IDishService;
import com.AITaste.service.IShopService;
import com.AITaste.service.IUserProfileService;
import com.AITaste.utils.AITools;
import com.AITaste.utils.CacheClient;
import com.AITaste.utils.SystemConstants;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.AITaste.utils.RedisConstants.*;

/**
 * 面试高频考点 & 实战答案（基于此店铺缓存服务实现）
 * ============================================================================
 * 1. 缓存穿透、缓存击穿、缓存雪崩分别是什么？如何解决？
 *    答：
 *    - 缓存穿透：查询一个不存在的数据，请求直接打到数据库。解决方案：缓存空对象（设置短TTL）或布隆过滤器。
 *    - 缓存击穿：热点key过期瞬间，大量并发请求重建缓存，压垮数据库。解决方案：互斥锁（只允许一个线程重建）或逻辑过期（异步重建）。
 *    - 缓存雪崩：大量key同时过期，或Redis宕机。解决方案：给过期时间加随机偏移量、Redis集群、多级缓存。
 *
 * 2. 本服务中如何解决缓存穿透？
 *    答：CacheClient.queryWithPassThrough 方法中，若数据库查询结果为空，会将空值（空字符串）写入Redis，
 *       并设置较短的过期时间（CACHE_NULL_TTL，例如2分钟）。这样后续相同key的请求会直接返回空，不会穿透到数据库。
 *       同时，本服务也通过 queryWithLogicalExpire 结合空值判断处理穿透（逻辑过期模式下空值直接返回null）。
 *
 * 3. 互斥锁解决缓存击穿的原理？本服务中如何实现？
 *    答：原理：当某个key过期后，第一个请求获取互斥锁并重建缓存，其他请求等待或重试。
 *       本服务中 queryWithMutex 方法（已注释但可参考）使用 Redis 的 setIfAbsent（setnx）实现锁，
 *       锁key为 "lock:shop:" + id，过期时间10秒（防止死锁）。获取锁失败则休眠50ms后递归重试；
 *       获取锁成功则查询数据库写入Redis，最后释放锁。注意使用 try-finally 保证释放。
 *
 * 4. 逻辑过期解决缓存击穿的思路？相比互斥锁有何优点？
 *    答：逻辑过期不设置Redis的物理过期时间，而是给缓存数据增加一个过期时间字段（expireTime）。
 *       查询时判断是否逻辑过期：若未过期直接返回；若已过期，尝试获取互斥锁，成功则开启独立线程异步重建缓存，
 *       当前线程立即返回旧数据。优点：用户体验好（不等待），高并发下不会阻塞其他线程。缺点：可能返回脏数据。
 *       本服务通过 CacheClient.queryWithLogicalExpire 实现，并提交给线程池 CACHE_REBUILD_EXECUTOR 异步重建。
 *
 * 5. 缓存更新策略有哪些？本服务采用什么策略？
 *    答：常见策略：① Cache Aside（旁路缓存）：先更新数据库，再删除缓存；② Read/Write Through；③ Write Behind。
 *       本服务在 update 方法中采用 Cache Aside 策略：先更新数据库（@Transactional），再删除缓存。
 *       这样可以避免更新数据库和写缓存的不一致问题（删除缓存后，下次查询会重新加载最新数据）。
 *
 * 6. 为什么不采用“先删除缓存，再更新数据库”？
 *    答：先删缓存再更新数据库，在并发下可能出现：线程A删缓存后还未更新DB，线程B读缓存未命中，从DB读到旧数据并写入缓存，
 *       导致缓存中长时间为脏数据。先更新DB再删缓存，虽然也有短暂不一致窗口（缓存删除前读到旧数据），但概率低且影响小。
 *
 * 7. 为什么更新操作不采用“更新缓存”而是“删除缓存”？
 *    答：更新缓存需要保证缓存与数据库完全一致，但若字段多或更新频繁，容易产生并发写冲突，且可能多次无效更新。
 *       删除缓存是懒加载思想，等到下次读请求时再重建，实现简单且最终一致。
 *
 * 8. 缓存重建过程中如何避免大量线程同时操作数据库？
 *    答：通过互斥锁或逻辑过期+独立线程池，保证同时只有一个线程（或一个线程池）进行数据库查询和缓存写入。
 *       本服务的互斥锁方案只允许一个线程获取锁执行重建，其他线程等待或重试；逻辑过期方案则只允许一个线程触发异步重建。
 *
 * 9. 线程池 CACHE_REBUILD_EXECUTOR 的大小为什么是10？如何选择？
 *    答：10是示例值，实际应根据系统并发量、数据库连接池大小、CPU核心数等调整。一般设置为CPU密集型任务：N+1，
 *       IO密集型任务：2N。缓存重建涉及数据库查询和网络IO，可适当增大。同时需监控队列积压情况。
 *
 * 10. 如何保证删除缓存操作的原子性？若删除失败怎么办？
 *     答：删除缓存是非原子操作，但在Cache Aside模式中，删除失败只会导致短时间内数据不一致（缓存仍为旧数据），
 *        可以通过重试机制（消息队列）或设置较短的缓存过期时间来兜底。本服务中直接删除，未做重试，生产环境可改进。
 *
 * 11. 本服务中的 CacheClient 是什么？为什么要抽取工具类？
 *     答：CacheClient 是自定义的缓存操作工具类，封装了三种缓存解决方案（穿透、互斥锁、逻辑过期），
 *        避免在每个业务方法中重复编写样板代码，提高复用性和可维护性。体现了“关注点分离”的设计原则。
 *
 * 12. 如果数据库更新成功，但删除缓存失败（如Redis宕机），如何保证最终一致性？
 *     答：① 设置合理的缓存过期时间作为兜底；② 将删除失败的消息发送到消息队列，异步重试删除；
 *       ③ 监听binlog（如Canal），当数据库更新时自动删除缓存。本示例未实现，但面试时可说明这些方案。
 *
 * 实战要点：
 * - 对于热点数据，应提前预热缓存，避免大量请求同时击穿。
 * - 互斥锁的锁key必须唯一，且设置合理的超时时间，防止死锁。
 * - 逻辑过期适合对数据一致性要求不高、但要求高可用性的场景（如商品详情页）。
 * - 缓存空对象可有效防止穿透，但会占用内存，需设置较短的过期时间。
 * - 使用@Transactional确保数据库更新和缓存删除的顺序？实际无法保证，因为缓存删除在事务外，应接受最终一致性。
 * - 建议监控缓存命中率、重建耗时等指标，及时调整策略。
 * ============================================================================
 */

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    @Lazy
    private ChatClient chatClient;

    @Resource
    private IDishService dishService;

    @Resource
    @Lazy
    private BloomFilterService bloomFilterService;

    @Resource
    private IUserProfileService userProfileService;

    @Resource
    @Lazy
    private AITools aiTools;

    // 用于异步延迟删除的线程池
    private static final ExecutorService DELAY_EXECUTOR = Executors.newFixedThreadPool(5);

    @Override
    public Result queryById(Long id) {
        // 1. 布隆过滤器快速过滤不存在的 ID
        if (!bloomFilterService.mightContain(id)) {
            return Result.fail("店铺不存在！");
        }
        // 2. 使用逻辑过期查询（永不阻塞，异步刷新）
        String key = CACHE_SHOP_KEY + id;
        Shop shop = cacheClient.queryWithLogicalExpire(key, id, Shop.class,
                this::getById, LOGIC_EXPIRE_SECONDS, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 3. 异步记录访问热度（可选，用于 AI 预测）
        // 引用内部方法
        recordAccessHot(id);
        return Result.ok(shop);
    }

    // 记录访问热度（每访问一次，增加热度计数）
    // 内部方法
    private void recordAccessHot(Long shopId) {
        String hotKey = SHOP_HOT_SCORE_PREFIX + shopId;
        stringRedisTemplate.opsForValue().increment(hotKey);
        stringRedisTemplate.expire(hotKey, 1, TimeUnit.HOURS); // 热度有效期1小时
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 根据热度决定立即删除还是延迟删除缓存
        // 引用内部方法
        int hotScore = getHotScore(id);
        if (hotScore > 100) {
            // 热点店铺：延迟 5 秒删除，避免缓存雪崩
            // 引用内部方法
            scheduleCacheDeletion(id, 5);
        } else {
            // 冷门店铺：立即删除
            cacheClient.delete(CACHE_SHOP_KEY + id);
        }
        return Result.ok();
    }
    // 获取店铺热度（最近1小时访问次数）
    // 内部方法
    private Integer getHotScore(Long shopId) {
        String hotKey = SHOP_HOT_SCORE_PREFIX + shopId;
        String val = stringRedisTemplate.opsForValue().get(hotKey);
        return val == null ? 0 : Integer.parseInt(val);
    }

    // 异步延迟删除缓存
    // 内部方法
    private void scheduleCacheDeletion(Long shopId, int delaySeconds) {
        DELAY_EXECUTOR.submit(() -> {
            try {
                Thread.sleep(delaySeconds * 1000L);
                cacheClient.delete(CACHE_SHOP_KEY + shopId);
                log.info("延迟删除缓存: shopId={}, 延迟{}秒", shopId, delaySeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public Result queryAllShops(Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        SHOP_GEO_KEY,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(1000000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

    @Override
    public Result queryShopsByName(String name, Integer current, Integer size) {
        // 构建分页对象
        Page<Shop> pageParam = new Page<>(current, size == null ? SystemConstants.DEFAULT_PAGE_SIZE : size);
        // 构建查询条件：name 模糊匹配（非空时）
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(name)) {
            wrapper.like(Shop::getName, name);
        }
        // 执行分页查询
        Page<Shop> shopPage = this.page(pageParam, wrapper);
        // 返回数据列表
        return Result.ok(shopPage.getRecords());
    }
    @Override
    public Result queryShopsByArea(String area, Integer pageNum) {
        Page<Shop> page = new Page<>(pageNum, SystemConstants.DEFAULT_PAGE_SIZE);
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<>();
        if (area != null && !area.isEmpty()) {
            wrapper.like(Shop::getArea, area).or().like(Shop::getAddress, area);
        }
        Page<Shop> result = this.page(page, wrapper);
        return Result.ok(result.getRecords());
    }

    @Override
    public Result getAiSummary(Long shopId) {
        // 1. 参数校验
        if (shopId == null) {
            return Result.fail("店铺ID不能为空");
        }

        // 2. 缓存查询
        String cacheKey = "shop:ai:summary:" + shopId;
        String cachedHtml = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cachedHtml)) {
            return Result.ok(cachedHtml);
        }

        // 3. 查询店铺信息
        Shop shop = getById(shopId);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        // 4. 查询店铺上架的菜品（最多8道）
        List<Dish> dishes = dishService.lambdaQuery()
                .eq(Dish::getShopId, shopId)
                .eq(Dish::getStatus, 1)
                .last("limit 8")
                .list();

        // 5. 构建AI提示词
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一位资深美食探店博主，请根据以下信息为餐厅写一段120~180字的推荐总结，语气热情、有感染力，突出餐厅特色和必点菜，最后用一句话吸引顾客购买优惠券。\n");
        prompt.append("餐厅名称：").append(shop.getName()).append("\n");
        prompt.append("口味标签：").append(shop.getTasteTag() != null ? shop.getTasteTag() : "多种口味").append("\n");
        prompt.append("人均价格：").append(shop.getAvgPrice()).append("元\n");
        prompt.append("地址：").append(shop.getAddress()).append("\n");
        prompt.append("评分：").append(String.format("%.1f", shop.getScore() / 10.0)).append("分（满分5分）\n");
        prompt.append("营业时间：").append(shop.getOpenHours()).append("\n");
        if (!dishes.isEmpty()) {
            prompt.append("招牌菜品：");
            for (Dish dish : dishes) {
                prompt.append(dish.getName()).append("（").append(dish.getPrice() / 100).append("元）、");
            }
            prompt.setLength(prompt.length() - 1); // 去掉最后的顿号
            prompt.append("\n");
        }
        prompt.append("要求：\n");
        prompt.append("- 突出环境、口味、性价比\n");
        prompt.append("- 推荐2-3道必点菜，并简单描述口味\n");
        prompt.append("- 最后一句用“快用优惠券来尝尝吧～”或类似鼓励话术\n");
        prompt.append("- 不要使用列表符号，写成连贯的自然段落，可适当使用表情符号。");

        String htmlSummary;
        try {
            // 6. 调用AI生成原始文本
            String summary = chatClient.prompt().user(prompt.toString()).call().content();
            if (summary.trim().isEmpty()) {
                // 引用内部方法
                htmlSummary = getFallbackHtml(shop, dishes);
            } else {
                // 7. 后处理：Markdown转HTML、段落拆分、增加引导话术
                // 引用内部方法
                htmlSummary = processAiSummary(summary, shop);
            }
        } catch (Exception e) {
            log.error("AI生成店铺总结失败, shopId={}", shopId, e);
            htmlSummary = getFallbackHtml(shop, dishes);
        }

        // 8. 存入缓存（有效期24小时）
        stringRedisTemplate.opsForValue().set(cacheKey, htmlSummary, Duration.ofDays(1));

        return Result.ok(htmlSummary);
    }

    /**
     * 处理AI返回的原始文本，转换为适合前端展示的HTML
     */
    private String processAiSummary(String raw, Shop shop) {
        // 1. 转换Markdown基础语法
        String processed = raw
                .replaceAll("\\*\\*(.*?)\\*\\*", "<strong>$1</strong>")   // **加粗**
                .replaceAll("\\*(.*?)\\*", "<em>$1</em>");                 // *斜体*

        // 2. 自动为重点信息添加 <strong> 标签（可根据需要增加更多规则）
        // 人均价格
        processed = processed.replaceAll("(人均\\s*\\d+元)", "<strong>$1</strong>");
        // 口味标签
        processed = processed.replaceAll("(口味[：:]\\s*[^，。;；]+)", "<strong>$1</strong>");
        // 推荐/必点菜品（匹配“推荐xxx”或“必点xxx”）
        processed = processed.replaceAll("(推荐|必点)\\s*([^，。;；]+)", "<strong>$1$2</strong>");
        // 价格数字（如 58元）
        processed = processed.replaceAll("(\\d+元)", "<strong>$1</strong>");
        // 店铺名称（可选）
        processed = processed.replaceAll(shop.getName(), "<strong>" + shop.getName() + "</strong>");

        // 3. 按两个连续换行分割段落
        String[] paragraphs = processed.split("\n\\s*\n");
        StringBuilder html = new StringBuilder();
        for (String para : paragraphs) {
            String line = para.replace("\n", " ").trim();
            if (!line.isEmpty()) {
                html.append("<p>").append(line).append("</p>");
            }
        }

        // 4. 如果没有段落（即AI没有使用双换行），则按单换行处理
        if (html.length() == 0) {
            html.append("<p>").append(processed.replace("\n", "<br>")).append("</p>");
        }

        // 5. 如果AI没有提到优惠券，追加引导话术
        if (!raw.contains("优惠券") && !raw.contains("券")) {
            html.append("<p class='voucher-guide'>💡 快用优惠券来尝尝吧～</p>");
        }

        return html.toString();
    }

    // 内部方法
    /**
     * AI调用失败时的降级HTML
     */
    private String getFallbackHtml(Shop shop, List<Dish> dishes) {
        String dishHint = dishes.isEmpty() ? "店内菜品丰富" : "推荐尝试" + dishes.get(0).getName();
        return String.format("<p>✨ %s 是一家%s口味的餐厅，人均约%d元。%s。快来领取优惠券体验吧！</p>",
                shop.getName(),
                shop.getTasteTag() != null ? shop.getTasteTag() : "特色",
                shop.getAvgPrice(),
                dishHint);
    }

    @Override
    public List<ShopRecommendVO> getAiSmartPick(Long userId, Double x, Double y) {
        if (userId == null) {
            userId = -1L;  // 游客标识
        }
        String cacheKey = "home:smartpick:" + userId + ":" + (x != null ? x : 0) + ":" + (y != null ? y : 0);

        // 1. 尝试从 Redis 获取缓存（30分钟）
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cachedJson)) {
            return JSON.parseArray(cachedJson, ShopRecommendVO.class);
        }

        // 2. 获取用户画像
        UserProfile profile = null;
        if (userId > 0) {
            profile = userProfileService.getUserProfile(userId);
        }

        // 3. 获取候选店铺（基于坐标或常去区域）
        // 引用内部方法
        List<Shop> candidates = getCandidateShops(x, y, profile);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. 智能排序（画像匹配分60% + 热度20% + 距离20%）
        Map<Long, Integer> hotMap = getHotScores(candidates.stream().map(Shop::getId).collect(Collectors.toList()));
        // 使用临时 Map 存储总分，避免覆盖 shop.distance
        Map<Long, Double> scoreMap = new HashMap<>();
        for (Shop shop : candidates) {
            int matchScore = aiTools.calculateMatchScore(shop, profile);
            int hotScore = hotMap.getOrDefault(shop.getId(), 0);
            double distanceScore = (shop.getDistance() == null) ? 0 : Math.max(0, 1000 - shop.getDistance()) / 1000.0;
            double total = matchScore * 0.6 + (hotScore / 100.0) * 0.2 + distanceScore * 0.2;
            scoreMap.put(shop.getId(), total);  // 存入 Map，不修改 shop
        }
        // 按总分降序排序
        candidates.sort((a, b) -> Double.compare(scoreMap.get(b.getId()), scoreMap.get(a.getId())));
        List<Shop> topShops = candidates.stream().limit(5).collect(Collectors.toList());

        // 5. 使用 Spring AI 为每家店铺生成个性化推荐语
        List<ShopRecommendVO> result = new ArrayList<>();
        if (!topShops.isEmpty()) {
            // 引用内部方法
            String prompt = buildReasonPrompt(topShops, profile);
            String aiOutput = chatClient.prompt().user(prompt).call().content();

            // 提取 JSON 数组部分
            // 引用内部方法
            String jsonStr = extractJsonFromAiResponse(aiOutput);
            List<Map<String, Object>> reasonList;
            try {
                reasonList = JSON.parseObject(jsonStr, new TypeReference<List<Map<String, Object>>>() {});
            } catch (JSONException e) {
                log.error("AI 返回的 JSON 解析失败，原始内容：{}", aiOutput, e);
                reasonList = Collections.emptyList();
            }
            Map<Long, String> reasonMap = reasonList.stream()
                    .collect(Collectors.toMap(m -> Long.valueOf(m.get("shopId").toString()), m -> m.get("reason").toString()));

            for (Shop shop : topShops) {
                ShopRecommendVO vo = new ShopRecommendVO();
                vo.setShopId(shop.getId());
                vo.setName(shop.getName());
                vo.setCoverUrl(shop.getImages());   // 直接使用阿里云地址
                vo.setAvgPrice(Math.toIntExact(shop.getAvgPrice()));
                vo.setDistance(shop.getDistance());
                vo.setTasteTag(shop.getTasteTag());
                vo.setReason(reasonMap.getOrDefault(shop.getId(), "✨ 人气推荐，快来尝尝吧"));
                result.add(vo);
            }
        }

        // 6. 缓存结果
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(result), Duration.ofMinutes(30));
        return result;
    }

    private List<Shop> getCandidateShops(Double x, Double y, UserProfile profile) {
        if (x != null && y != null) {
            Result result = queryAllShops(1, x, y);
            if (result.getSuccess() && result.getData() != null) {
                return (List<Shop>) result.getData();
            }
        } else if (profile != null && profile.getFrequentArea() != null) {
            Result result = queryShopsByArea(profile.getFrequentArea(), 1);
            if (result.getSuccess() && result.getData() != null) {
                List<Shop> shops = (List<Shop>) result.getData();
                // 最多取20条
                return shops.stream().limit(20).collect(Collectors.toList());
            }
        }
        // 兜底：查询热度最高的20家（可根据销量或评分排序）
        return lambdaQuery().orderByDesc(Shop::getSold).last("limit 20").list();
    }

    // 批量获取店铺热度
    // 内部方法
    private Map<Long, Integer> getHotScores(List<Long> shopIds) {
        Map<Long, Integer> map = new HashMap<>();
        for (Long id : shopIds) {
            String val = stringRedisTemplate.opsForValue().get(SHOP_HOT_SCORE_PREFIX + id);
            map.put(id, val == null ? 0 : Integer.parseInt(val));
        }
        return map;
    }

    // 构建批量生成推荐语的 Prompt
    // 内部方法
    private String buildReasonPrompt(List<Shop> shops, UserProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深美食推荐官。请根据以下店铺信息和用户偏好，为每家店铺生成一句简短、吸引人的推荐理由（20字以内）。输出 JSON 数组，格式：[{\"shopId\":123,\"reason\":\"...\"}]\n");
        if (profile == null) {
            sb.append("用户偏好：多种口味，消费等级中等\n");
        } else {
            sb.append("用户偏好：").append(profile.getTaste() != null ? profile.getTaste() : "多种口味")
                    .append("口味，消费等级").append(profile.getAvgPriceLevel() != null ? profile.getAvgPriceLevel() : 2).append("\n");
        }
        for (Shop shop : shops) {
            sb.append("店铺ID:").append(shop.getId())
                    .append(", 名称:").append(shop.getName())
                    .append(", 标签:").append(shop.getTasteTag())
                    .append(", 人均:").append(shop.getAvgPrice()).append("元\n");
        }
        return sb.toString();
    }

    // 内部方法
    /**
     * 从 AI 返回的文本中提取 JSON 数组或对象
     * 支持格式：
     * - 纯 JSON
     * - 包含 Markdown 代码块 ```json ... ```
     * - 前后带有自然语言描述
     */
    private String extractJsonFromAiResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "[]";
        }
        // 1. 尝试提取 ```json ... ``` 中的内容
        Pattern pattern = Pattern.compile("```json\\s*(\\[\\s*\\{.*?\\}\\s*\\])\\s*```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // 2. 尝试提取 ``` ... ``` 中的内容（无 json 标识）
        pattern = Pattern.compile("```\\s*(\\[\\s*\\{.*?\\}\\s*\\])\\s*```", Pattern.DOTALL);
        matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // 3. 尝试直接查找第一个 [ 到最后一个 ] 之间的内容
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start != -1 && end != -1 && end > start) {
            return response.substring(start, end + 1);
        }
        // 4. 降级：返回空数组
        log.warn("无法从 AI 响应中提取 JSON，原始内容：{}", response);
        return "[]";
    }
}