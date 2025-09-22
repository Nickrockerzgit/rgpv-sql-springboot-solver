package com.example.rgpv.service;

import com.example.rgpv.dto.FinalQueryRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Service
public class WebhookService {

    private final RestTemplate restTemplate;
    private final String generateWebhookUrl;

    public WebhookService(RestTemplate restTemplate,
                          @Value("${app.generateWebhookUrl}") String generateWebhookUrl) {
        this.restTemplate = restTemplate;
        this.generateWebhookUrl = generateWebhookUrl;
    }

    public void executeFlow(String name, String regNo, String email) throws Exception {
        // 1) Call generateWebhook
        Map<String, String> requestBody = Map.of(
                "name", name,
                "regNo", regNo,
                "email", email
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String,String>> entity = new HttpEntity<>(requestBody, headers);

        System.out.println("Calling generateWebhook: " + generateWebhookUrl);
        ResponseEntity<Map> resp = restTemplate.postForEntity(generateWebhookUrl, entity, Map.class);
        if (resp.getStatusCode() != HttpStatus.OK && resp.getStatusCode() != HttpStatus.CREATED) {
            System.err.println("generateWebhook returned status: " + resp.getStatusCode());
            throw new IllegalStateException("Failed to generate webhook");
        }

        Map body = resp.getBody();
        if (body == null) throw new IllegalStateException("Empty response from generateWebhook");

        // Flexible extraction (field names might vary)
        String webhookUrl = (String) (body.getOrDefault("webhook", body.get("webhookUrl")));
        if (webhookUrl == null) webhookUrl = (String) body.get("webhook_url");

        String accessToken = (String) (body.getOrDefault("accessToken", body.get("access_token")));
        if (accessToken == null) accessToken = (String) body.get("token");

        System.out.println("Webhook URL: " + webhookUrl);
        System.out.println("Access token: " + (accessToken != null ? "[present]" : "[missing]"));

        // 2) Decide question based on last two digits of regNo
        int lastTwo = extractLastTwoDigits(regNo);
        boolean isOdd = (lastTwo % 2) == 1;

        String finalQuery;
        if (isOdd) {
            finalQuery = getQuestion1Query();
            System.out.println("Selected Question 1 (odd): lastTwo=" + lastTwo);
        } else {
            finalQuery = getQuestion2Query();
            System.out.println("Selected Question 2 (even): lastTwo=" + lastTwo);
        }

        // 3) Save final query locally
        Path outDir = Path.of("output");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("final-query.txt"), finalQuery);
        Files.writeString(outDir.resolve("solution.sql"), finalQuery);

        // 4) Send finalQuery to webhook URL with Authorization header
        if (webhookUrl == null || accessToken == null) {
            System.err.println("Missing webhookUrl or accessToken, cannot POST finalQuery.");
            return;
        }

        HttpHeaders h2 = new HttpHeaders();
        h2.setContentType(MediaType.APPLICATION_JSON);
        // Use Bearer scheme (PDF asked to use JWT in Authorization header)
        h2.set("Authorization", "Bearer " + accessToken);

        FinalQueryRequest fq = new FinalQueryRequest(finalQuery);
        HttpEntity<FinalQueryRequest> postEntity = new HttpEntity<>(fq, h2);
        System.out.println("Sending finalQuery to webhook...");
        ResponseEntity<String> postResp = restTemplate.postForEntity(webhookUrl, postEntity, String.class);
        System.out.println("Webhook POST response: status=" + postResp.getStatusCode());
        System.out.println("Body: " + postResp.getBody());
    }

    private int extractLastTwoDigits(String regNo) {
        String digits = regNo.replaceAll("\\D+", "");
        if (digits.length() == 0) return 0;
        if (digits.length() == 1) return Integer.parseInt(digits);
        String last2 = digits.substring(digits.length() - 2);
        return Integer.parseInt(last2);
    }

    private String getQuestion1Query() {
        return """
               SELECT
                 p.max_amount AS SALARY,
                 CONCAT(e.FIRST_NAME, ' ', e.LAST_NAME) AS NAME,
                 TIMESTAMPDIFF(YEAR, e.DOB, CURDATE()) AS AGE,
                 d.DEPARTMENT_NAME
               FROM (
                 SELECT MAX(AMOUNT) AS max_amount
                 FROM PAYMENTS
                 WHERE DAY(PAYMENT_TIME) <> 1
               ) p
               JOIN PAYMENTS pay
                 ON pay.AMOUNT = p.max_amount
                 AND DAY(pay.PAYMENT_TIME) <> 1
               JOIN EMPLOYEE e ON e.EMP_ID = pay.EMP_ID
               JOIN DEPARTMENT d ON d.DEPARTMENT_ID = e.DEPARTMENT;
               """;
    }

    private String getQuestion2Query() {
        return """
               SELECT
                 e.EMP_ID,
                 e.FIRST_NAME,
                 e.LAST_NAME,
                 d.DEPARTMENT_NAME,
                 (
                   SELECT COUNT(1)
                   FROM EMPLOYEE e2
                   WHERE e2.DEPARTMENT = e.DEPARTMENT
                     AND TIMESTAMPDIFF(YEAR, e2.DOB, CURDATE()) < TIMESTAMPDIFF(YEAR, e.DOB, CURDATE())
                 ) AS YOUNGER_EMPLOYEES_COUNT
               FROM EMPLOYEE e
               JOIN DEPARTMENT d ON d.DEPARTMENT_ID = e.DEPARTMENT
               ORDER BY e.EMP_ID DESC;
               """;
    }
}
