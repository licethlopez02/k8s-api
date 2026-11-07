package co.edu.uptc.k8s_api.service;

import co.edu.uptc.k8s_api.dto.ContainerInfoDTO;
import co.edu.uptc.k8s_api.dto.AppVersionDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class InfoService {

    private final List<String> integrantes;
    private final String version;

    public InfoService(
            @Value("${app.info.integrantes[0]}") String primerIntegrante,
            @Value("${app.info.integrantes[1]}") String segundoIntegrante,
            @Value("${app.info.integrantes[2]}") String tercerIntegrante,
            @Value("${app.info.version:1.0.0}") String version) {
        this.integrantes = List.of(primerIntegrante, segundoIntegrante, tercerIntegrante);
        this.version = version;
    }

    public ContainerInfoDTO getContainerInfo() {
        
        // 1. Hora del contenedor
        // Decisión: Se usa LocalDateTime.now(ZoneId.systemDefault()) para capturar la 
        // hora local configurada en el sistema operativo del pod en el momento de la petición.
        String horaContenedor = LocalDateTime.now(ZoneId.systemDefault()).toString();

        // 3. Nombre o ID del contenedor
        // Decisión: En Kubernetes, el ID/Nombre del Pod se inyecta por defecto en la 
        // variable de entorno "HOSTNAME". Se prioriza su lectura. Si falla, se realiza 
        // un fallback seguro utilizando InetAddress para evitar caídas en el servicio.
        String hostName = System.getenv("HOSTNAME");
        if (hostName == null || hostName.isEmpty()) {
            try {
                hostName = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                hostName = "Id_No_Identificado";
            }
        }

        // 4. Cantidad de almacenamiento del contenedor
        // Decisión: Se consulta la raíz "/" que representa el sistema de archivos del 
        // contenedor montado por el runtime (containerd/docker). Se hace la conversión 
        // de bytes a Megabytes (MB) dividiendo entre 1024^2.
        File rootDirectory = new File("/");
        long totalStorageMB = rootDirectory.getTotalSpace() / (1024 * 1024);
        String almacenamientoTotal = totalStorageMB + " MB";

        // 5. Cantidad de RAM del contenedor
        // Decisión: Se invoca Runtime.getRuntime().maxMemory(). Dado que Java soporta 
        // nativamente el reconocimiento de cgroups, este valor reflejará el límite 
        // de memoria asignado en el deployment de Kubernetes (limits/requests).
        long maxRamMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        String ramAsignada = maxRamMB + " MB";

        // Decisión: Se retorna la instancia del DTO ensamblando toda la información.
        return new ContainerInfoDTO(
                horaContenedor,
                integrantes,
                hostName,
                almacenamientoTotal,
                ramAsignada
        );
    }

    public AppVersionDTO getAppVersion() {
        return new AppVersionDTO(version);
    }
}