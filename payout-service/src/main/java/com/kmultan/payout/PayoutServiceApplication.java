package com.kmultan.payout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.kmultan.payout", "com.kmultan.platform"})
@EntityScan(basePackages = {"com.kmultan.payout", "com.kmultan.platform.outbox"})
@EnableJpaRepositories(basePackages = {"com.kmultan.payout", "com.kmultan.platform.outbox"})
@EnableScheduling
@ConfigurationPropertiesScan
public class PayoutServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PayoutServiceApplication.class, args);
    }
}
