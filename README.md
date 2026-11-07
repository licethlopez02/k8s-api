# Documentación Técnica: Arquitectura del Clúster K8s y Decisiones de Diseño

**Proyecto:** Clúster de Kubernetes sobre Red Virtual de Tailscale

## 1. Resumen Ejecutivo y Alcance del Proyecto

Este documento consolida y justifica las decisiones técnicas y de arquitectura tomadas para el diseño, despliegue y validación de un clúster de Kubernetes (1 Control Plane / Master y 5 Worker Nodes) capaz de tolerar el dinamismo en las direcciones IP locales (redes NAT/Bridge) mediante el uso de la red de **Tailscale**.

Asimismo, documenta la arquitectura interna del servicio backend desarrollado en **Spring Boot (Java 17)**, detallando la segregación de responsabilidades mediante el patrón de diseño por capas sin capa de persistencia relacional (stateless microservice con DTOs, Controllers y Services), la especificación de los manifiestos de Kubernetes (`Deployment` y `Service`) y la estrategia para la demostración de alta disponibilidad (borrado de Pods y apagado físico de nodos).

## 2. Decisiones de Arquitectura del Clúster

### 2.1. Red Overlay y Resiliencia ante IPs Dinámicas: Tailscale (VPN Mesh)

* **Problema planteado:** En entornos virtuales o redes domésticas/laborales bajo NAT o DHCP dinámico, los reinicios o reasignaciones de IP locales rompen la comunicación entre el API Server del Master y los servicios `kubelet` de los Workers.

* **Decisión tomada:** Implementar la red en malla (Mesh VPN) de **Tailscale** utilizando el espacio de direcciones IP `100.x.x.x`.

* **Justificación:** Tailscale abstrae la capa física mediante el protocolo WireGuard y realiza NAT Traversal automático. Esto otorga a cada nodo una IP estática y duradera dentro de la red privada virtual, garantizando que los nodos mantengan la identidad y conectividad sin importar los cambios de la interfaz de red física local (`eth0` / `wlan0`).

### 2.2. Prevención en la Configuración del Kubelet (`/etc/default/kubelet`)

* **Decisión tomada:** Configurar de manera explícita la variable `KUBELET_EXTRA_ARGS="--node-ip=<IP_TAILSCALE>"` en todos los nodos (Master y Workers) **antes** de ejecutar los comandos `kubeadm init` o `kubeadm join`.

* **Justificación:** Por defecto, `kubeadm` intenta registrar el nodo con la IP de la interfaz por defecto del sistema operativo (usualmente la IP local `192.168.x.x` o `10.x.x.x`). Al forzar la IP de Tailscale, el servicio `kubelet` reporta la interfaz de la VPN al API Server desde el primer instante de unión al clúster.

### 2.3. Selección e Integración del CNI: Flannel

* **Decisión tomada:** Inicializar el Master utilizando el argumento `--pod-network-cidr=10.244.0.0/16` e instalar la red overlay de Flannel (`kube-flannel.yml`).

* **Justificación:** Flannel es un Container Network Interface (CNI) ligero y altamente compatible que crea una red plana de nivel 3 entre los pods de distintos nodos. Su subred por defecto (`10.244.0.0/16`) encaja con la inicialización predeterminada y permite la resolución inmediata de los nodos de estado `NotReady` a `Ready`.

### 2.4. Estandarización del Runtime de Contenedores: `containerd` y Systemd Cgroups

* **Decisión tomada:** Desactivar la memoria Swap (`swapoff -a` y modificación de `/etc/fstab`) y configurar `containerd` con `SystemdCgroup = true`.

* **Justificación:** Kubernetes exige la ausencia de Swap para la gestión predecible de recursos de memoria. Además, al alinear el driver de Cgroups de `containerd` con `systemd` (el init del SO Ubuntu), se evitan bucles de reinicio en el agente `kubelet` y en la fase `wait-control-plane`.

## 3. Arquitectura y Código de la Aplicación (Spring Boot)

La aplicación backend está construida bajo un enfoque **stateless (sin estado)** utilizando **Spring Boot (Java 17)**. Se prescinde explícitamente de tecnologías de persistencia (como Spring Data JPA, Hibernate o bases de datos relacionales/NoSQL) para mantener una ejecución ligera y permitir escalar instancias de forma horizontal en Kubernetes sin coordinar estado entre Pods.

