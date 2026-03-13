package com.drone.pojo.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserLoginDto implements Serializable {
    private Long id;
    private String password;
}