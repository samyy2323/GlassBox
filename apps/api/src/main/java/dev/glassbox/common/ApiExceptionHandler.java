package dev.glassbox.common;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handle(ApiException e) {
        return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ProblemDetail tooLarge() {
        return ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the 25 MB limit");
    }
}
