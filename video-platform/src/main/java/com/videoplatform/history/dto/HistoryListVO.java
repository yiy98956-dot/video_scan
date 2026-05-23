package com.videoplatform.history.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryListVO {

    private List<HistoryItemVO> items;
    private int page;
    private int size;
    private long total;
}
