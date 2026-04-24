package com.AITaste.service;


import com.AITaste.VO.CartItemVO;

import java.util.List;
import java.util.Map;

public interface ICartService {
    void addItem(Long userId, Long shopId, Long dishId, Integer quantity);
    void updateQuantity(Long userId, Long shopId, Long dishId, Integer quantity);
    List<CartItemVO> getCart(Long userId, Long shopId);
    int getTotalAmount(Long userId, Long shopId);
    void clearCart(Long userId, Long shopId);
    Map<Long, List<CartItemVO>> getCartGroupByShop(Long userId);
    boolean hasCartForShop(Long userId, Long shopId);
}