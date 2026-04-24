package com.AITaste.config;

import com.AITaste.utils.TokenAuthenticationFilter;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // 禁用 CSRF、表单登录、HTTP Basic
        http.csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                // 使用无状态 Session（基于 Token）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // 添加自定义 Token 过滤器（在 UsernamePasswordAuthenticationFilter 之前）
        http.addFilterBefore(new TokenAuthenticationFilter(stringRedisTemplate),
                UsernamePasswordAuthenticationFilter.class);

        // 授权规则
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/user/{id}",  // 用户公开信息
                        "/shop/**",             // 店铺查询
                        "/blog/hot",            // 热门博客列表
                        "/blog/{id}",           // 博客详情
                        "/blog/likes/{id}",     // 点赞用户列表
                        "/comment/of/blog",     // 评论列表
                        "/voucher/list/**",     // 优惠券列表
                        "/home/aiSmartPick",    // 首页顶部店铺列表
                        "/user/code", "/user/login", "/user/passwordLogin",
                        "/ai/**"        // 未登录左下固定引导语
                ).permitAll()
                .anyRequest().authenticated()
        );

        // 异常处理：返回 JSON 格式的 401/403
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint())
                .accessDeniedHandler(accessDeniedHandler())
        );

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"success\":false,\"errorMsg\":\"未登录或 token 已过期\"}");
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("{\"success\":false,\"errorMsg\":\"无权限访问\"}");
        };
    }
}