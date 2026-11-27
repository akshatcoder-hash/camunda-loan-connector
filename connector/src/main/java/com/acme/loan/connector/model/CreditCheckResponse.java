package com.acme.loan.connector.model;

public record CreditCheckResponse(
    String applicantId,
    Integer creditScore,
    String riskBand,
    String legacyReference,
    Integer upstreamStatus,
    Long latencyMillis) {}
