package fr.dossierfacile.garbagecollector.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class OvhConnectionFailedException extends RuntimeException {
    public OvhConnectionFailedException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
