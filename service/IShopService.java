package com.AITaste.service;


import com.AITaste.VO.ShopRecommendVO;
import com.AITaste.dto.Result;
import com.AITaste.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);

    Result queryAllShops(Integer current, Double x, Double y);

    Result queryShopsByName(String keyword, Integer page, Integer size);

    Result queryShopsByArea(String area, Integer pageNum);

    Result getAiSummary(Long shopId);

    List<ShopRecommendVO> getAiSmartPick(Long userId, Double x, Double y);

    default void incrSold(Long shopId) {
        lambdaUpdate().eq(Shop::getId, shopId).setSql("sold = sold + 1").update();
    }
}
