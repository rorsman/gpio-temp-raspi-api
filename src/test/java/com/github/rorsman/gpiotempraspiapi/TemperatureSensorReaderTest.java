package com.github.rorsman.gpiotempraspiapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemperatureSensorReaderTest {
    private static final String ID = "28-000000000001";

    @TempDir Path root;

    private TemperatureSensorReader service(String id) {
        return new TemperatureSensorReader(new SensorProperties(root, id, null));
    }

    private void write(String id, String content) throws IOException {
        Path device = Files.createDirectories(root.resolve(id));
        Files.writeString(device.resolve("w1_slave"), content);
    }

    @ParameterizedTest
    @ValueSource(ints = {23125, -10125, 0, -55000, 125000})
    void readsCelsiusAndRereadsOnEverySample(int milliCelsius) throws IOException {
        write(ID, "aa : crc=aa YES\naa t=" + milliCelsius + "\n");
        var reading = service("").readTemperature();
        assertThat(reading.sensorId()).isEqualTo(ID);
        assertThat(reading.temperatureCelsius())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(milliCelsius, 3));
        assertThat(reading.measuredAt()).isNotNull();
        write(ID, "aa : crc=aa YES\naa t=1000\n");
        assertThat(service("").readTemperature().temperatureCelsius())
                .isEqualByComparingTo("1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "aa : crc=aa NO\naa t=23125\n",
            "aa : crc=aa YES\naa\n",
            "aa : crc=aa YES\naa t=invalid\n",
            "aa : crc=aa YES\naa t=999999999999999999999\n",
            "aa : crc=aa YES\naa t=125001\n",
            "aa : crc=aa YES\naa t=-55001\n",
            "aa : crc=aa YES\n",
            ""
    })
    void rejectsInvalidReadings(String content) throws IOException {
        write(ID, content);
        assertThatThrownBy(() -> service("").readTemperature())
                .isInstanceOf(SensorUnavailableException.class);
    }

    @Test
    void reportsMissingSensor() {
        assertThatThrownBy(() -> service("").readTemperature())
                .isInstanceOf(SensorUnavailableException.class)
                .hasMessageContaining("No DS18B20");
    }

    @Test
    void reportsMissingDirectoryAndMissingSensorFile() throws IOException {
        var missing = new TemperatureSensorReader(new SensorProperties(root.resolve("missing"), "", null));
        assertThatThrownBy(missing::readTemperature)
                .isInstanceOf(SensorUnavailableException.class)
                .hasCauseInstanceOf(IOException.class);
        Files.createDirectory(root.resolve(ID));
        assertThatThrownBy(() -> service(ID).readTemperature())
                .isInstanceOf(SensorUnavailableException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void requiresSelectionWithMultipleSensors() throws IOException {
        write(ID, "aa : crc=aa YES\naa t=23125\n");
        write("28-000000000002", "aa : crc=aa YES\naa t=-5000\n");
        assertThatThrownBy(() -> service("").readTemperature())
                .isInstanceOf(SensorUnavailableException.class)
                .hasMessageContaining("Multiple sensors");
        assertThat(service("28-000000000002").readTemperature().temperatureCelsius())
                .isEqualByComparingTo("-5");
    }

    @Test
    void followsSysfsStyleDeviceSymlinks() throws IOException {
        Path target = Files.createDirectories(root.resolve("devices").resolve(ID));
        Files.writeString(target.resolve("w1_slave"), "aa : crc=aa YES\naa t=23125\n");
        Files.createSymbolicLink(root.resolve(ID), target);
        assertThat(service("").readTemperature().temperatureCelsius())
                .isEqualByComparingTo("23.125");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "/tmp/sensor", "10-000000000001", "28-invalid"})
    void rejectsInvalidSensorIds(String id) {
        assertThatThrownBy(() -> new SensorProperties(root, id, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
