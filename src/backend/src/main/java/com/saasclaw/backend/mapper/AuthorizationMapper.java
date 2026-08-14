package com.saasclaw.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.saasclaw.backend.entity.Authorization;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthorizationMapper extends BaseMapper<Authorization> {
}