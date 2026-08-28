package com.lowcode.platform.domain.exception;

import com.lowcode.platform.execution.RunCapacityExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body(ex.getMessage()));
    }

    @ExceptionHandler(RunCapacityExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRunCapacityExceeded(RunCapacityExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(ex.getMessage()));
    }

    @ExceptionHandler(ReferencedByScenariosException.class)
    public ResponseEntity<Map<String, Object>> handleReferenced(ReferencedByScenariosException ex) {
        List<Map<String, Object>> referencingScenarios = ex.getReferencingScenarios().stream()
                .map(s -> Map.<String, Object>of("id", s.getId(), "name", s.getName()))
                .toList();
        Map<String, Object> body = new HashMap<>(body(ex.getMessage()));
        body.put("referencingScenarios", referencingScenarios);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Общий предохранитель: любое нарушение constraint'а на уровне БД (FK, unique,
     * not-null), которое не смоделировано отдельным доменным исключением выше, не
     * должно долетать до клиента голым 500 со стектрейсом. Конкретно этот баг уже
     * пофикшен на уровне схемы (см. V4__fix_entry_point_delete_cascade.sql), но
     * это тот класс ошибок, который лучше не оставлять необработанным в принципе.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("The operation violates a data integrity constraint"));
    }

    /**
     * Ревью CTO, п.3.1: без этого fallback'а необработанное исключение уходило
     * бы в дефолтный обработчик Spring Boot — не всегда безобидно (может
     * протечь внутренняя деталь реализации в теле ответа). Логируем стектрейс
     * на сервере, клиенту — обезличенное сообщение.
     * NB (та же п.3.1): IllegalStateException/IllegalArgumentException выше
     * ловятся слишком широко — это JDK-типы, которые может бросить и глубина
     * Spring/Hibernate, не только наш домен. Замена на специфичные доменные
     * исключения — отдельная задача, не в этом заходе.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("Internal server error"));
    }

    private Map<String, Object> body(String message) {
        return Map.of("timestamp", Instant.now().toString(), "message", message);
    }
}
