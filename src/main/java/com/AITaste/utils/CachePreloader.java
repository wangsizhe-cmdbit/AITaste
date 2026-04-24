package com.AITaste.utils;

import com.AITaste.entity.Shop;
import com.AITaste.service.IShopService;
import com.AITaste.service.impl.BloomFilterService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.AITaste.utils.RedisConstants.*;

@Component
@Slf4j
public class CachePreloader {
    @Autowired
    private IShopService shopService;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private BloomFilterService bloomFilterService;

    @PostConstruct
    public void preloadAllShops() {
        List<Shop> shops = shopService.list();
        for (Shop shop : shops) {
            String key = RedisConstants.CACHE_SHOP_KEY + shop.getId();
            // 使用逻辑过期写入
            cacheClient.setWithLogicalExpire(key, shop, LOGIC_EXPIRE_SECONDS, TimeUnit.SECONDS);
            // 加入布隆过滤器
            bloomFilterService.addShopId(shop.getId());
        }
        log.info("全量预热店铺缓存完成，数量：{}", shops.size());
    }
}