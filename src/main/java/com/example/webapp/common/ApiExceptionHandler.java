package com.example.webapp.common;

import com.example.webapp.account.AccountService.AccountExistsException;
import java.util.LinkedHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.example.webapp.applications.ApplicationService;

/** Return actionable messages without exposing stack traces, passwords, or database details. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.example.webapp.actions.ActionService.MissingAction.class)
    ResponseEntity<ApiError> missingAction() {
        return ResponseEntity.status(404).body(new ApiError("ACTION_NOT_FOUND","This action could not be found."));
    }
    @ExceptionHandler(com.example.webapp.actions.ActionService.InvalidAction.class)
    ResponseEntity<ApiError> invalidAction(com.example.webapp.actions.ActionService.InvalidAction exception) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED","Check the highlighted fields.",exception.fields));
    }
    @ExceptionHandler(com.example.webapp.actions.ActionService.ActionConflict.class)
    ResponseEntity<ApiError> actionConflict(com.example.webapp.actions.ActionService.ActionConflict exception) {
        return ResponseEntity.status(409).body(new ApiError("ACTION_CHANGED",exception.getMessage()));
    }
    // Application errors share the same shape as authentication and form errors.
    @ExceptionHandler(ApplicationService.InvalidApplication.class)
    ResponseEntity<ApiError> invalidApplication(ApplicationService.InvalidApplication exception) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "Check the highlighted fields.", exception.fields));
    }

    @ExceptionHandler(ApplicationService.MissingApplication.class)
    ResponseEntity<ApiError> missingApplication() {
        return ResponseEntity.status(404).body(new ApiError("APPLICATION_NOT_FOUND", "This application could not be found."));
    }

    @ExceptionHandler({ApplicationService.StaleApplication.class, OptimisticLockingFailureException.class})
    ResponseEntity<ApiError> staleApplication() {
        return ResponseEntity.status(409).body(new ApiError("APPLICATION_CHANGED", "This application changed in another tab. Reload the latest version before saving."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidFilter() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_FILTER", "Check the status, page number, and page size."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        var fields = new LinkedHashMap<String, String>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "Check the highlighted fields.", fields));
    }

    @ExceptionHandler(AccountExistsException.class)
    ResponseEntity<ApiError> accountExists() {
        return ResponseEntity.status(409).body(new ApiError("SETUP_CLOSED", "Your account already exists. Please sign in."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Send a valid JSON request."));
    }

    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
    ResponseEntity<ApiError> databaseUnavailable(RuntimeException exception) {
        // Two transactions can read the same version before either writes. MongoDB's
        // WriteConflict (112) is an edit conflict, not a disconnected database.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.mongodb.MongoException mongo && mongo.getCode() == 112) {
                return staleApplication();
            }
        }
        return ResponseEntity.status(503).body(new ApiError("DATABASE_UNAVAILABLE", "Cannot reach the database. Check that MongoDB is running."));
    }
}
