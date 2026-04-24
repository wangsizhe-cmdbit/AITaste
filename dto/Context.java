package com.AITaste.dto;

import lombok.Data;
import java.util.List;

@Data
public class Context {
    private String source;          // home_ai_icon, home_guide, search_result, shop_detail
    private List<Long> shopIds;     // 搜索结果页的店铺ID列表
    private Long shopId;            // 店铺详情页的店铺ID
    private String searchKeyword;   // 可选
}