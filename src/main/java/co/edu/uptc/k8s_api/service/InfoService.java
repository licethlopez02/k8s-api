package co.edu.uptc.k8s_api.service;

import org.springframework.stereotype.Service;
import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InfoService {

    public Map<String, Object> getContainerInfo() {
        Map<String, Object> info = new HashMap<>();

        // 1. Hora del contenedor
        // Decisión: Se utiliza LocalDateTime.now() con la zona horaria del sistema (ZoneId.systemDefault()) 
        // para reflejar la hora exacta configurada internamente en el SO del contenedor en tiempo real.
        info.put("hora_contenedor", LocalDateTime.now(ZoneId.systemDefault()).toString());

        // 2. Nombres de los integrantes del grupo
        // Decisión: Se inyecta mediante un List.of() inmutable. Al ser información estática 
        // que no mutará en el ciclo de vida del microservicio, no requiere variables de entorno ni BD.
        info.put("integrantes", List.of("Jhon Cardenas", "Liceth Lopez", "Diego Soto"));

        // 3. Nombre o ID del contenedor
        // Decisión: En entornos Kubernetes, el nombre del Pod se inyecta por defecto en la 
        // variable de entorno "HOSTNAME". Se consulta primero esta variable por eficiencia. 
        // Si no existe, se hace un fallback a la red local (InetAddress) para garantizar una respuesta.
        String hostName = System.getenv("HOSTNAME");
        if (hostName == null || hostName.isEmpty()) {
            try {
                hostName = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                hostName = "Id_No_Identificado";
            }
        }
        info.put("id_contenedor", hostName);

        // 4. Cantidad de almacenamiento del contenedor
        // Decisión: Se lee el directorio raíz ("/") que corresponde a la capa (layer) del contenedor 
        // montada por el Container Runtime (containerd/docker). Se usa getTotalSpace() y se divide 
        // entre (1024 * 1024) para presentar el dato en Megabytes (MB) de forma legible.
        File rootDirectory = new File("/");
        long totalStorageMB = rootDirectory.getTotalSpace() / (1024 * 1024);
        info.put("almacenamiento_total_MB", totalStorageMB + " MB");

        // 5. Cantidad de RAM del contenedor
        // Decisión: Se utiliza Runtime.getRuntime().maxMemory(). A partir de Java 10+, la JVM 
        // reconoce automáticamente los límites de cgroups (UseContainerSupport). Por lo tanto, 
        // esto devolverá el límite de RAM configurado en el Pod de K8s, y no la RAM del nodo físico.
        long maxRamMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        info.put("ram_asignada_MB", maxRamMB + " MB");

        return info;
    }
}