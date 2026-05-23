package com.videoplatform.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoplatform.interaction.entity.LikeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface LikeRecordMapper extends BaseMapper<LikeRecord> {

    /** 分页查询用户的喜欢列表，联表 video_meta */
    @Select("SELECT lr.video_id, lr.create_time AS like_time, " +
            "v.id AS local_id, v.cms_video_id, v.source, v.title, v.cover_url, v.duration, " +
            "v.play_count, v.like_count, v.collect_count, v.comment_count " +
            "FROM like_record lr " +
            "LEFT JOIN video_meta v ON lr.video_id = v.id " +
            "WHERE lr.user_id = #{userId} AND lr.status = 1 " +
            "ORDER BY lr.create_time DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> selectLikesWithVideo(@Param("userId") Long userId,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);

    /** 用户喜欢总数 */
    @Select("SELECT COUNT(*) FROM like_record WHERE user_id = #{userId} AND status = 1")
    long countActiveByUserId(@Param("userId") Long userId);
}
