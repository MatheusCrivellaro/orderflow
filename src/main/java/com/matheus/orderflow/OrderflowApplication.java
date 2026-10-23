package com.matheus.orderflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class OrderflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderflowApplication.class, args);
    }
}
