package com.videoplatform.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videoplatform.interaction.service.FollowService;
import com.videoplatform.user.dto.UpdateProfileRequest;
import com.videoplatform.user.dto.UserProfileVO;
import com.videoplatform.user.dto.UserPublicVO;
import com.videoplatform.user.entity.User;
import com.videoplatform.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final FollowService followService;
    private final PasswordEncoder passwordEncoder;

    /** 获取当前用户完整信息（不含密码） */
    public UserProfileVO getProfile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        long followerCount = followService.countFollowers(userId);
        long followingCount = followService.countFollowing(userId);

        UserProfileVO vo = new UserProfileVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setStatus(user.getStatus());
        vo.setFollowerCount(followerCount);
        vo.setFollowingCount(followingCount);
        vo.setRole(user.getRole());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    /** 更新个人信息（昵称/头像/用户名/密码） */
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        // 修改昵称
        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }

        // 修改头像
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }

        // 修改用户名（需检查唯一性）
        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            Long count = userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getUsername, request.getUsername()));
            if (count != null && count > 0) {
                throw new IllegalArgumentException("用户名已存在");
            }
            user.setUsername(request.getUsername());
        }

        // 修改密码（需要校验当前密码）
        if (request.getNewPassword() != null && !request.getNewPassword().isEmpty()) {
            if (request.getPassword() == null || request.getPassword().isEmpty()) {
                throw new BadCredentialsException("修改密码需要提供当前密码");
            }
            if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                throw new BadCredentialsException("当前密码错误");
            }
            user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        }

        userMapper.updateById(user);
        return getProfile(userId);
    }

    /** 获取他人公开信息 */
    public UserPublicVO getPublicInfo(Long targetUserId) {
        User user = userMapper.selectById(targetUserId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        long followerCount = followService.countFollowers(targetUserId);
        long followingCount = followService.countFollowing(targetUserId);

        UserPublicVO vo = new UserPublicVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setFollowerCount(followerCount);
        vo.setFollowingCount(followingCount);
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    // ════════════════════════════════════════════════════════════
    // 管理员操作
    // ════════════════════════════════════════════════════════════

    /** 分页查询所有用户 */
    public Map<String, Object> listUsers(int page, int size, String keyword) {
        Page<User> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();

        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.and(w -> w
                .like(User::getUsername, keyword.trim())
                .or()
                .like(User::getNickname, keyword.trim())
            );
        }
        wrapper.orderByDesc(User::getCreateTime);

        Page<User> result = userMapper.selectPage(pageParam, wrapper);

        List<Map<String, Object>> items = result.getRecords().stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("nickname", u.getNickname());
            map.put("avatarUrl", u.getAvatarUrl());
            map.put("role", u.getRole());
            map.put("banned", u.getBanned() != null ? u.getBanned() : 0);
            map.put("mutedUntil", u.getMutedUntil());
            map.put("createTime", u.getCreateTime());
            return map;
        }).toList();

        Map<String, Object> resp = new HashMap<>();
        resp.put("items", items);
        resp.put("total", result.getTotal());
        resp.put("pages", result.getPages());
        return resp;
    }

    /** 封禁/解封用户 */
    public void toggleBan(Long userId, boolean banned) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        if ("admin".equals(user.getRole())) throw new IllegalArgumentException("不能封禁管理员");
        user.setBanned(banned ? 1 : 0);
        userMapper.updateById(user);
    }

    /** 禁言用户（durationMinutes 分钟） */
    public void muteUser(Long userId, int durationMinutes) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        if ("admin".equals(user.getRole())) throw new IllegalArgumentException("不能禁言管理员");
        if (durationMinutes <= 0) {
            user.setMutedUntil(null);
        } else {
            user.setMutedUntil(LocalDateTime.now().plusMinutes(durationMinutes));
        }
        userMapper.updateById(user);
    }

    /** 检查用户是否被封禁 */
    public boolean isBanned(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && user.getBanned() != null && user.getBanned() == 1;
    }

    /** 检查用户是否被禁言 */
    public boolean isMuted(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return false;
        if (user.getMutedUntil() == null) return false;
        return user.getMutedUntil().isAfter(LocalDateTime.now());
    }
}
