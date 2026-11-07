package co.edu.uptc.k8s_api.dto;

import java.util.List;

public record GreetingDTO(String mensaje, String version, List<String> integrantes) {}
