package com.clawsaas.gateway.service;

import com.clawsaas.gateway.dto.CreateUserRequest;
import com.clawsaas.gateway.entity.UserEntity;
import java.util.List;

public interface UserService {
    List<UserEntity> list();

    UserEntity create(CreateUserRequest request);

    UserEntity disable(String id);
}
