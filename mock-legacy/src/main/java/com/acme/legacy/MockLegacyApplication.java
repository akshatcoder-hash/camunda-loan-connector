package com.acme.legacy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class MockLegacyApplication {

  private static final Logger LOGGER = LoggerFactory.getLogger(MockLegacyApplication.class);
  private final AtomicInteger creditCheckCalls = new AtomicInteger();

  public static void main(String[] args) {
    SpringApplication.run(MockLegacyApplication.class, args);
  }

  @PostMapping("/api/credit-checks")
  public ResponseEntity<Map<String, Object>> creditCheck(@RequestBody CreditCheckRequest request) throws InterruptedException {
    int call = creditCheckCalls.incrementAndGet();
    String applicantId = request.applicantId() == null ? "UNKNOWN" : request.applicantId();
    boolean forcedFailure = applicantId.startsWith("ERR");
    boolean occasionalFailure = !forcedFailure && call % 5 == 0;
    long delayMillis = applicantId.startsWith("SLOW") ? 2_600 : 450;

    LOGGER.info("legacy credit check call={} applicant={} amount={} delayMs={} forcedFailure={} occasionalFailure={}",
        call, applicantId, request.amount(), delayMillis, forcedFailure, occasionalFailure);
    Thread.sleep(delayMillis);

    if (forcedFailure || occasionalFailure) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
          "error", "CORE_BANKING_UNAVAILABLE",
          "message", forcedFailure ? "forced failure for error-path demo" : "simulated intermittent core banking failure",
          "at", Instant.now().toString()));
    }

    int score = applicantId.startsWith("LOW") ? 590 : 690 + Math.floorMod(applicantId.hashCode(), 90);
    String riskBand = score >= 700 ? "LOW" : score >= 650 ? "MEDIUM" : "HIGH";
    return ResponseEntity.ok(Map.of(
        "applicantId", applicantId,
        "creditScore", score,
        "riskBand", riskBand,
        "legacyReference", "CR-" + Math.floorMod(applicantId.hashCode(), 100_000),
        "processingMs", delayMillis));
  }

  @PostMapping("/api/notifications")
  public Map<String, Object> notify(@RequestBody NotificationRequest request) throws InterruptedException {
    Thread.sleep(150);
    LOGGER.info("legacy notification applicant={} decision={} message={}",
        request.applicantId(), request.decision(), request.message());
    return Map.of(
        "notificationId", UUID.randomUUID().toString(),
        "status", "QUEUED",
        "applicantId", request.applicantId());
  }

  public record CreditCheckRequest(String applicantId, Double amount) {}

  public record NotificationRequest(String applicantId, String decision, String message) {}
}
