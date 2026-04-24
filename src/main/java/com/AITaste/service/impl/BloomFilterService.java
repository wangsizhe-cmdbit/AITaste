package com.AITaste.service.impl;

import com.AITaste.entity.Shop;
import com.AITaste.service.IShopService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class BloomFilterService {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    @Lazy
    private IShopService shopService;

    private RBloomFilter<Long> bloomFilter;

    @PostConstruct
    public void init() {
        // 初始化布隆过滤器，预计容量10000，误判率1%
        bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");
        bloomFilter.tryInit(10000L, 0.01);
        // 从数据库加载所有有效的店铺 ID
        loadAllShopIds();
        log.info("布隆过滤器初始化完成，当前已加载店铺数量: {}", bloomFilter.count());
    }

    public void addShopId(Long shopId) {
        bloomFilter.add(shopId);
    }

    public boolean mightContain(Long shopId) {
        return bloomFilter.contains(shopId);
    }

    private void loadAllShopIds() {
        // 查询所有上架状态的店铺 ID（假设 status=1 表示上架）
        List<Shop> shops = shopService.lambdaQuery()
                .list();
        for (Shop shop : shops) {
            bloomFilter.add(shop.getId());
        }
        log.info("从数据库加载店铺 ID 数量: {}", shops.size());
    }
}