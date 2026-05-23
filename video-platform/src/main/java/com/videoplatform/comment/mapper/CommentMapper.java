package com.videoplatform.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoplatform.comment.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /** 分页查询顶级评论（parent_id = 0），联表 user */
    @Select("SELECT c.id, c.video_id, c.user_id, c.parent_id, c.reply_to_uid, " +
            "c.content, c.like_count, c.status, c.create_time, " +
            "u.nickname, u.avatar_url " +
            "FROM comment c " +
            "LEFT JOIN `user` u ON c.user_id = u.id " +
            "WHERE c.video_id = #{videoId} AND c.parent_id = 0 AND c.status = 1 " +
            "ORDER BY c.create_time DESC " +
            "LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> selectTopComments(@Param("videoId") Long videoId,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    /** 顶级评论总数 */
    @Select("SELECT COUNT(*) FROM comment WHERE video_id = #{videoId} AND parent_id = 0 AND status = 1")
    long countTopComments(@Param("videoId") Long videoId);

    /** 根据父评论 ID 列表查询子评论（按时间升序），联表 user */
    @Select("<script>" +
            "SELECT c.id, c.video_id, c.user_id, c.parent_id, c.reply_to_uid, " +
            "c.content, c.like_count, c.status, c.create_time, " +
            "u.nickname, u.avatar_url " +
            "FROM comment c " +
            "LEFT JOIN `user` u ON c.user_id = u.id " +
            "WHERE c.parent_id IN " +
            "<foreach item='id' index='index' collection='parentIds' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            " AND c.status = 1 " +
            "ORDER BY c.create_time ASC" +
            "</script>")
    List<Map<String, Object>> selectRepliesByParentIds(@Param("parentIds") List<Long> parentIds);

    /** 单条评论详情（含用户信息） */
    @Select("SELECT c.id, c.video_id, c.user_id, c.parent_id, c.reply_to_uid, " +
            "c.content, c.like_count, c.status, c.create_time, " +
            "u.nickname, u.avatar_url " +
            "FROM comment c " +
            "LEFT JOIN `user` u ON c.user_id = u.id " +
            "WHERE c.id = #{id}")
    Map<String, Object> selectCommentWithUser(@Param("id") Long id);
}
