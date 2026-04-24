package com.AITaste.controller;

import com.AITaste.VO.CartItemVO;
import com.AITaste.dto.Result;
import com.AITaste.entity.DiningOrder;
import com.AITaste.service.ICartService;
import com.AITaste.service.IDiningOrderService;
import com.AITaste.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/dining-order")
public class DiningOrderController {

    @Autowired
    private IDiningOrderService diningOrderService;
    @Autowired
    private ICartService cartService;

    @PostMapping("/create")
    public Result create(@RequestParam Long shopId) {
        Long userId = UserHolder.getUser().getId();
        // 检查该店铺购物车是否有商品
        List<CartItemVO> cart = cartService.getCart(userId, shopId);
        if (cart.isEmpty()) {
            return Result.fail("购物车为空");
        }
        int totalAmount = cartService.getTotalAmount(userId, shopId);
        // 创建订单，暂不使用优惠券
        DiningOrder order = diningOrderService.createOrder(userId, shopId, totalAmount, null, totalAmount);
        // 清空该店铺购物车
        cartService.clearCart(userId, shopId);
        return Result.ok(order.getId());
    }

    @PostMapping("/pay/{orderId}")
    public Result pay(@PathVariable Long orderId) {
        diningOrderService.payOrder(orderId);
        return Result.ok();
    }
}