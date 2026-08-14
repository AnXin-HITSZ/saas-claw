package com.saasclaw.backend.service;

import com.saasclaw.backend.vo.ApiKeyVO;
import com.saasclaw.backend.vo.CreateApiKeyVO;
import com.saasclaw.backend.dto.LoginRequest;
import com.saasclaw.backend.vo.LoginVO;
import com.saasclaw.backend.dto.RegisterRequest;

import java.util.List;

public interface AuthService {
    LoginVO register(RegisterRequest request);
    LoginVO login(LoginRequest request);
    CreateApiKeyVO createApiKey(Long userId, String name);
    List<ApiKeyVO> listApiKeys(Long userId);
    void revokeApiKey(Long userId, Long id);
}
