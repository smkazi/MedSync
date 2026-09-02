package com.hms.admissions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.hms.admissions", "com.hms.common"})
public class AdmissionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdmissionsApplication.class, args);
    }
}
