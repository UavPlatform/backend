package com.uav.rider.service;

import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.rider.pojo.vo.RiderInfoVO;

public interface RiderInfoService {

    /** 读取飞手资料与资质（从未保存资料时返回仅含用户名的空资料）。 */
    RiderInfoVO getInfo(Long userId);

    /** 局部更新飞手资料；{@code qualifications} 非 null 时按集合差异同步资质。 */
    RiderInfoVO editInfo(Long userId, RiderInfoDto dto);
}
