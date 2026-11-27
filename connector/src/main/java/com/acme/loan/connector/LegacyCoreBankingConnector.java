package com.acme.loan.connector;

import com.acme.loan.connector.model.CreditCheckRequest;
import com.acme.loan.connector.model.CreditCheckResponse;
import com.acme.loan.connector.model.NotificationRequest;
import com.acme.loan.connector.model.NotificationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.annotation.Operation;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.annotation.Variable;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorInputException;
import io.camunda.connector.api.error.ConnectorRetryException;
import io.camunda.connector.api.outbound.OutboundConnectorProvider;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OutboundConnector(name = "Legacy Core Banking", type = "com.acme:legacy-core-banking:1")
@ElementTemplate(
    id = "com.acme.loan.legacy-core-banking.v1",
    name = "Legacy Core Banking Connector",
    version = 1,
    description = "Calls a legacy core banking API for credit checks and applicant notifications.",
    documentationRef = "https://github.com/akshatcoder-hash/camunda-loan-connector")
public class LegacyCoreBankingConnector implements OutboundConnectorProvider {

  private static final Logger LOGGER = LoggerFactory.getLogger(LegacyCoreBankingConnector.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

  private final HttpClient httpClient;

  public LegacyCoreBankingConnector() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  LegacyCoreBankingConnector(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Operation(id = "creditCheck", name = "Run credit check")
  public CreditCheckResponse creditCheck(@Variable CreditCheckRequest request) {
    validateAmount(request.amount());
    LOGGER.info("Running legacy credit check for applicant {}", request.applicantId());
    long started = System.nanoTime();
    JsonNode body = postForJson(
        request.baseUrl(),
        "/api/credit-checks",
        Map.of("applicantId", request.applicantId(), "amount", request.amount()),
        request.timeoutMillis());
    Integer creditScore = integerValue(body, "creditScore");
    if (creditScore == null) {
      throw new ConnectorException("LEGACY_BAD_RESPONSE", "Credit check response did not contain a numeric creditScore");
    }
    return new CreditCheckResponse(
        request.applicantId(),
        creditScore,
        textValue(body, "riskBand"),
        textValue(body, "legacyReference"),
        200,
        elapsedMillis(started));
  }

  @Operation(id = "notifyApplicant", name = "Notify applicant")
  public NotificationResponse notifyApplicant(@Variable NotificationRequest request) {
    LOGGER.info("Sending legacy notification for applicant {} with decision {}", request.applicantId(), request.decision());
    long started = System.nanoTime();
    JsonNode body = postForJson(
        request.baseUrl(),
        "/api/notifications",
        Map.of(
            "applicantId", request.applicantId(),
            "decision", request.decision(),
            "message", request.message()),
        request.timeoutMillis());
    return new NotificationResponse(
        textValue(body, "notificationId"),
        textValue(body, "status"),
        200,
        elapsedMillis(started));
  }

  private JsonNode postForJson(String baseUrl, String path, Map<String, Object> payload, int timeoutMillis) {
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(normalizeBaseUrl(baseUrl) + path))
          .timeout(Duration.ofMillis(timeoutMillis))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload)))
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status >= 500) {
        throw retryable("LEGACY_UNAVAILABLE", "Legacy system returned HTTP " + status, null);
      }
      if (status >= 400) {
        throw new ConnectorException("LEGACY_CLIENT_ERROR", "Legacy system rejected the request with HTTP " + status + ": " + abbreviate(response.body()));
      }
      if (status < 200 || status >= 300) {
        throw new ConnectorException("LEGACY_BAD_RESPONSE", "Legacy system returned unexpected HTTP " + status);
      }
      return OBJECT_MAPPER.readTree(response.body());
    } catch (HttpTimeoutException e) {
      throw retryable("LEGACY_TIMEOUT", "Legacy system call timed out after " + timeoutMillis + " ms", e);
    } catch (IOException e) {
      throw retryable("LEGACY_UNAVAILABLE", "Legacy system call failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw retryable("LEGACY_UNAVAILABLE", "Legacy system call was interrupted", e);
    }
  }

  private static ConnectorRetryException retryable(String code, String message, Throwable cause) {
    return ConnectorRetryException.builder()
        .errorCode(code)
        .message(message)
        .cause(cause)
        .backoffDuration(RETRY_BACKOFF)
        .build();
  }

  private static void validateAmount(Double amount) {
    if (amount != null && amount <= 0) {
      throw new ConnectorInputException("Loan amount must be positive");
    }
  }

  private static String normalizeBaseUrl(String baseUrl) {
    String normalized = baseUrl == null ? "" : baseUrl.trim();
    if (normalized.endsWith("/")) {
      return normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static Integer integerValue(JsonNode body, String field) {
    JsonNode node = body.get(field);
    return node != null && node.isNumber() ? node.intValue() : null;
  }

  private static String textValue(JsonNode body, String field) {
    JsonNode node = body.get(field);
    return node != null && node.isTextual() ? node.textValue() : null;
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 160 ? body : body.substring(0, 160) + "...";
  }
}
