package com.interview.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interview.agent.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
