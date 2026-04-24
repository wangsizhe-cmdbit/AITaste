package com.AITaste.dto;

import lombok.Data;
@Data
public class ChatRequest {
    private String query;           // 用户问题
    private String sessionId;       // 会话ID（用于多轮）
    private Long userId;            // 用户ID
    private Context context;        // 上下文
    private Double x;
    private Double y;
}

