package com.drone;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"com.drone", "com.chat"})
@MapperScan({"com.chat.mapper"})
@ConfigurationPropertiesScan("com.drone.server.util")
public class DroneBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(DroneBackendApplication.class, args);
	}

}
