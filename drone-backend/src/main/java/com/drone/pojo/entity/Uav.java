package com.drone.pojo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "uav")
@Data
public class Uav {
    @Id
    private Long id;

    @Column(name = "user_name")
    private String userName;
}
