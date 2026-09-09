package com.mamtrex.hospital.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Single owner of shared client-error mapping (docs/plan1.md Task 3): every
 * controller-local handler for malformed path values and malformed request
 * bodies was centralized here so the stable {@link ApiError} JSON shape is
 * produced in exactly one place.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
 @ExceptionHandler(NotFoundException.class) ResponseEntity<ApiError> notFound(NotFoundException ex,HttpServletRequest r){return ResponseEntity.status(404).body(new ApiError(Instant.now(),404,"Not Found",ex.getMessage(),r.getRequestURI()));}
 @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex,HttpServletRequest r){String m=ex.getBindingResult().getFieldErrors().stream().map(e->e.getField()+": "+e.getDefaultMessage()).collect(Collectors.joining(", "));return ResponseEntity.badRequest().body(new ApiError(Instant.now(),400,"Validation Error",m,r.getRequestURI()));}
 @ExceptionHandler(MethodArgumentTypeMismatchException.class) ResponseEntity<ApiError> malformedPathValue(MethodArgumentTypeMismatchException ex,HttpServletRequest r){return ResponseEntity.badRequest().body(new ApiError(Instant.now(),400,"Validation Error","Invalid path value: "+ex.getName(),r.getRequestURI()));}
 @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<ApiError> malformedBody(HttpMessageNotReadableException ex,HttpServletRequest r){return ResponseEntity.badRequest().body(new ApiError(Instant.now(),400,"Validation Error","Malformed request body",r.getRequestURI()));}
}
