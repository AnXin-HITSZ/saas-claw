package com.saasclaw.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.saasclaw.backend.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
