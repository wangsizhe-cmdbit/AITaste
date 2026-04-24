package com.AITaste.controller;


import com.AITaste.VO.CartItemVO;
import com.AITaste.dto.Result;
import com.AITaste.service.ICartService;
import com.AITaste.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/cart")
public class CartController {

    @Autowired
    private ICartService cartService;

    @PostMapping("/add")
    public Result add(@RequestParam Long shopId, @RequestParam Long dishId, @RequestParam Integer quantity) {
        Long userId = UserHolder.getUser().getId();
        cartService.addItem(userId, shopId, dishId, quantity);
        return Result.ok();
    }

    @PostMapping("/update")
    public Result update(@RequestParam Long shopId,@RequestParam Long dishId, @RequestParam Integer quantity) {
        Long userId = UserHolder.getUser().getId();
        cartService.updateQuantity(userId, shopId, dishId, quantity);
        return Result.ok();
    }

    @GetMapping("/list")
    public Result list(@RequestParam Long shopId) {
        Long userId = UserHolder.getUser().getId();
        List<CartItemVO> cart = cartService.getCart(userId, shopId);
        int total = cartService.getTotalAmount(userId, shopId);
        return Result.ok(Map.of("items", cart, "totalAmount", total));
    }

    @DeleteMapping("/clear")
    public Result clear(@RequestParam Long shopId) {
        Long userId = UserHolder.getUser().getId();
        cartService.clearCart(userId, shopId);
        return Result.ok();
    }
}