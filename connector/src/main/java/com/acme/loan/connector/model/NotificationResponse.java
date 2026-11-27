package com.acme.loan.connector.model;

public record NotificationResponse(
    String notificationId,
    String status,
    Integer upstreamStatus,
    Long latencyMillis) {}
