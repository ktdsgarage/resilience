// File: membership/common/src/main/java/com/telecom/membership/common/exception/PointException.java
package com.telecom.membership.common.exception;

public class PointException extends RuntimeException {
    public static final String INVALID_AMOUNT = "Invalid amount: Amount must be greater than zero";
    public static final String DATABASE_ERROR = "Database error occurred";

    public static class RateLimitExceededException extends PointException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }

    public static class BulkheadFullException extends PointException {
        public BulkheadFullException(String message) {
            super(message);
        }
    }

    public static class CircuitBreakerOpenException extends PointException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }

    public static class DatabaseException extends PointException {
        public DatabaseException(String message) {
            super(message);
        }
    }

    public static class EventPublishException extends RuntimeException {
        public EventPublishException(String message) {
            super(message);
        }

        public EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }

        public EventPublishException(Throwable cause) {
            super(cause);
        }
    }

    public PointException(String message) {
        super(message);
    }

    public PointException(String message, Throwable cause) {
        super(message, cause);
    }
}