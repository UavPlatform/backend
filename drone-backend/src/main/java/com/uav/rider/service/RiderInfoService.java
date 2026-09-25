package com.uav.rider.service;

import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.rider.pojo.vo.RiderInfoVO;

// TODO(yx9926): 本接口随 feature/wmc 合并进入 main，但当前【无人调用】。
//  调用方 RiderInfoController 在 main 上仍是占位实现（POST /info/edit，仅返回成功），
//  真实接线在 feat/feed 分支上。待该分支并入 main 后，本接口即生效。
//  —— wmc 合并时标注，2026-09-25
public interface RiderInfoService {
    RiderInfoVO getInfo(Long userId);
    RiderInfoVO editInfo(Long userId, RiderInfoDto dto);
}
