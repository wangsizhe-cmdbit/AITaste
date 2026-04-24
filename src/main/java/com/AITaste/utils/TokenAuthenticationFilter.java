package com.AITaste.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.AITaste.dto.UserDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.AITaste.utils.RedisConstants.*;

public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final StringRedisTemplate stringRedisTemplate;

    public TokenAuthenticationFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader("Authorization");

        if (StrUtil.isNotBlank(token)){
            String key = LOGIN_USER_KEY + token;
            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
            if (!userMap.isEmpty()) {
                // 2. 转换为 UserDTO
                UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
                // 3. 刷新 token 有效期
                stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
                // 4. 创建 Spring Security 认证对象（无密码，无角色时可省略权限）
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDTO, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                // 5. 同时保存到 ThreadLocal（兼容旧代码，可选）
                UserHolder.saveUser(userDTO);
            }
        }
        // 继续过滤链
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // 清理资源
        SecurityContextHolder.clearContext();
        UserHolder.removeUser();
    }
}