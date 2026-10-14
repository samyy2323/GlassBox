package dev.glassbox.common;

import org.springframework.http.HttpStatus;

/** Thrown by services; rendered as problem+json by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final Integer retryAfterSeconds;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    /** Seconds to put in a Retry-After header, or null. */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException tooManyRequests(String m, int retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, m, retryAfterSeconds);
    }

    public static ApiException notFound(String m) { return new ApiException(HttpStatus.NOT_FOUND, m); }
    public static ApiException forbidden(String m) { return new ApiException(HttpStatus.FORBIDDEN, m); }
    public static ApiException unauthorized(String m) { return new ApiException(HttpStatus.UNAUTHORIZED, m); }
    public static ApiException conflict(String m) { return new ApiException(HttpStatus.CONFLICT, m); }
    public static ApiException unsupportedMediaType(String m) { return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, m); }
    public static ApiException tooLarge(String m) { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, m); }
    public static ApiException unprocessable(String m) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, m); }
    public static ApiException badRequest(String m) { return new ApiException(HttpStatus.BAD_REQUEST, m); }
}
