package com.github.rorsman.gpiotempraspiapi;

public class SensorUnavailableException extends RuntimeException {
    public SensorUnavailableException(String message) {
        super(message);
    }

    public SensorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
