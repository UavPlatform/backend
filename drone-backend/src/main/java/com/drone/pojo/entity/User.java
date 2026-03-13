package com.drone.pojo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "user")
@Data
public class User {
    @Id
    private Long id;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "password")
    private String passWord;

    private Integer status;
}