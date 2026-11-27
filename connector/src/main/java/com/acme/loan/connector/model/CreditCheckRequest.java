package com.acme.loan.connector.model;

import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreditCheckRequest(
    @NotEmpty
        @TemplateProperty(group = "request", label = "Applicant ID", description = "Applicant identifier sent to the legacy system")
        String applicantId,
    @NotNull
        @TemplateProperty(group = "request", label = "Loan amount", description = "Requested loan amount", type = PropertyType.Number)
        Double amount,
    @NotEmpty
        @TemplateProperty(group = "connection", label = "Base URL", defaultValue = "http://mock-legacy:8080", description = "Mock legacy core banking base URL")
        String baseUrl,
    @NotNull @Positive
        @TemplateProperty(group = "connection", label = "Timeout (ms)", defaultValue = "1800", defaultValueType = DefaultValueType.Number, type = PropertyType.Number, description = "HTTP request timeout in milliseconds")
        Integer timeoutMillis) {}
