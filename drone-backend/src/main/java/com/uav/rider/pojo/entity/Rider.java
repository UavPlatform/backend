package com.uav.rider.pojo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Rider {

    @Id
    private Long id;

    private String name;

    private int age;

    private String location;

    private String selfIntroduction;

}
