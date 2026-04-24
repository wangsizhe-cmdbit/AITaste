package com.AITaste.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GuideResponse {
    private String guideText;   // 显示在气泡上的文字
    private String autoQuery;   // 点击后自动发送的查询语句
}