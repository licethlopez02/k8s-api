package co.edu.uptc.k8s_api.dto;

import java.util.List;

// Decisión: Se implementa un 'record' (disponible desde Java 14+) en lugar de una clase 
// tradicional (POJO). Esto garantiza la inmutabilidad de los datos de transferencia, 
// reduce el código repetitivo (getters/setters implícitos) y cumple con las mejores 
// prácticas modernas de Spring Boot para estructurar respuestas JSON.
public record ContainerInfoDTO(
        String horaContenedor,          // Cumple requisito: Hora del contenedor
        List<String> integrantes,       // Cumple requisito: Nombres de los integrantes del grupo
        String idContenedor,            // Cumple requisito: Nombre o id del contenedor
        String almacenamientoTotalMB,   // Cumple requisito: Cantidad de almacenamiento
        String ramAsignadaMB            // Cumple requisito: Cantidad de RAM
) {}