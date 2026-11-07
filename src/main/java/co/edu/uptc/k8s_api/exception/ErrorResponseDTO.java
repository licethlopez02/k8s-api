package co.edu.uptc.k8s_api.exception;

public record ErrorResponseDTO(
        String timestamp,
        int status,
        String error,
        String message,
        String path
) {}
