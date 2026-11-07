# Decisiones de Arquitectura — `k8s_api`

**Proyecto:** API de inspección de contenedor en Spring Boot y Java 17  
**Versión funcional documentada:** 3.0.0  
**Plataforma de ejecución prevista:** Kubernetes  
**Propósito:** registrar las decisiones de diseño, sus responsabilidades y sus límites operativos.

> Este documento describe el código fuente y la documentación del proyecto disponibles al momento de su redacción. Los detalles de despliegue indicados como configuración prevista o documentada no implican que exista un manifiesto de Kubernetes versionado en este repositorio.

## 1. Visión general de la arquitectura

### 1.1. Diseño stateless

La aplicación no mantiene estado de negocio entre solicitudes ni necesita una base de datos. Cada petición se procesa a partir de la configuración de la aplicación y de información disponible en el entorno de ejecución de la JVM o del contenedor. Los objetos que representan la versión y los integrantes se cargan al iniciar la aplicación; no son información mutable por petición.

Esta característica permite ejecutar varias réplicas equivalentes: cualquier Pod puede atender una petición sin afinidad con una instancia previa. Kubernetes puede reemplazar Pods o ajustar la cantidad de réplicas sin migrar estado local de negocio. La persistencia de logs o archivos no forma parte del contrato de esta API.

Stateless no significa que todas las mediciones sean idénticas entre Pods: el nombre del host y los recursos visibles pueden variar según el contenedor y el nodo donde se ejecute.

### 1.2. Arquitectura por capas

La estructura lógica mantiene responsabilidades diferenciadas:

```text
Cliente HTTP
    |
    v
InfoController (@RestController)
    |  mapea rutas y delega
    v
InfoService (@Service)
    |  reúne configuración y datos del runtime
    v
DTOs (contrato JSON de salida)

Excepciones de controladores --> GlobalExceptionHandler (@RestControllerAdvice)
```

| Componente | Responsabilidad |
|---|---|
| `InfoController` | Expone las rutas HTTP bajo `/api`, delega la operación al servicio y retorna DTOs. Spring MVC serializa los objetos a JSON. |
| `InfoService` | Implementa la lógica de consulta: combina configuración inyectada con la hora, el host y los valores del runtime. |
| DTOs | Definen las estructuras de respuesta y aíslan el formato HTTP de la implementación del servicio. |
| `GlobalExceptionHandler` | Convierte excepciones generales de solicitudes atendidas por Spring MVC en una respuesta JSON estandarizada con estado 500. |

El controlador no construye manualmente respuestas JSON ni crea `ResponseEntity` en los casos exitosos. La respuesta normal de cada endpoint es el DTO retornado por el método del controlador, serializado por Spring con HTTP 200 OK.

### 1.3. Sin persistencia

No se incluyen Spring Data JPA, Hibernate, JDBC ni un motor de base de datos. Esta es una decisión intencional: el servicio expone configuración y métricas de ejecución, y no tiene operaciones que requieran almacenamiento transaccional. La ausencia de una capa de persistencia también reduce componentes de despliegue y dependencias operativas.

La lista de integrantes, el saludo y la versión viven en la configuración de la aplicación (`application.yml`), no en una tabla de base de datos.

### 1.4. IoC y ciclo de vida de componentes

Spring administra la creación e inyección de dependencias del controlador y del servicio. `InfoController` recibe `InfoService` por constructor, y `InfoService` recibe sus valores de configuración mediante `@Value`.

Los componentes anotados con `@Service` y `@RestController` tienen alcance singleton por defecto en el contexto de Spring: una instancia por contexto de aplicación. Esto no es un singleton global compartido entre Pods. Cada réplica de Kubernetes ejecuta su propio proceso JVM y su propio contexto Spring. Como el servicio no guarda estado de petición mutable, este alcance resulta apropiado para las operaciones implementadas.

## 2. Inspección del entorno y métricas de bajo nivel

