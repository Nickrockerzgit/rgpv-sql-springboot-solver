package com.example.rgpv;

import com.example.rgpv.service.WebhookService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class RgpvWebhookSolverApplication {

    public static void main(String[] args) {
        SpringApplication.run(RgpvWebhookSolverApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ApplicationRunner runner(WebhookService svc,
                                    @Value("${app.name}") String name,
                                    @Value("${app.regNo}") String regNo,
                                    @Value("${app.email}") String email) {
        return args -> {
            svc.executeFlow(name, regNo, email);
        };
    }
}
