package com.videoplatform.infrastructure.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoplatform.user.entity.User;
import com.videoplatform.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 数据库 UserDetailsService — 通过用户名查询用户
 */
@Service
@RequiredArgsConstructor
public class DbUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username));

        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }

        return new CustomUserDetails(user.getId(), user.getUsername(), user.getPasswordHash(), user.getRole());
    }
}
