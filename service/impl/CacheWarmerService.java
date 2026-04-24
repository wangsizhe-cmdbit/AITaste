package com.AITaste.service.impl;

import com.AITaste.entity.Shop;
import com.AITaste.service.IShopService;
import com.AITaste.utils.CacheClient;
import com.alibaba.fastjson2.JSON;


import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.AITaste.utils.RedisConstants.*;

@Service
@Slf4j
@EnableScheduling
public class CacheWarmerService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;

    @Resource
    private ChatClient chatClient;

    @Resource
    private CacheClient cacheClient;

    // 每5分钟执行一次
    @Scheduled(cron = "0 */5 * * * ?")
    public void warmUpHotShops() {
        log.info("开始AI预测热点店铺...");
        // 1. 获取过去1小时的访问日志（每5分钟一个key，共12个）
        List<String> logKeys = getRecentLogKeys(12);
        Map<Long, Integer> accessCount = new HashMap<>();
        for (String key : logKeys) {
            Set<String> members = stringRedisTemplate.opsForSet().members(key);
            if (members != null) {
                for (String id : members) {
                    accessCount.merge(Long.valueOf(id), 1, Integer::sum);
                }
            }
        }
        if (accessCount.isEmpty()) {
            log.info("无访问日志，跳过预热");
            return;
        }

        // 2. 调用AI预测热点店铺（只返回前5个ID）
        String prompt = String.format(
                "你是一个缓存热点预测专家。根据以下店铺在过去1小时的访问次数（JSON格式），预测未来5分钟最可能成为热点的5个店铺ID。" +
                        "只返回JSON数组，例如[123,456,789,101,112]。数据：%s",
                JSON.toJSONString(accessCount)
        );
        String aiResult = chatClient.prompt().user(prompt).call().content();
        log.info("AI预测结果: {}", aiResult);
        List<Long> hotShopIds;
        try {
            hotShopIds = JSON.parseArray(aiResult, Long.class);
        } catch (Exception e) {
            log.error("解析AI结果失败", e);
            hotShopIds = new ArrayList<>();
        }
        if (hotShopIds == null || hotShopIds.isEmpty()) return;

        // 3. 刷新这些店铺的缓存（预热）
        for (Long shopId : hotShopIds) {
            refreshShopCache(shopId);
        }
        log.info("预热完成，店铺: {}", hotShopIds);
    }

    private void refreshShopCache(Long shopId) {
        Shop shop = shopService.getById(shopId);
        if (shop != null) {
            long ttl = getDynamicTtl(shop);
            // 使用逻辑过期写入
            cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + shopId, shop, ttl, TimeUnit.SECONDS);
        } else {
            // 空值缓存（可选）
            cacheClient.set(CACHE_SHOP_KEY + shopId, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        }
    }

    private long getDynamicTtl(Shop shop) {
        // 基础TTL 30分钟
        long base = LOGIC_EXPIRE_SECONDS;
        // 热度加成：根据评论数，每100条评论增加1分钟，最多增加10分钟
        int hotBonus = Math.min(shop.getComments() / 100, 10) * 60;
        // 随机偏移，防雪崩（0~5分钟）
        int randomOffset = new Random().nextInt(5 * 60);
        return base + hotBonus + randomOffset;
    }

    private List<String> getRecentLogKeys(int count) {
        List<String> keys = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < count; i++) {
            String time = now.minusMinutes(5L * i).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            keys.add(SHOP_ACCESS_LOG_PREFIX + time);
        }
        return keys;
    }
}