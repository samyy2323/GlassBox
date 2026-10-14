package dev.glassbox.common;

import org.springframework.http.HttpStatus;

/** Thrown by services; rendered as problem+json by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
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
