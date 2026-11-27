package com.acme.loan.connector.model;

import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record NotificationRequest(
    @NotEmpty @TemplateProperty(group = "request", label = "Applicant ID") String applicantId,
    @NotEmpty @TemplateProperty(group = "request", label = "Decision") String decision,
    @NotEmpty @TemplateProperty(group = "request", label = "Message", type = PropertyType.Text) String message,
    @NotEmpty @TemplateProperty(group = "connection", label = "Base URL", defaultValue = "http://mock-legacy:8080") String baseUrl,
    @NotNull @Positive @TemplateProperty(group = "connection", label = "Timeout (ms)", defaultValue = "1800", defaultValueType = DefaultValueType.Number, type = PropertyType.Number) Integer timeoutMillis) {}
