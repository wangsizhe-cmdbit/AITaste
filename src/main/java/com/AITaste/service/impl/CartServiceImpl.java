package com.AITaste.service.impl;

import com.AITaste.VO.CartItemVO;
import com.AITaste.entity.Dish;
import com.AITaste.service.ICartService;
import com.AITaste.service.IDishService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CartServiceImpl implements ICartService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IDishService dishService;

    private static final String CART_KEY_PREFIX = "cart:";

    // 构建 Redis key: cart:{userId}:{shopId}
    private String getCartKey(Long userId, Long shopId) {
        return CART_KEY_PREFIX + userId + ":" + shopId;
    }

    @Override
    public void addItem(Long userId, Long shopId, Long dishId, Integer quantity) {
        String key = getCartKey(userId, shopId);
        String field = String.valueOf(dishId);
        String currentQtyStr = (String) stringRedisTemplate.opsForHash().get(key, field);
        int currentQty = currentQtyStr == null ? 0 : Integer.parseInt(currentQtyStr);
        int newQty = currentQty + quantity;
        if (newQty <= 0) {
            stringRedisTemplate.opsForHash().delete(key, field);
            // 如果该店铺的购物车为空（没有其他菜品），删除整个 key
            Long size = stringRedisTemplate.opsForHash().size(key);
            if (size == null || size == 0) {
                stringRedisTemplate.delete(key);
            }
        } else {
            stringRedisTemplate.opsForHash().put(key, field, String.valueOf(newQty));
        }
    }

    @Override
    public void updateQuantity(Long userId, Long shopId, Long dishId, Integer quantity) {
        String key = getCartKey(userId, shopId);
        String field = String.valueOf(dishId);
        if (quantity <= 0) {
            stringRedisTemplate.opsForHash().delete(key, field);
            // 检查是否还有菜品
            Long size = stringRedisTemplate.opsForHash().size(key);
            if (size == null || size == 0){
                stringRedisTemplate.delete(key);
            }
        } else {
            stringRedisTemplate.opsForHash().put(key, field, String.valueOf(quantity));
        }
    }

    @Override
    public List<CartItemVO> getCart(Long userId, Long shopId) {
        String key = getCartKey(userId, shopId);
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> dishIds = new ArrayList<>();
        Map<Long, Integer> quantityMap = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            Long dishId = Long.valueOf(entry.getKey().toString());
            Integer quantity = Integer.valueOf(entry.getValue().toString());
            dishIds.add(dishId);
            quantityMap.put(dishId, quantity);
        }
        if (dishIds.isEmpty()) return Collections.emptyList();
        List<Dish> dishes = dishService.listByIds(dishIds);
        return dishes.stream().map(dish -> {
            CartItemVO vo = new CartItemVO();
            vo.setDishId(dish.getId());
            vo.setDishName(dish.getName());
            vo.setPrice(dish.getPrice());
            vo.setQuantity(quantityMap.get(dish.getId()));
            vo.setTotalPrice(dish.getPrice() * vo.getQuantity());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public int getTotalAmount(Long userId, Long shopId) {
        List<CartItemVO> items = getCart(userId, shopId);
        return items.stream().mapToInt(CartItemVO::getTotalPrice).sum();
    }

    @Override
    public void clearCart(Long userId, Long shopId) {
        String key = getCartKey(userId, shopId);
        stringRedisTemplate.delete(key);
    }

    @Override
    public Map<Long, List<CartItemVO>> getCartGroupByShop(Long userId) {
        Set<String> keys = stringRedisTemplate.keys(CART_KEY_PREFIX + userId + ":*");
        Map<Long, List<CartItemVO>> result = new HashMap<>();
        for (String key : keys) {
            // 提取 shopId (key 格式: cart:{userId}:{shopId})
            String[] parts = key.split(":");
            if (parts.length < 3) continue;
            Long shopId = Long.valueOf(parts[2]);
            List<CartItemVO> items = getCart(userId, shopId);
            if (!items.isEmpty()) {
                result.put(shopId, items);
            }
        }
        return result;
    }

    @Override
    public boolean hasCartForShop(Long userId, Long shopId) {
        String key = getCartKey(userId, shopId);
        Long size = stringRedisTemplate.opsForHash().size(key);
        return size > 0;
    }
}