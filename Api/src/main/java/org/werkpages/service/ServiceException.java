package org.werkpages.service;

/**
 * Domain exception carrying an HTTP status code so handlers can map it
 * to the right response without leaking HTTP concerns into services.
 */
public class ServiceException extends RuntimeException {

    private final int statusCode;

    public ServiceException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    public static ServiceException badRequest(String message) {
        return new ServiceException(400, message);
    }

    public static ServiceException unauthorized(String message) {
        return new ServiceException(401, message);
    }

    public static ServiceException forbidden(String message) {
        return new ServiceException(403, message);
    }

    public static ServiceException notFound(String message) {
        return new ServiceException(404, message);
    }

    public static ServiceException conflict(String message) {
        return new ServiceException(409, message);
    }

    public static ServiceException tooManyRequests(String message) {
        return new ServiceException(429, message);
    }
}