El endpoint `/api/info` ensambla la respuesta en `InfoService`. Las métricas son observaciones del proceso o del filesystem accesible desde el contenedor; no son un inventario exhaustivo de recursos del clúster.

| Dato | Fuente y comportamiento | Consideraciones |
|---|---|---|
| Hora del contenedor | `LocalDateTime.now(ZoneId.systemDefault())`, convertida a `String`. | Representa la hora local según la zona configurada en la JVM/entorno. `LocalDateTime` no incluye offset ni zona en el valor serializado. |
| Identificador | Variable de entorno `HOSTNAME`; si no está definida o está vacía, se intenta `InetAddress.getLocalHost().getHostName()`. Si esa búsqueda falla con `UnknownHostException`, se usa `Id_No_Identificado`. | Kubernetes suele proporcionar `HOSTNAME` dentro del contenedor. El fallback depende de la resolución de nombre del sistema. |
| Almacenamiento | `new File("/").getTotalSpace()`, convertido de bytes a MB mediante división por `1024 * 1024`. | Es el tamaño total que reporta el filesystem montado en `/`; no necesariamente equivale al espacio libre ni al límite de almacenamiento efímero asignado al Pod. |
| Memoria | `Runtime.getRuntime().maxMemory()`, convertido a MB. | Reporta el máximo heap de la JVM, no la memoria total consumida por el proceso o el límite completo del contenedor. La JVM 17 reconoce límites de contenedores Linux en condiciones compatibles, pero el resultado también depende de las opciones de memoria y del runtime. |

El valor de memoria no debe interpretarse como una lectura de `resources.limits.memory` hecha directamente a través de la API de Kubernetes. Es el máximo de heap que la JVM tiene disponible, que puede estar basado en la detección de límites cgroups o en opciones de JVM. Del mismo modo, `getTotalSpace()` informa sobre el filesystem y no consulta directamente las cuotas del volumen efímero de Kubernetes.

## 3. Evolución del código y control de versiones

La evolución funcional se expresa mediante la propiedad `app.info.version` y la incorporación incremental de endpoints. La versión funcional declarada actualmente en `application.yml` es `3.0.0`; la versión Maven del artefacto (`0.0.1` en `pom.xml`) es un identificador de empaquetado distinto y no se actualiza automáticamente al cambiar `app.info.version`.

### 3.1. v1.0.0 — externalización de configuración

La configuración de la aplicación se mantiene en `src/main/resources/application.yml`. La versión y los integrantes se inyectan en `InfoService` con `@Value`, evitando mantener esos valores codificados directamente en la operación de negocio.

Se incorporó `GET /api/version`, que retorna `AppVersionDTO` con la versión leída de configuración. `InfoService` recibe los tres integrantes mediante placeholders indexados (`app.info.integrantes[0]`, `[1]`, `[2]`) y construye una lista inmutable con ellos. Por tanto, aunque los valores son configurables, la implementación actual presupone tres elementos.

La externalización aplica el principio de separar configuración de código promovido por Twelve-Factor App. La configuración actual está empaquetada como recurso de la aplicación; para entornos con valores distintos se puede sobrescribir mediante fuentes de configuración compatibles con Spring, sin modificar la lógica del servicio.

### 3.2. v2.0.0 — mensajes y respuesta de saludo

Se incorporó `app.info.greeting-message` a la configuración y `GET /api/greeting`. `InfoService.getGreetingInfo()` concatena el mensaje configurado con la versión y retorna un `GreetingDTO` que contiene:

- `mensaje`, por ejemplo `Hola, esta es la version: 2.0.0`;
- `version`;
- `integrantes`.

La versión configurada evolucionó a `2.0.0`. El endpoint `/api/info` se conserva.

### 3.3. v3.0.0 — PI y tolerancia a fallos

La versión configurada es `3.0.0`. Se añadió `GET /api/pi`, sin parámetros de entrada. `InfoService.getPiValue()` crea `PiResponseDTO` con:

- `valorPi`, obtenido de `Math.PI`;
- `timestamp`, generado con `LocalDateTime.now().toString()`.

