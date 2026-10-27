package com.lb.financefresh;

import com.lb.financefresh.config.DotEnv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FinanceFreshApplication {

    public static void main(String[] args) {
        DotEnv.export("REDIS_HOST", "REDIS_PORT");
        SpringApplication.run(FinanceFreshApplication.class, args);
    }
}
