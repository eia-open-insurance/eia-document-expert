package com.eia.document.expert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocumentExpertApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocumentExpertApplication.class, args);
    }
}
