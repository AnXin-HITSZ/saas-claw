package com.saasclaw.backend.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saasclaw.backend.entity.Authorization;
import com.saasclaw.backend.mapper.AuthorizationMapper;
import com.saasclaw.backend.util.ApiKeyUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final AuthorizationMapper authorizationMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String key = auth.substring(7);
            if (key.startsWith("sk-")) {
                // 与 createApiKey 存储一致：SHA-256 哈希后等值查询
                Authorization authz = authorizationMapper.selectOne(
                        new LambdaQueryWrapper<Authorization>()
                                .eq(Authorization::getApiKey, ApiKeyUtil.hash(key))
                                .eq(Authorization::getStatus, 1)   // 只认未吊销的
                );
                if (authz != null) {
                    request.setAttribute("userId", authz.getUserId());
                    return true;
                }
            }
        }
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"API Key 无效\"}");
        return false;
    }
}
