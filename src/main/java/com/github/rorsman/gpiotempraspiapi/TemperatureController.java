package com.github.rorsman.gpiotempraspiapi;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TemperatureController {
    private final TemperatureService service;

    public TemperatureController(TemperatureService service) {
        this.service = service;
    }

    @GetMapping("/api/temperature")
    public ResponseEntity<TemperatureReading> currentTemperature() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.readCurrentTemperature());
    }

    @ExceptionHandler(SensorUnavailableException.class)
    public ResponseEntity<ProblemDetail> sensorUnavailable(SensorUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setTitle("Temperature sensor unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}