### 3.1. Estructura por capas lógicas (arquitectura stateless)

La aplicación separa responsabilidades en las siguientes capas:

1. **Capa de Controladores (`@RestController`):**
   * Expone los endpoints REST de la API vía HTTP en el puerto `8080`.
   * Recibe las solicitudes, asigna las rutas y delega la lógica en los servicios. Spring serializa los DTO de respuesta a JSON.

2. **Capa de Objetos de Transferencia de Datos (DTOs):**
   * Modela y estructura los datos JSON intercambiados con el cliente.
   * Mantiene un contrato de datos desacoplado de la lógica interna.

3. **Capa de Lógica de Negocio y Servicios (`@Service`):**
   * Implementa las operaciones de negocio y prepara los datos de respuesta en memoria.
   * No requiere conexiones externas a bases de datos.

### 3.2. Ausencia de persistencia y JPA

* **Decisión tomada:** Excluir dependencias como `spring-boot-starter-data-jpa`, controladores JDBC o motores de base de datos integrados (H2/PostgreSQL).
* **Justificación:** Los Pods son recursos reemplazables y escalables. Al no mantener estado persistente en la aplicación, una réplica puede atender solicitudes sin depender de datos locales de otra; esto favorece el escalado horizontal y evita dependencias externas para iniciar el servicio.

### 3.3. Manejo global de excepciones (v3.0.0)

En la versión **3.0.0** se incorporó el paquete `exception`, que centraliza el tratamiento de excepciones REST:

* `GlobalExceptionHandler` utiliza `@RestControllerAdvice` y maneja excepciones mediante `@ExceptionHandler`.
* Los errores se devuelven con el DTO `ErrorResponseDTO`, cuyos campos son `timestamp`, `status`, `error`, `message` y `path`.
* Las excepciones atendidas se responden con HTTP `500 Internal Server Error`. La respuesta estructurada no incluye la traza de pila de la JVM, por lo que esta no se expone al cliente.

| Campo | Descripción |
| --- | --- |
| `timestamp` | Fecha y hora en que se construye la respuesta de error. |
| `status` | Código HTTP numérico (`500`). |
| `error` | Nombre descriptivo del estado HTTP. |
| `message` | Mensaje asociado a la excepción. |
| `path` | Ruta solicitada que produjo el error. |

### 3.4. Evolución de la API y control de versiones en Git

| Versión / rama | Evolución funcional y técnica |
| --- | --- |
| **v1.0.0** (`main`) | Consulta de métricas del contenedor: hora, hostname, uso de RAM mediante cgroups y disco. Se estandarizó el nombre del artefacto Maven con `<finalName>k8s-api</finalName>`. |
| **v2.0.0** (`feature/v2-endpoint`) | Externalización de `app.info.version`, `app.info.integrantes` y `app.info.greeting-message` en `application.yml`. Se añadieron `GET /api/version` y `GET /api/greeting`; este último responde mediante `GreetingDTO`. |
| **v3.0.0** (`feature/v3-pi-endpoint`) | Se añadió `GET /api/pi`, sin parámetros de entrada, que entrega el valor de `Math.PI` en `PiResponseDTO`; se incorporó también el manejo global de excepciones descrito arriba. |

Los endpoints de las versiones anteriores se conservan en la API actual: `GET /api/info`, `GET /api/version` y `GET /api/greeting`; v3.0.0 agrega `GET /api/pi`. Los parámetros de versión y saludo se configuran actualmente en `application.yml`.


### 3.5. Configuraciones clave del runtime

* **Java 17:** versión LTS configurada para el proyecto.
* **Puerto predeterminado:** `8080`, expuesto por la aplicación y utilizado como `targetPort` en el Service de Kubernetes.

## 4. Decisiones de Diseño del Servicio en Kubernetes

### 4.1. Arquitectura de Despliegue: Contenedor vs. Ejecución Nativa

* **Decisión tomada:** Empaquetar la aplicación Spring Boot (Java 17, puerto 8080) mediante un `Dockerfile` (basado en `eclipse-temurin:17-jre-alpine`), publicar la imagen en un registro público (Docker Hub) y consumirla mediante un objeto `Deployment` de Kubernetes.

