package com.AITaste.service.impl;

import com.AITaste.dto.ChatRequest;
import com.AITaste.entity.UserProfile;
import com.AITaste.service.IChatService;
import com.AITaste.service.IUserProfileService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

@Service
public class ChatServiceImpl implements IChatService {

    private final ChatClient chatClient;

    @Autowired
    private IUserProfileService userProfileService;

    public ChatServiceImpl(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 流式对话入口，支持上下文，并传递 userId 到 Advisor
     */
    public Flux<String> chatStream(Long userId, ChatRequest request) {
        String userQuery = request.getQuery();
        String sessionId = request.getSessionId();
        UserProfile profile = userProfileService.getUserProfile(userId);
        String systemPrompt = buildSystemPrompt(profile, request);

        // 创建一个 Map 来存放你的上下文参数
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put("userId", userId);
        if (request.getContext() != null && request.getContext().getShopId() != null) {
            toolContext.put("shopId", request.getContext().getShopId());
        }
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userQuery)
                .advisors(advisor -> advisor.param("conversation_id", sessionId))
                .toolContext(toolContext)
                .stream()
                .content()
                .concatWith(Flux.just("\n"));
    }

    /**
     * 根据请求动态构建系统提示词
     */
    public String buildSystemPrompt(UserProfile profile, ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个美食导购助手，回答要亲切自然，一定要尽可能多地使用表情符号。\n");

        if (profile != null) {
            sb.append("当前用户画像：");
            if (profile.getTaste() != null) sb.append("口味偏好=").append(profile.getTaste()).append("；");
            if (profile.getAvgPriceLevel() != null) sb.append("消费等级=").append(profile.getAvgPriceLevel()).append("（1低2中3高）；");
            if (profile.getFrequentArea() != null) sb.append("常去区域=").append(profile.getFrequentArea()).append("。");
            sb.append("\n");
        }

        if (request.getX() != null && request.getY() != null) {
            sb.append("当前用户坐标：经度=").append(request.getX()).append(", 纬度=").append(request.getY()).append("。\n");
        }

        if (request.getContext() != null && request.getContext().getSource() != null) {
            sb.append("用户来源：").append(request.getContext().getSource()).append("。\n");
        }

        return sb.toString();
    }
}