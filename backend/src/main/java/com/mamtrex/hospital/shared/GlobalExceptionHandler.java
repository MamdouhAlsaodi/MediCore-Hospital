package com.mamtrex.hospital.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
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

 /**
  * Conflict mapping for illegal lifecycle transitions (docs/plan2.md Task 2):
  * services throw InvalidStateTransitionException with a controlled
  * client-safe message and no cause, so the message passes through while
  * entity and persistence internals never leak.
  */
 @ExceptionHandler(InvalidStateTransitionException.class) ResponseEntity<ApiError> invalidTransition(InvalidStateTransitionException ex,HttpServletRequest r){return ResponseEntity.status(409).body(new ApiError(Instant.now(),409,"Conflict",ex.getMessage(),r.getRequestURI()));}

 /**
  * Conflict mapping for duplicate natural keys (duplicate-MRN gap fix): the
  * PatientService pre-check throws DuplicateKeyException with a controlled
  * client-safe message and no cause, while a race-lost DB unique-constraint
  * violation arrives Spring-translated with an internal cause whose text
  * must never leak SQL internals — so only cause-free messages pass through
  * and translated violations receive a fixed generic conflict message.
  */
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex,HttpServletRequest r){String m=ex.getCause()==null?ex.getMessage():"Resource conflict: the record already exists or violates a data integrity constraint";return ResponseEntity.status(409).body(new ApiError(Instant.now(),409,"Conflict",m,r.getRequestURI()));}

 /**
  * Conflict mapping for concurrent modification (docs/plan3.md Task 6 bed
  * lifecycle): two racing mutations of the same row hit the existing JPA
  * optimistic-lock (@Version) mechanism, which surfaces here as a
  * Spring-translated failure whose internals must never leak — so the
  * response always carries this one fixed generic conflict message, and
  * the losing mutation records no audit event (its transaction rolled
  * back before any audit write committed).
  */
 @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class) ResponseEntity<ApiError> optimisticConflict(org.springframework.orm.ObjectOptimisticLockingFailureException ex,HttpServletRequest r){return ResponseEntity.status(409).body(new ApiError(Instant.now(),409,"Conflict","Resource conflict: the record was modified by another request",r.getRequestURI()));}
 @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex,HttpServletRequest r){String m=ex.getBindingResult().getFieldErrors().stream().map(e->e.getField()+": "+e.getDefaultMessage()).collect(Collectors.joining(", "));return ResponseEntity.badRequest().body(new ApiError(Instant.now(),400,"Validation Error",m,r.getRequestURI()));}
 @ExceptionHandler(MethodArgumentTypeMismatchException.class) ResponseEntity<ApiError> malformedPathValue(MethodArgumentTypeMismatchException ex,HttpServletRequest r){return ResponseEntity.badRequest().body(new ApiError(Instant.now(),400,"Validation Error","Invalid path value: "+ex.getName(),r.getRequestURI()));}
 @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<ApiError> malformedBody(HttpMessageNotReadableException ex,HttpServletRequest r){return ResponseEntity.badRequest().body(new ApiError(Instant.now(),400,"Validation Error","Malformed request body",r.getRequestURI()));}
}
