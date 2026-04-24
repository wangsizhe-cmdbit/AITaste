package com.AITaste.utils;

public class RedisConstants {
    // 登录相关
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    // 缓存穿透空值TTL
    public static final Long CACHE_NULL_TTL = 2L;

    // 店铺缓存
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final Long LOGIC_EXPIRE_SECONDS = 1800L; // 30分钟

    // 互斥锁（仅用于异步刷新去重，非阻塞）
    public static final String LOCK_SHOP_REFRESH_KEY = "lock:shop:refresh:";

    // 秒杀、点赞等
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:all";

    // AI预热相关
    public static final String SHOP_ACCESS_LOG_PREFIX = "shop:access:";
    public static final String SHOP_HOT_SCORE_PREFIX = "shop:hot:";
}
