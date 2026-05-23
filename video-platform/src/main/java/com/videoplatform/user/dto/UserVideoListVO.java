package com.videoplatform.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserVideoListVO {

    private List<UserVideoItemVO> items;
    private int page;
    private int size;
    private long total;

    public static UserVideoListVO of(List<UserVideoItemVO> items, int page, int size, long total) {
        UserVideoListVO vo = new UserVideoListVO();
        vo.items = items; vo.page = page; vo.size = size; vo.total = total;
        return vo;
    }
}
