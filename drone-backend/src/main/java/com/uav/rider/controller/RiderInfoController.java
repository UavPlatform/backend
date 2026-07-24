package com.uav.rider.controller;

import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.server.result.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/info")
public class RiderInfoController {

    @PostMapping("/edit")
    public Result EditSelfInfo(@RequestBody RiderInfoDto riderInfoDto){
        return Result.success();
    }
}
