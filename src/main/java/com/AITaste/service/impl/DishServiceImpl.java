package com.AITaste.service.impl;

import com.AITaste.entity.Dish;
import com.AITaste.mapper.DishMapper;
import com.AITaste.service.IDishService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class DishServiceImpl extends ServiceImpl<DishMapper, Dish> implements IDishService {
    @Override
    public List<Dish> searchDishes(Long shopId, String keyword, Integer page, Integer size) {
        LambdaQueryWrapper<Dish> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Dish::getShopId, shopId)
                .eq(Dish::getStatus, 1);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Dish::getName, keyword);
        }
        Page<Dish> pageParam = new Page<>(page, size);
        Page<Dish> result = this.page(pageParam, wrapper);
        return result.getRecords();
    }
}