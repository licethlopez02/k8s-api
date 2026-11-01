package co.edu.uptc.k8s_api.controller;

import co.edu.uptc.k8s_api.service.InfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class InfoController {

    private final InfoService infoService;

    // Decisión: Se utiliza inyección de dependencias por constructor. 
    // Es la convención recomendada en Spring Boot para garantizar que el controlador 
    // no se instancie si el servicio no está disponible (fail-fast).
    public InfoController(InfoService infoService) {
        this.infoService = infoService;
    }

    @GetMapping("/info")
    public Map<String, Object> returnInfo() {
        return infoService.getContainerInfo();
    }
}