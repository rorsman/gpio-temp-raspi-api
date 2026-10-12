package com.github.rorsman.gpiotempraspiapi;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TemperatureService {
    private static final Logger LOG = LoggerFactory.getLogger(TemperatureService.class);
    private final TemperatureSensorReader reader;
    private final SensorProperties properties;
    private final Clock clock;
    private final AtomicReference<Sample> sample =
            new AtomicReference<>(new Sample(null, "No temperature sample is available yet."));

    @Autowired
    public TemperatureService(TemperatureSensorReader reader, SensorProperties properties) {
        this(reader, properties, Clock.systemUTC());
    }

    TemperatureService(TemperatureSensorReader reader, SensorProperties properties, Clock clock) {
        this.reader = reader;
        this.properties = properties;
        this.clock = clock;
    }

    // Fixed delay starts after the previous read completes, preventing overlapping conversions.
    @Scheduled(fixedDelayString = "${sensor.sample-delay-ms:1000}")
    public synchronized void sampleTemperature() {
        try {
            sample.set(new Sample(reader.readTemperature(), null));
        } catch (SensorUnavailableException exception) {
            Sample previous = sample.get();
            if (!exception.getMessage().equals(previous.error())) {
                LOG.warn("Temperature sampling failed: {}", exception.getMessage());
            }
            // Keep the last successful reading until its freshness limit expires.
            sample.set(new Sample(previous.reading(), exception.getMessage()));
        }
    }

    public TemperatureReading readCurrentTemperature() {
        Sample current = sample.get();
        TemperatureReading reading = current.reading();
        if (reading == null) {
            throw new SensorUnavailableException(current.error());
        }
        if (clock.instant().isAfter(reading.measuredAt().plus(properties.maxAge()))) {
            throw new SensorUnavailableException("The latest temperature sample is too old.");
        }
        return reading;
    }

    private record Sample(TemperatureReading reading, String error) {}
}
