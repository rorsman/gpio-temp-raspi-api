package com.github.rorsman.gpiotempraspiapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemperatureServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    private final TemperatureSensorReader reader = mock(TemperatureSensorReader.class);
    private final Clock clock = mock(Clock.class);
    private final SensorProperties properties =
            new SensorProperties(Path.of("/unused"), "", Duration.ofSeconds(5));
    private final TemperatureService service = new TemperatureService(reader, properties, clock);

    private TemperatureReading reading(String celsius, Instant time) {
        return new TemperatureReading("28-000000000001", new BigDecimal(celsius), time);
    }

    @Test
    void returns503BeforeFirstSampleWithoutReadingSensor() {
        assertThatThrownBy(service::readCurrentTemperature)
                .isInstanceOf(SensorUnavailableException.class)
                .hasMessageContaining("No temperature sample");
        verifyNoInteractions(reader);
    }

    @Test
    void requestsReuseSampleUntilSamplerPublishesReplacement() {
        when(clock.instant()).thenReturn(NOW);
        var first = reading("23.125", NOW);
        var second = reading("-1.25", NOW.plusSeconds(1));
        when(reader.readTemperature()).thenReturn(first, second);
        service.sampleTemperature();
        assertThat(service.readCurrentTemperature()).isSameAs(first);
        assertThat(service.readCurrentTemperature()).isSameAs(first);
        verify(reader, times(1)).readTemperature();
        service.sampleTemperature();
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        assertThat(service.readCurrentTemperature()).isSameAs(second);
    }

    @Test
    void keepsLastValidSampleOnFailureButRejectsItWhenStaleAndRecovers() {
        when(clock.instant()).thenReturn(NOW);
        var first = reading("23.125", NOW);
        var recovered = reading("24", NOW.plusSeconds(6));
        when(reader.readTemperature()).thenReturn(first)
                .thenThrow(new SensorUnavailableException("CRC failure"))
                .thenReturn(recovered);
        service.sampleTemperature();
        service.sampleTemperature();
        assertThat(service.readCurrentTemperature()).isSameAs(first);
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        assertThat(service.readCurrentTemperature()).isSameAs(first);
        when(clock.instant()).thenReturn(NOW.plusSeconds(5).plusNanos(1));
        assertThatThrownBy(service::readCurrentTemperature)
                .isInstanceOf(SensorUnavailableException.class)
                .hasMessageContaining("too old");
        service.sampleTemperature();
        when(clock.instant()).thenReturn(NOW.plusSeconds(6));
        assertThat(service.readCurrentTemperature()).isSameAs(recovered);
    }

    @Test
    void initialFailureDoesNotPreventLaterSuccessfulSampling() {
        when(clock.instant()).thenReturn(NOW);
        when(reader.readTemperature())
                .thenThrow(new SensorUnavailableException("No sensor found"))
                .thenReturn(reading("23", NOW));
        service.sampleTemperature();
        assertThatThrownBy(service::readCurrentTemperature)
                .isInstanceOf(SensorUnavailableException.class)
                .hasMessage("No sensor found");
        service.sampleTemperature();
        assertThat(service.readCurrentTemperature().temperatureCelsius()).isEqualByComparingTo("23");
    }

    @Test
    void requestsDoNotWaitForAnInProgressConversion() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        var first = reading("23", NOW);
        when(reader.readTemperature()).thenReturn(first);
        service.sampleTemperature();

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.readTemperature()).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Read was not released");
            }
            return reading("24", NOW);
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sampling = executor.submit(service::sampleTemperature);
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var response = executor.submit(service::readCurrentTemperature);
                assertThat(response.get(1, TimeUnit.SECONDS)).isSameAs(first);
            } finally {
                release.countDown();
            }
            sampling.get(5, TimeUnit.SECONDS);
        }
        assertThat(service.readCurrentTemperature().temperatureCelsius()).isEqualByComparingTo("24");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveMaximumAge(long seconds) {
        assertThatThrownBy(() -> new SensorProperties(Path.of("/unused"), "", Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
