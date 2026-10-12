package com.github.rorsman.gpiotempraspiapi;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TemperatureSensorReader {
    private static final Pattern TEMPERATURE = Pattern.compile("(?:^|\\s)t=(-?\\d+)\\s*$");
    private final SensorProperties properties;

    public TemperatureSensorReader(SensorProperties properties) {
        this.properties = properties;
    }

    public TemperatureReading readTemperature() {
        try {
            Path device = resolveDevice();
            // Opening w1_slave triggers a new conversion in the Linux driver.
            List<String> lines = Files.readAllLines(device.resolve("w1_slave"), StandardCharsets.US_ASCII);
            BigDecimal temperature = parseTemperature(lines);
            return new TemperatureReading(device.getFileName().toString(), temperature, Instant.now());
        } catch (IOException e) {
            throw new SensorUnavailableException("Unable to read the temperature sensor.", e);
        }
    }

    private Path resolveDevice() throws IOException {
        if (!properties.id().isEmpty()) {
            return properties.basePath().resolve(properties.id());
        }
        try (var entries = Files.list(properties.basePath())) {
            List<Path> devices = entries
                    .filter(path -> path.getFileName().toString().matches("28-[0-9a-fA-F]{12}"))
                    .filter(Files::isDirectory)
                    .toList();
            if (devices.isEmpty()) {
                throw new SensorUnavailableException("No DS18B20 sensor was found.");
            }
            if (devices.size() > 1) {
                throw new SensorUnavailableException("Multiple sensors were found. Set SENSOR_ID to select one.");
            }
            return devices.getFirst();
        }
    }

    private BigDecimal parseTemperature(List<String> lines) {
        if (lines.size() != 2 || !lines.getFirst().trim().endsWith(" YES")) {
            throw new SensorUnavailableException("The sensor reading failed its CRC check.");
        }
        Matcher match = TEMPERATURE.matcher(lines.get(1));
        if (!match.find()) {
            throw new SensorUnavailableException("The sensor returned an invalid temperature.");
        }
        try {
            int milliCelsius = Integer.parseInt(match.group(1));
            if (milliCelsius < -55_000 || milliCelsius > 125_000) {
                throw new SensorUnavailableException("The temperature is outside the DS18B20 range.");
            }
            return BigDecimal.valueOf(milliCelsius, 3);
        } catch (NumberFormatException e) {
            throw new SensorUnavailableException("The sensor returned an invalid temperature.", e);
        }
    }
}
