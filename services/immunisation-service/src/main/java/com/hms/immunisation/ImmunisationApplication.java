package com.hms.immunisation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.hms.immunisation", "com.hms.common"})
public class ImmunisationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImmunisationApplication.class, args);
    }
}
