package com.AITaste.service;


import com.AITaste.entity.Dish;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IDishService extends IService<Dish> {
    List<Dish> searchDishes(Long shopId, String keyword, Integer page, Integer size);
}