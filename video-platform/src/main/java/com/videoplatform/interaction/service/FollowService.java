package com.videoplatform.interaction.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoplatform.auth.dto.FollowResponse;
import com.videoplatform.interaction.entity.Follow;
import com.videoplatform.interaction.mapper.FollowMapper;
import com.videoplatform.user.dto.FollowUserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowMapper followMapper;

    /**
     * 关注/取消关注（幂等，利用唯一索引）
     *
     * @return 当前关注状态
     */
    @Transactional(rollbackFor = Exception.class)
    public FollowResponse toggleFollow(Long followerId, Long followeeId) {
        if (followerId.equals(followeeId)) {
            throw new IllegalArgumentException("不能关注自己");
        }

        // 查是否已关注
        Follow existing = followMapper.selectOne(
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getFollowerId, followerId)
                        .eq(Follow::getFolloweeId, followeeId));

        if (existing != null) {
            // 已关注 → 取关
            followMapper.deleteById(existing.getId());
            return FollowResponse.of(false, followeeId);
        } else {
            // 未关注 → 关注
            Follow follow = new Follow();
            follow.setFollowerId(followerId);
            follow.setFolloweeId(followeeId);
            followMapper.insert(follow);
            return FollowResponse.of(true, followeeId);
        }
    }

    /** 当前用户是否已关注目标 */
    public boolean isFollowing(Long followerId, Long followeeId) {
        return followMapper.existsFollow(followerId, followeeId);
    }

    /** 获取关注列表 */
    public List<FollowUserVO> getFollowing(Long userId, int page, int size) {
        List<Map<String, Object>> rows = followMapper.selectFollowingWithUser(userId, (page - 1) * size, size);
        return mapToFollowUserVO(rows, false);
    }

    /** 获取粉丝列表 */
    public List<FollowUserVO> getFans(Long userId, int page, int size) {
        List<Map<String, Object>> rows = followMapper.selectFansWithUser(userId, (page - 1) * size, size);
        return mapToFollowUserVO(rows, true);
    }

    public long countFollowing(Long userId) {
        return followMapper.countFollowing(userId);
    }

    public long countFollowers(Long userId) {
        return followMapper.countFollowers(userId);
    }

    /** Map 结果集 → VO */
    private List<FollowUserVO> mapToFollowUserVO(List<Map<String, Object>> rows, boolean isFans) {
        List<FollowUserVO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long targetId = row.get("targetId") != null ? ((Number) row.get("targetId")).longValue() : null;
            Long uid = row.get("userId") != null ? ((Number) row.get("userId")).longValue() : null;
            FollowUserVO vo = new FollowUserVO();
            vo.setUserId(uid);
            vo.setUsername((String) row.get("username"));
            vo.setNickname((String) row.get("nickname"));
            vo.setAvatarUrl((String) row.get("avatar_url"));
            vo.setFollowTime((java.time.LocalDateTime) row.get("followTime"));
            list.add(vo);
        }
        return list;
    }
}