* **Justificación:** Kubernetes no ejecuta archivos de código o binarios sueltos (`.jar`). Delega la descarga y ejecución de contenedores al motor `containerd` de los Workers.

### 4.2. Objeto `Deployment` y escalabilidad horizontal

* **Decisión tomada:** Configurar el `Deployment` con `replicas: 5`; el estado deseado es mantener cinco Pods de la aplicación activos.

* **Justificación:** Kubernetes crea y mantiene las réplicas declaradas. El `Deployment` administra un `ReplicaSet`, que supervisa el estado deseado y crea reemplazos si Pods fallan. Las cinco réplicas favorecen la alta disponibilidad y la distribución de carga entre nodos; la cantidad realmente disponible puede variar durante fallos o actualizaciones.

### 4.3. Actualizaciones progresivas y reversión

La estrategia `RollingUpdate` permite desplegar una nueva imagen de forma progresiva, sustituyendo Pods sin detener simultáneamente todas las réplicas. `kubectl rollout status` permite observar el avance; `kubectl rollout history` consulta revisiones registradas y `kubectl rollout undo` revierte a la revisión anterior. Para seleccionar cualquiera de las versiones v1.0.0, v2.0.0 o v3.0.0, se deben desplegar su imagen etiquetada y su manifiesto correspondiente; el historial de rollout por sí solo no reemplaza el versionado de esos artefactos.

```bash
kubectl rollout status deployment/springboot-api
kubectl rollout history deployment/springboot-api
kubectl rollout undo deployment/springboot-api
```

## 5. Manifiestos de referencia

El siguiente manifiesto resume las decisiones de réplica y enrutamiento. El nombre de imagen es ilustrativo; en cada versión debe utilizarse la imagen etiquetada que corresponda.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: springboot-api
spec:
  replicas: 5
  strategy:
    type: RollingUpdate
  selector:
    matchLabels:
      app: springboot-api
  template:
    metadata:
      labels:
        app: springboot-api
    spec:
      containers:
        - name: api-container
          image: tu-usuario/k8s-api:3.0.0
          ports:
            - containerPort: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: springboot-service
spec:
  type: NodePort
  selector:
    app: springboot-api
  ports:
    - port: 8080
      targetPort: 8080
      nodePort: 30001
```

## 6. Estrategia de Pruebas y Validación de Resiliencia

| Prueba | Acción | Mecanismo de control | Resultado esperado |
| --- | --- | --- | --- |
| **Distribución de carga** | Desplegar el manifiesto y consultar `kubectl get pods -o wide`. | El scheduler asigna Pods a nodos disponibles; `replicas: 5` expresa el estado deseado. | Hasta cinco Pods disponibles, distribuidos según los recursos y restricciones del clúster. |
| **Recuperación de Pods** | `kubectl delete pod <nombre>`. | El `ReplicaSet` detecta la réplica faltante y crea un reemplazo. | El Deployment vuelve a buscar cinco Pods disponibles. |
| **Actualización / reversión** | Actualizar la imagen y utilizar `kubectl rollout`; para revertir, `kubectl rollout undo`. | El Deployment administra el `RollingUpdate` y las revisiones del rollout. | Cambio progresivo o retorno a la revisión anterior disponible. |
| **Tolerancia a fallos en nodos** | Apagar un Worker de prueba. | El controlador marca el nodo `NotReady`; Kubernetes programa Pods en nodos disponibles según sus recursos y restricciones. | Recuperación de las réplicas posibles y acceso mediante el `NodePort` `30001`, sujeto a la capacidad restante del clúster. |

## 7. Firma y Trazabilidad de Generación

Toda la arquitectura del código fuente (Spring Boot en capas sin persistencia, DTOs, Controllers, Services), la configuración del entorno, la resolución de fallos de red/cgroups, la parametrización de archivos del sistema (`/etc/default/kubelet`) y la redacción de manifiestos YAML han sido estructuradas colaborativamente mediante asistencia de Inteligencia Artificial para responder a los estándares oficiales de desarrollo en Java y administración de clústeres Kubernetes (`kubeadm`).
