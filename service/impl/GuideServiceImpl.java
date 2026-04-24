package com.AITaste.service.impl;

import com.AITaste.dto.GuideResponse;
import com.AITaste.entity.UserProfile;
import com.AITaste.service.IGuideService;
import com.AITaste.service.IUserProfileService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GuideServiceImpl implements IGuideService {

    @Autowired
    private IUserProfileService userProfileService;

    @Autowired
    private ChatClient chatClient;   // 直接注入，配置中已定义

    public GuideResponse generateGuide(Long userId) {
        UserProfile profile = userProfileService.getUserProfile(userId);
        if (profile == null) {
            return new GuideResponse("欢迎！想吃什么？我可以帮你推荐附近美食。", "推荐餐厅");
        }

        String prompt = String.format(
                "用户画像：口味偏好：%s，消费水平：%s，常去区域：%s。请生成一句亲切、个性化的问候和推荐引导语，吸引用户开始对话。要求：自然、口语化，不超过30字。",
                profile.getTaste() != null ? profile.getTaste() : "多种",
                priceLevelToDesc(profile.getAvgPriceLevel()),
                profile.getFrequentArea() != null ? profile.getFrequentArea() : "本地"
        );

        String guideText;
        try {
            guideText = chatClient.prompt().user(prompt).call().content();
            if (guideText.trim().isEmpty()) {
                guideText = "想好吃什么了吗？我可以帮你推荐！";
            }
            if (guideText.length() > 30) {
                guideText = guideText.substring(0, 30);
            }
        } catch (Exception e) {
            guideText = "想好吃什么了吗？我可以帮你推荐！";
        }

        return new GuideResponse(guideText, "帮我推荐餐厅");
    }

    private String priceLevelToDesc(Integer level) {
        if (level == null) return "适中";
        switch (level) {
            case 1: return "实惠";
            case 2: return "适中";
            case 3: return "高端";
            default: return "适中";
        }
    }
}