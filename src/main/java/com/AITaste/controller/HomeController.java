package com.AITaste.controller;

import com.AITaste.VO.ShopRecommendVO;
import com.AITaste.dto.Result;
import com.AITaste.dto.UserDTO;
import com.AITaste.service.IShopService;
import com.AITaste.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/home")
public class HomeController {

    @Resource
    private IShopService shopService;

    @GetMapping("/aiSmartPick")
    public Result aiSmartPick(@RequestParam(required = false) Double x,
                              @RequestParam(required = false) Double y) {
        UserDTO currentUser = UserHolder.getUser();
        Long userId = currentUser != null ? currentUser.getId() : null;
        List<ShopRecommendVO> list = shopService.getAiSmartPick(userId, x, y);
        return Result.ok(list);
    }
}
