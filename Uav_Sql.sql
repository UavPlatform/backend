CREATE TABLE `user` (
                        `id` bigint NOT NULL,
                        `password` varchar(255) DEFAULT NULL,
                        `status` int DEFAULT NULL,
                        `user_name` varchar(255) DEFAULT NULL,
                        PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `uav` (
                       `id` BIGINT NOT NULL,
                       `uav_name` VARCHAR(255) DEFAULT NULL,
                       `online_status` CHAR(1) DEFAULT '0' comment "0离线，1在线",
                       `uav_user` BIGINT DEFAULT NULL,
                       PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;