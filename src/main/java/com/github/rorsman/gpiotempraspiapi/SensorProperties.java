package com.github.rorsman.gpiotempraspiapi;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sensor")
public record SensorProperties(Path basePath, String id, Duration maxAge) {
    public SensorProperties {
        if (maxAge == null) {
            maxAge = Duration.ofSeconds(5);
        }
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("sensor.max-age must be positive");
        }
        if (basePath == null) {
            basePath = Path.of("/sys/bus/w1/devices");
        }
        id = id == null ? "" : id.trim();
        if (!id.isEmpty() && !id.matches("28-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("sensor.id must be a DS18B20 ID such as 28-000000000001");
        }
    }
}
