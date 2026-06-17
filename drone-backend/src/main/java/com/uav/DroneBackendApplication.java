package com.uav;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan({"com.uav.chat.mapper"})
@ConfigurationPropertiesScan({"com.uav.server.util", "com.uav.pay.config", "com.uav.server.file.config"})
@EnableScheduling
public class DroneBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DroneBackendApplication.class, args);
    }

}
