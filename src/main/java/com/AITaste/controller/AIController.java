package com.AITaste.controller;

import com.AITaste.dto.ChatRequest;
import com.AITaste.dto.GuideResponse;
import com.AITaste.dto.UserDTO;
import com.AITaste.service.IGuideService;
import com.AITaste.service.impl.ChatServiceImpl;
import com.AITaste.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class AIController {

    @Autowired
    private ChatServiceImpl chatService;

    @Autowired
    private IGuideService guideService;

    // 流式对话接口（所有入口统一调用这个）
    @PostMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chat(@RequestBody ChatRequest request) {
        // 参数校验
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            return Flux.just("问题不能为空");
        }
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Flux.just("请先登录");
        }
        Long userId = currentUser.getId();
        return chatService.chatStream(userId, request);
    }

    // 首页引导语接口
    @GetMapping("/guide/home")
    public GuideResponse getHomeGuide() {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            // 未登录，返回默认引导语
            return new GuideResponse("欢迎！想吃什么？我可以帮你推荐附近美食。", "推荐餐厅");
        }
        return guideService.generateGuide(currentUser.getId());
    }
}