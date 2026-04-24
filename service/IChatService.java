package com.AITaste.service;

import com.AITaste.dto.ChatRequest;
import com.AITaste.entity.UserProfile;
import reactor.core.publisher.Flux;

public interface IChatService {

    Flux<String> chatStream(Long userId, ChatRequest request);

    String buildSystemPrompt(UserProfile profile, ChatRequest request);
}
