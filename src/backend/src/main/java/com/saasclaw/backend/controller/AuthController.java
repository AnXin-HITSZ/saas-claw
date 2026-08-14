package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.vo.ApiKeyVO;
import com.saasclaw.backend.dto.CreateApiKeyRequest;
import com.saasclaw.backend.vo.CreateApiKeyVO;
import com.saasclaw.backend.dto.LoginRequest;
import com.saasclaw.backend.vo.LoginVO;
import com.saasclaw.backend.dto.RegisterRequest;
import com.saasclaw.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @PostMapping("/api-keys")
    public Result<CreateApiKeyVO> createApiKey(@RequestAttribute("userId") Long userId,
                                               @Valid @RequestBody CreateApiKeyRequest request) {
        return Result.ok(authService.createApiKey(userId, request.getName()));
    }

    @GetMapping("/api-keys")
    public Result<List<ApiKeyVO>> listApiKeys(@RequestAttribute("userId") Long userId) {
        return Result.ok(authService.listApiKeys(userId));
    }

    @DeleteMapping("/api-keys/{id}")
    public Result<Void> revokeApiKey(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        authService.revokeApiKey(userId, id);
        return Result.ok();
    }
}