El DTO actual no incluye una descripción. El timestamp, igual que el de `/api/info`, es una fecha/hora local sin zona u offset explícitos.

También se añadió `GlobalExceptionHandler`, anotado con `@RestControllerAdvice`. El método que maneja `Exception.class` crea un `ErrorResponseDTO` con hora, estado, nombre del error, mensaje de la excepción y URI de la solicitud, y responde con HTTP 500 Internal Server Error. El manejador cubre excepciones de solicitudes procesadas por el contexto Spring MVC; no sustituye los mecanismos de supervisión de procesos ni intercepta fallos que ocurran fuera de ese contexto.

El mensaje incluido procede de `Exception.getMessage()` y puede ser nulo o contener detalles internos. En una aplicación expuesta a usuarios externos convendría evaluar si se debe devolver un mensaje genérico y conservar los detalles únicamente en logs protegidos.

### 3.4. Endpoints disponibles

| Método | Ruta | Respuesta |
|---|---|---|
| `GET` | `/api/info` | `ContainerInfoDTO`: hora, integrantes, identificador, almacenamiento y heap máximo reportado como RAM asignada. |
| `GET` | `/api/version` | `AppVersionDTO`: versión configurada. |
| `GET` | `/api/greeting` | `GreetingDTO`: saludo, versión e integrantes. |
| `GET` | `/api/pi` | `PiResponseDTO`: valor de PI y timestamp. |

En las respuestas exitosas, el controlador retorna directamente el DTO y Spring MVC lo serializa como JSON con estado HTTP 200 por defecto.

### 3.5. Estrategia de ramas y trazabilidad

El flujo de trabajo solicitado contempla:

```text
main
  └── feature/v2-endpoint
        └── feature/v3-pi-endpoint
```

`main` representa la línea integrada; las ramas `feature/*` aíslan cambios funcionales para revisión e integración. En el historial Git inspeccionado existen las ramas `main`, `feature/v2-endpoint` y `feature/v3-pi-endpoint`. En el momento de esta documentación, `feature/v2-endpoint` y `feature/v3-pi-endpoint` apuntan al mismo commit, mientras que los cambios de v3 están presentes en el árbol de trabajo y no forman parte de ese commit. Por ello, la convención de nombres describe el flujo deseado, pero los nombres de rama por sí solos no demuestran que cada versión esté integrada o publicada.

## 4. Decisiones de red y despliegue en Kubernetes

Los detalles de clúster y manifiestos de esta sección se basan en la documentación técnica `README.md`. No se encontraron manifiestos YAML de Kubernetes versionados en el árbol de archivos de la aplicación; antes de un despliegue deben confirmarse contra la configuración real del clúster.

### 4.1. Deployment, ReplicaSet y recuperación

Un `Deployment` declara la versión deseada de la aplicación y administra sus `ReplicaSet`. Según la documentación del proyecto, el despliegue de referencia solicita 12 réplicas. El Deployment coordina la historia de revisiones, las actualizaciones y la reversión de la plantilla de Pods. Una estrategia `RollingUpdate` reemplaza instancias gradualmente para reducir interrupciones; una reversión puede solicitarse con `kubectl rollout undo deployment/<nombre>`.

El `ReplicaSet` mantiene el número deseado de Pods: si un Pod desaparece, crea otro para reconciliar el estado observado con el estado declarado. No es, por sí solo, una comprobación completa de salud de la aplicación. Para retirar una instancia que sigue ejecutándose pero no está lista, deben configurarse y verificarse probes de readiness y, según el caso, liveness/startup. No hay un manifiesto de despliegue disponible en este repositorio que permita confirmar esas probes.

La recuperación de un nodo perdido involucra también la detección del estado del nodo y el scheduler/controladores de Kubernetes; no depende exclusivamente del ReplicaSet. El tiempo de detección y reprogramación no es necesariamente de milisegundos y depende de los timeouts y la capacidad disponible del clúster.

### 4.2. Service tipo NodePort

