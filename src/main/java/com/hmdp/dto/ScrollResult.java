package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
//混动分页查询
public class ScrollResult {
    //结果列表
    private List<?> list;
    //最小分数
    private Long minTime;
    //偏移量
    private Integer offset;
}
