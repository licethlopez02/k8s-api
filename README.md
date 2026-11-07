[Documentacion k8s-api.md](https://github.com/user-attachments/files/34300166/Documentacion.k8s-api.md)
# Documentación Técnica: Arquitectura del Clúster K8s y Decisiones de Diseño

**Proyecto:** Clúster de Kubernetes Resiliente sobre Red Virtual de Tailscale

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

La aplicación backend fue construida bajo un enfoque **stateless (sin estado)** utilizando **Spring Boot (Java 17)**. Se prescindió explícitamente de tecnologías de persistencia (como Spring Data JPA, Hibernate o bases de datos relacionales/NoSQL) para garantizar un inicio de contenedor ultra-rápido y una ejecución ligera orientada a microservicios efímeros en Kubernetes.

### 3.1. Estructura por Capas Lógicas (Stateless Architecture)

Se aplicó una segregación de responsabilidades limpia dividida en tres componentes principales:

1. **Capa de Controladores (`@RestController`):**
   * Encargada de exponer los endpoints RESTful de la API consumibles vía HTTP (puerto `8080`).
   * Recibe las peticiones HTTP, mapea las rutas de acceso y retorna directamente los objetos DTO, delegando en Spring Boot la serialización automática a JSON y la respuesta implícita con código de estado HTTP 200 OK.

2. **Capa de Objetos de Transferencia de Datos (DTOs):**
   * Clases Java dedicadas exclusivamente a modelar y estructurar el payload JSON que entra y sale de la aplicación.
   * Evitan la exposición directa de lógica interna y aseguran un contrato de datos estricto y desacoplado entre el cliente y el backend.

3. **Capa de Lógica de Negocio y Servicios (`@Service`):**
   * Contiene las operaciones y la manipulación de datos en memoria (mediante colecciones nativas de Java como `List`).
   * Actúa como el motor del servicio sin requerir conexiones externas a bases de datos.

### 3.2. Justificación de la Ausencia de Persistencia y JPA

* **Decisión tomada:** Excluir dependencias como `spring-boot-starter-data-jpa`, controladores JDBC o motores de base de datos integrados (H2/PostgreSQL).
* **Justificación:** En un entorno de Kubernetes enfocado en pruebas de resiliencia y alta disponibilidad, los Pods son considerados recursos desechables y horizontales. Eliminar el estado persistente evita cuellos de botella en la inicialización, reduce el tamaño de la imagen Docker y permite validar la auto-recuperación de Pods de forma pura sin dependencias externas.

### 3.3. Configuraciones Clave del Runtime

* **LTS Java 17:** Elegido por sus optimizaciones de memoria en entornos contenedorizados (Cgroups v2 support).
* **Puerto Predeterminado:** `8080` (configurado en `application.properties` y referenciado en los manifiestos de Kubernetes).

## 4. Decisiones de Diseño del Servicio en Kubernetes

### 4.1. Arquitectura de Despliegue: Contenedor vs. Ejecución Nativa

* **Decisión tomada:** Empaquetar la aplicación Spring Boot (Java 17, puerto 8080) mediante un `Dockerfile` (basado en `eclipse-temurin:17-jre-alpine`), publicar la imagen en un registro público (Docker Hub) y consumirla mediante un objeto `Deployment` de Kubernetes.

* **Justificación:** Kubernetes no ejecuta archivos de código o binarios sueltos (`.jar`). Delega la descarga y ejecución de contenedores al motor `containerd` de los Workers.

### 4.2. Objeto `Deployment` y Réplicas Generosas

* **Decisión tomada:** Configurar un `Deployment` con `replicas: 12`.

* **Justificación:** Solicitar un número de réplicas superior al número de nodos activos fuerza al componente `kube-scheduler` a distribuir equitativamente los Pods entre todos los Workers disponibles. El objeto `Deployment` gestiona internamente el `ReplicaSet`, garantizando el monitoreo continuo del estado deseado frente al estado real.

### 4.3. Objeto `Service` Tipo `NodePort`

* **Decisión tomada:** Exponer la aplicación mediante un `Service` con `type: NodePort` asociando el puerto expuesto de la aplicación (`targetPort: 8080`) a un puerto estático en los nodos (`nodePort: 30001`).

* **Justificación:** El `NodePort` abre la puerta de enlace `30001` en **todos** los nodos del clúster (gracias a `kube-proxy`). Esto permite el acceso desde el exterior (vía IP de Tailscale del Master o de cualquier Worker) sin requerir un balanceador de carga externo.

## 5. Estructura de Manifiestos Aplicados (`api-spring-deployment.yaml`)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: springboot-api
spec:
  replicas: 12
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
        image: tu-usuario/api-springboot:v1
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

| Prueba | Acción Ejecutada | Comportamiento del Clúster / Mecanismo de Control | Resultado Esperado | 
| ----- | ----- | ----- | ----- | 
| **Distribución de Carga** | Despliegue del YAML en Master | `kube-scheduler` analiza la carga y distribuye las 12 réplicas entre los Workers activos. | Visualización de los Pods repartidos en distintos nodos (`kubectl get pods -o wide`). | 
| **Recuperación de Pods** | `kubectl delete pod <nombre>` | El **ReplicaSet** detecta la reducción del conteo de pods a 11 y recrea 1 pod inmediatamente para cumplir las 12 réplicas. | Alta disponibilidad garantizada; reemplazo en milisegundos. | 
| **Tolerancia a Fallos en Nodos** | `sudo poweroff` en un Worker | `Node Controller` marca el nodo como `NotReady`. El `ReplicaSet` reagrupa los Pods perdidos y los recrea en los Workers sobrevivientes. | Cero interrupción del servicio consumido desde el puerto `30001`. | 

## 7. Firma y Trazabilidad de Generación

Toda la arquitectura del código fuente (Spring Boot en capas sin persistencia, DTOs, Controllers, Services), la configuración del entorno, la resolución de fallos de red/cgroups, la parametrización de archivos del sistema (`/etc/default/kubelet`) y la redacción de manifiestos YAML han sido estructuradas colaborativamente mediante asistencia de Inteligencia Artificial para responder a los estándares oficiales de desarrollo en Java y administración de clústeres Kubernetes (`kubeadm`).
