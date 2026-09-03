package com.hms.imaging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.hms.imaging", "com.hms.common"})
public class ImagingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImagingApplication.class, args);
    }
}