La topología documentada expone el servicio con `NodePort`, usando `nodePort: 30001` y encaminando al puerto de la aplicación `8080`:

```text
Cliente
  |
  | <IP de un nodo>:30001
  v
Service (NodePort 30001 / port 8080)
  |
  | targetPort 8080
  v
Pods de la API (Spring Boot)
```

El puerto debe estar permitido por las reglas de red/firewall del entorno para que el cliente pueda acceder. Un Service Kubernetes selecciona Pods mediante labels y distribuye el tráfico entre endpoints disponibles. `kube-proxy` programa las reglas de encaminamiento del nodo; el mecanismo concreto depende de la configuración y versión del clúster. La documentación del proyecto menciona `iptables`, pero Kubernetes puede usar otros modos soportados. No se requiere un balanceador externo ni Nginx para este escenario básico de acceso desde un nodo, aunque un Ingress o balanceador puede ser preferible para TLS, nombres DNS, políticas y exposición pública controlada.

### 4.3. Imagen y artefacto de aplicación

El `Dockerfile` disponible usa `eclipse-temurin:17-jdk-alpine`, copia un JAR al contenedor y ejecuta `java -jar`. `pom.xml` define `<java.version>17</java.version>` y `<finalName>k8s-service</finalName>`.

**Pendiente de alineación antes de construir la imagen:** el `Dockerfile` todavía copia `target/k8s-api-0.0.1.jar`, mientras que el `finalName` configurado produce `target/k8s-service.jar`. Deben actualizarse juntos el nombre de entrada del Dockerfile y el proceso de build para evitar un fallo de `COPY`. Esta nota documenta la discrepancia observada; no cambia esos archivos.

### 4.4. Consideraciones operativas

- Los Pods son reemplazables; no guardar estado de negocio únicamente en su filesystem local.
- Mantener coherentes los labels del Deployment y el selector del Service.
- Confirmar que el `targetPort` coincide con el puerto donde Spring Boot escucha (`8080` según el diseño documentado).
- Configurar probes y límites/requests de CPU y memoria en los manifiestos reales; no se pueden verificar aquí porque no están presentes.
- Revisar la exposición de `NodePort`, los firewalls y el acceso desde redes externas antes de publicar el servicio.
- Confirmar el nombre del JAR de Maven frente a lo que copia el Dockerfile antes de empaquetar la imagen.

## 5. Configuración de aplicación actual

La configuración funcional documentada en `application.yml` es:

```yaml
spring:
  application:
    name: k8s-api
app:
  info:
    version: "3.0.0"
    integrantes:
      - "Jhon Cardenas"
      - "Liceth Lopez"
      - "Diego Soto"
    greeting-message: "Hola, esta es la version:"
```

Los valores de `app.info` alimentan las respuestas `/api/version`, `/api/greeting` y `/api/info`. El endpoint `/api/pi` no depende de esta configuración.

## 6. Resumen de decisiones

| Decisión | Motivo |
|---|---|
| Arquitectura por capas Controller–Service–DTO | Separar transporte HTTP, lógica y contrato de respuesta. |
| Sin persistencia | Las operaciones actuales no necesitan datos duraderos ni transacciones. |
| Configuración en YAML | Separar valores de despliegue y datos de aplicación del código de negocio. |
| Componentes Spring singleton y lógica sin estado mutable | Reutilización segura por contexto y procesamiento independiente de cada solicitud. |
| Métricas locales del proceso/contenedor | Proveer contexto de ejecución útil para una API de demostración en Kubernetes, aclarando que son aproximaciones y no métricas completas del clúster. |
| DTOs tipados y serialización Spring | Mantener una respuesta JSON explícita, coherente y desacoplada de las clases internas. |
| `@RestControllerAdvice` | Centralizar el formato de errores de las solicitudes manejadas por Spring MVC. |
| Deployment y NodePort en la topología documentada | Gestionar réplicas/actualizaciones y permitir acceso externo sencillo al clúster sin balanceador adicional. |
