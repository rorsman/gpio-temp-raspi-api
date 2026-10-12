package com.github.rorsman.gpiotempraspiapi;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class TemperatureApiIntegrationTest {
    @TempDir static Path root;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void sensorProperties(DynamicPropertyRegistry registry) {
        registry.add("sensor.base-path", () -> root.toString());
        registry.add("sensor.id", () -> "28-000000000001");
        registry.add("sensor.sample-delay-ms", () -> "50");
        registry.add("sensor.max-age", () -> "300ms");
    }

    @Test
    void schedulerUpdatesResponsesExpiresFailuresAndRecovers() throws Exception {
        mvc.perform(get("/api/temperature"))
                .andExpect(status().isServiceUnavailable());

        write("aa : crc=aa YES\naa t=23125\n");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/api/temperature"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.sensorId").value("28-000000000001"))
                        .andExpect(jsonPath("$.temperatureCelsius").value(23.125))
                        .andExpect(jsonPath("$.measuredAt").isNotEmpty()));

        write("aa : crc=aa YES\naa t=-1250\n");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/api/temperature"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.temperatureCelsius").value(-1.25)));

        write("aa : crc=aa NO\naa t=-1250\n");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/api/temperature"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                        .andExpect(jsonPath("$.status").value(503))
                        .andExpect(jsonPath("$.title").value("Temperature sensor unavailable")));

        write("aa : crc=aa YES\naa t=1000\n");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/api/temperature"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.temperatureCelsius").value(1)));

        Files.delete(root.resolve("28-000000000001/w1_slave"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/api/temperature"))
                        .andExpect(status().isServiceUnavailable()));
    }

    private void write(String content) throws IOException {
        Path device = Files.createDirectories(root.resolve("28-000000000001"));
        Path pending = device.resolve("pending");
        Files.writeString(pending, content);
        Files.move(pending, device.resolve("w1_slave"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
