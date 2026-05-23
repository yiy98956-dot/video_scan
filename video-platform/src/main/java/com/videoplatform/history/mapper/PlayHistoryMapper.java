package com.videoplatform.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoplatform.history.entity.PlayHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlayHistoryMapper extends BaseMapper<PlayHistory> {

    /** 分页查询用户播放历史，联表 video_meta */
    @Select("SELECT ph.id AS history_id, ph.video_id, ph.progress, ph.duration, " +
            "ph.play_time, ph.update_time, " +
            "v.id AS local_id, v.cms_video_id, v.source, v.title, v.cover_url, v.duration AS video_duration, " +
            "v.play_count, v.like_count, v.collect_count, v.comment_count " +
            "FROM play_history ph " +
            "LEFT JOIN video_meta v ON ph.video_id = v.id " +
            "WHERE ph.user_id = #{userId} " +
            "ORDER BY ph.play_time DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> selectHistoryWithVideo(@Param("userId") Long userId,
                                                     @Param("offset") int offset,
                                                     @Param("limit") int limit);

    /** 用户历史总数 */
    @Select("SELECT COUNT(*) FROM play_history WHERE user_id = #{userId}")
    long countByUserId(@Param("userId") Long userId);
}
