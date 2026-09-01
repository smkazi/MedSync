package com.hms.laboratory.config;

import com.hms.laboratory.service.LabMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class LabConfig {

    @Bean
    public LabMapper labMapper(ObjectMapper objectMapper) {
        return new LabMapper(objectMapper);
    }
}
