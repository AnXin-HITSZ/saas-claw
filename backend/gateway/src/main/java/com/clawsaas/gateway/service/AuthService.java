package com.clawsaas.gateway.service;

import com.clawsaas.gateway.dto.LoginRequest;
import com.clawsaas.gateway.dto.LoginResponse;
import com.clawsaas.gateway.dto.RegisterRequest;

public interface AuthService {
    LoginResponse login(LoginRequest request);

    LoginResponse register(RegisterRequest request);
}
