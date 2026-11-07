package co.edu.uptc.k8s_api.controller;

import co.edu.uptc.k8s_api.dto.ContainerInfoDTO;
import co.edu.uptc.k8s_api.dto.AppVersionDTO;
import co.edu.uptc.k8s_api.dto.GreetingDTO;
import co.edu.uptc.k8s_api.dto.PiResponseDTO;
import co.edu.uptc.k8s_api.service.InfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Decisión: Se usa @RestController para asegurar que las respuestas se serialicen 
// automáticamente a JSON. Se define la ruta base "/api" para modularidad.
@RestController
@RequestMapping("/api")
public class InfoController {

    private final InfoService infoService;

    // Decisión: Se aplica Inyección de Dependencias por constructor (recomendado 
    // en Spring Boot) para garantizar que el controlador no se instancie si el servicio 
    // falla o no está disponible.
    public InfoController(InfoService infoService) {
        this.infoService = infoService;
    }

    // Decisión: Se expone el endpoint GET mapeado a "/info" para entregar los 5 
    // requerimientos del taller de forma estructurada mediante el DTO.
    @GetMapping("/info")
    public ContainerInfoDTO returnInfo() {
        return infoService.getContainerInfo();
    }

    @GetMapping("/version")
    public AppVersionDTO returnVersion() {
        return infoService.getAppVersion();
    }

    @GetMapping("/greeting")
    public GreetingDTO returnGreeting() {
        return infoService.getGreetingInfo();
    }

    @GetMapping("/pi")
    public PiResponseDTO returnPiValue() {
        return infoService.getPiValue();
    }
}