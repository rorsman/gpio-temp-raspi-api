# Build on the builder's architecture; the Java JAR is architecture independent.
FROM --platform=$BUILDPLATFORM maven:3.9-amazoncorretto-25-debian AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp verify

# Alpine avoids Amazon Linux 2023's ARMv8.2+crypto requirement on older Raspberry Pis.
FROM amazoncorretto:25-alpine
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/gpio-temp-raspi-api.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
