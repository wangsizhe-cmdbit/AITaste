package com.AITaste.controller;

import com.AITaste.dto.Result;
import com.AITaste.entity.Dish;
import com.AITaste.service.IDishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dish")
public class DishController {

    @Autowired
    private IDishService dishService;

    @GetMapping("/list")
    public Result list(@RequestParam Long shopId,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(defaultValue = "1") Integer page,
                       @RequestParam(defaultValue = "10") Integer size) {
        List<Dish> dishes = dishService.searchDishes(shopId, keyword, page, size);
        return Result.ok(dishes);
    }
}