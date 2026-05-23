package com.videoplatform.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoplatform.interaction.entity.Favorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {

    /** 分页查询用户的收藏列表，联表 video_meta */
    @Select("SELECT f.video_id, f.create_time AS favorite_time, " +
            "v.id AS local_id, v.cms_video_id, v.source, v.title, v.cover_url, v.duration, " +
            "v.play_count, v.like_count, v.collect_count, v.comment_count " +
            "FROM favorite f " +
            "LEFT JOIN video_meta v ON f.video_id = v.id " +
            "WHERE f.user_id = #{userId} " +
            "ORDER BY f.create_time DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> selectFavoritesWithVideo(@Param("userId") Long userId,
                                                       @Param("offset") int offset,
                                                       @Param("limit") int limit);

    /** 用户收藏总数 */
    @Select("SELECT COUNT(*) FROM favorite WHERE user_id = #{userId}")
    long countByUserId(@Param("userId") Long userId);
}
