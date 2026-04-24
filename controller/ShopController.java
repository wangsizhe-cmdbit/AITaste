package com.AITaste.controller;

import com.AITaste.dto.Result;
import com.AITaste.entity.Shop;
import com.AITaste.service.IShopService;
import com.AITaste.service.IUserProfileService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import static com.AITaste.utils.RedisConstants.*;


@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    @Resource
    private IUserProfileService userProfileService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //查询所有店铺
    @GetMapping("/all")
    public Result queryAllShops(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(required = false) Double x,
            @RequestParam(required = false) Double y) {
        return shopService.queryAllShops(current, x, y);
    }

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id,
                                @RequestParam Long userId) {
        // 记录访问日志（用于 AI 预热）
        String minute = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String logKey = SHOP_ACCESS_LOG_PREFIX + minute;
        stringRedisTemplate.opsForSet().add(logKey, id.toString());
        stringRedisTemplate.expire(logKey, 10, TimeUnit.MINUTES);

        // 查询商铺信息
        Result result = shopService.queryById(id);

        if (result.getData() != null) {
            Shop shop = (Shop) result.getData();
            String address = shop.getAddress();
            String area = extractArea(address);  // 调用提取方法
            if (area != null) {
                userProfileService.updateTag(userId, "frequentArea", area);
            }
        }
        // 返回商铺信息
        return result;
    }

    /**
     * 从地址中提取区域（示例：取第一个“区”字之前的内容 + “区”）
     */
    private String extractArea(String address) {
        if (address != null && address.contains("区")) {
            int index = address.indexOf("区");
            return address.substring(0, index + 1);
        }
        return null;
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 写入数据库
        return shopService.update(shop);
    }

    /**
     * 关键字
     * @param keyword
     * @param page
     * @param size
     * @return
     */
    @GetMapping("/of/name")
    public Result queryShopByKeyword(@RequestParam String keyword,
                                     @RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "10") Integer size
                                     ) {
        // 执行原有搜索逻辑（调用 service 查询店铺列表）
        Result result = shopService.queryShopsByName(keyword, page, size);
        return result;
    }

    @GetMapping("/ai-summary/{shopId}")
    public Result getAiSummary(@PathVariable Long shopId) {
        return shopService.getAiSummary(shopId);
    }
}