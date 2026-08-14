package com.saasclaw.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.gateway.mapper")
public class SrcApplication {

    public static void main(String[] args) {
        SpringApplication.run(SrcApplication.class, args);
    }

}
