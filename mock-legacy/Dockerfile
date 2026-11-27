ARG CAMUNDA_VERSION=8.10.12
FROM registry.camunda.cloud/camunda/camunda:${CAMUNDA_VERSION}
WORKDIR /app
COPY target/mock-legacy-core-banking-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
