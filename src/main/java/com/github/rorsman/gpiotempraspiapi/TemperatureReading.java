package com.github.rorsman.gpiotempraspiapi;

import java.math.BigDecimal;
import java.time.Instant;

public record TemperatureReading(String sensorId, BigDecimal temperatureCelsius, Instant measuredAt) {}
