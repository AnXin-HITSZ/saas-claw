package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.vo.ApiKeyVO;
import com.saasclaw.backend.vo.CreateApiKeyVO;
import com.saasclaw.backend.dto.LoginRequest;
import com.saasclaw.backend.vo.LoginVO;
import com.saasclaw.backend.dto.RegisterRequest;
import com.saasclaw.backend.entity.Authorization;
import com.saasclaw.backend.entity.User;
import com.saasclaw.backend.mapper.AuthorizationMapper;
import com.saasclaw.backend.mapper.UserMapper;
import com.saasclaw.backend.service.AuthService;
import com.saasclaw.backend.util.ApiKeyUtil;
import com.saasclaw.backend.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final AuthorizationMapper authorizationMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public LoginVO register(RegisterRequest request) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername())
        );
        if (count > 0) {
            throw new BizException(409, "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setStatus(1);
        userMapper.insert(user);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), 0);
        return new LoginVO(token, user.getId(), user.getUsername(), user.getNickname());
    }

    @Override
    public LoginVO login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(401, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(403, "账号已被禁用");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginVO(token, user.getId(), user.getUsername(), user.getNickname());
    }

    @Override
    @Transactional
    public CreateApiKeyVO createApiKey(Long userId, String name) {
        String plain = ApiKeyUtil.generate();
        String suffix = plain.substring(plain.length() - 6);

        Authorization auth = new Authorization();
        auth.setUserId(userId);
        auth.setName(name);
        auth.setApiKey(ApiKeyUtil.hash(plain));
        auth.setKeySuffix(suffix);
        auth.setStatus(1);
        authorizationMapper.insert(auth);

        return new CreateApiKeyVO(auth.getId(), plain, name, suffix, LocalDateTime.now());
    }

    @Override
    public List<ApiKeyVO> listApiKeys(Long userId) {
        // 返回全部（含已吊销，status 区分），按创建时间倒序
        return authorizationMapper.selectList(
                        new LambdaQueryWrapper<Authorization>()
                                .eq(Authorization::getUserId, userId)
                                .orderByDesc(Authorization::getId))
                .stream()
                .map(a -> new ApiKeyVO(
                        a.getId(), a.getName(), a.getKeySuffix(), a.getStatus(),
                        a.getCreatedAt(), a.getUpdatedAt()))
                .toList();
    }

    @Override
    public void revokeApiKey(Long userId, Long id) {
        // 归属校验：id + userId 双条件，不存在/非本人/已吊销统一 404
        Authorization auth = authorizationMapper.selectOne(
                new LambdaQueryWrapper<Authorization>()
                        .eq(Authorization::getId, id)
                        .eq(Authorization::getUserId, userId));
        if (auth == null || auth.getStatus() == 0) {
            throw new BizException(404, "API Key 不存在");
        }
        authorizationMapper.update(null,
                new LambdaUpdateWrapper<Authorization>()
                        .eq(Authorization::getId, id)
                        .set(Authorization::getStatus, 0));
    }
}
