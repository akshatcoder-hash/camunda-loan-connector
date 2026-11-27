package com.acme.loan.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.loan.connector.model.CreditCheckRequest;
import com.acme.loan.connector.model.CreditCheckResponse;
import com.sun.net.httpserver.HttpServer;
import io.camunda.connector.api.error.ConnectorRetryException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LegacyCoreBankingConnectorTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void creditCheckReturnsLegacyCreditScore() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    startServer(exchange -> {
      calls.incrementAndGet();
      respond(exchange, 200, "{\"creditScore\":742,\"riskBand\":\"LOW\",\"legacyReference\":\"CR-1\"}");
    });

    CreditCheckResponse response = new LegacyCoreBankingConnector().creditCheck(
        new CreditCheckRequest("AUTO-1", 15_000d, baseUrl(), 1_000));

    assertThat(response.creditScore()).isEqualTo(742);
    assertThat(response.riskBand()).isEqualTo("LOW");
    assertThat(calls).hasValue(1);
  }

  @Test
  void serverErrorIsClassifiedAsRetryable() throws IOException {
    startServer(exchange -> respond(exchange, 500, "{\"error\":\"core unavailable\"}"));

    assertThatThrownBy(() -> new LegacyCoreBankingConnector().creditCheck(
            new CreditCheckRequest("ERR-1", 15_000d, baseUrl(), 1_000)))
        .isInstanceOf(ConnectorRetryException.class)
        .extracting("errorCode")
        .isEqualTo("LEGACY_UNAVAILABLE");
  }

  @Test
  void slowResponseIsClassifiedAsTimeoutAndRetryable() throws IOException {
    startServer(exchange -> {
      try {
        Thread.sleep(500);
        respond(exchange, 200, "{\"creditScore\":742}");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    assertThatThrownBy(() -> new LegacyCoreBankingConnector().creditCheck(
            new CreditCheckRequest("SLOW-1", 15_000d, baseUrl(), 100)))
        .isInstanceOf(ConnectorRetryException.class)
        .extracting("errorCode")
        .isEqualTo("LEGACY_TIMEOUT");
  }

  private void startServer(Handler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/credit-checks", exchange -> handler.handle(exchange));
    server.start();
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  @FunctionalInterface
  private interface Handler {
    void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
  }
}
