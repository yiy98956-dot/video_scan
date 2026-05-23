package com.videoplatform.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoplatform.interaction.entity.Follow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface FollowMapper extends BaseMapper<Follow> {

    /** 查询关注列表（联表 user），返回 followerId=followerId 的那些行 */
    @Select("SELECT f.followee_id AS targetId, f.create_time AS followTime, " +
            "u.id AS userId, u.username, u.nickname, u.avatar_url " +
            "FROM `follow` f " +
            "JOIN `user` u ON f.followee_id = u.id " +
            "WHERE f.follower_id = #{followerId} " +
            "ORDER BY f.create_time DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> selectFollowingWithUser(@Param("followerId") Long followerId,
                                                      @Param("offset") int offset,
                                                      @Param("limit") int limit);

    /** 查询粉丝列表（联表 user），返回 followerId=followerId 的那些行 */
    @Select("SELECT f.follower_id AS targetId, f.create_time AS followTime, " +
            "u.id AS userId, u.username, u.nickname, u.avatar_url " +
            "FROM `follow` f " +
            "JOIN `user` u ON f.follower_id = u.id " +
            "WHERE f.followee_id = #{followeeId} " +
            "ORDER BY f.create_time DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> selectFansWithUser(@Param("followeeId") Long followeeId,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    /** 查询某人的关注列表（他人视角，联表 user） */
    @Select("SELECT f.followee_id AS targetId, f.create_time AS followTime, " +
            "u.id AS userId, u.username, u.nickname, u.avatar_url " +
            "FROM `follow` f " +
            "JOIN `user` u ON f.followee_id = u.id " +
            "WHERE f.follower_id = #{userId} " +
            "ORDER BY f.create_time DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> selectFollowingByUserId(@Param("userId") Long userId,
                                                      @Param("offset") int offset,
                                                      @Param("limit") int limit);

    /** 查询某人的粉丝列表（他人视角，联表 user） */
    @Select("SELECT f.follower_id AS targetId, f.create_time AS followTime, " +
            "u.id AS userId, u.username, u.nickname, u.avatar_url " +
            "FROM `follow` f " +
            "JOIN `user` u ON f.follower_id = u.id " +
            "WHERE f.followee_id = #{userId} " +
            "ORDER BY f.create_time DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> selectFansByUserId(@Param("userId") Long userId,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    /** 查询关注数 */
    @Select("SELECT COUNT(*) FROM `follow` WHERE follower_id = #{userId}")
    long countFollowing(@Param("userId") Long userId);

    /** 查询粉丝数 */
    @Select("SELECT COUNT(*) FROM `follow` WHERE followee_id = #{userId}")
    long countFollowers(@Param("userId") Long userId);

    /** 查询是否已关注 */
    @Select("SELECT COUNT(*) > 0 FROM `follow` WHERE follower_id = #{followerId} AND followee_id = #{followeeId}")
    boolean existsFollow(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);
}
