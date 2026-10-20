# Imagen de la API y del worker (la misma: EINVOICE_WORKER_ENABLED decide qué hace cada instancia).
#   docker build --platform linux/amd64 -t einvoice-api .
# Las pruebas no corren aquí: las ejecuta el CI antes de construir la imagen. Despliegue en Google
# Cloud Run: docs/13-deployment.md.

# El jar no depende de la arquitectura: se compila en la de la máquina que construye, aunque la
# imagen sea para otra (linux/amd64 desde un Mac con ARM, por ejemplo), sin emular Maven.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# En un checkout de Windows el script puede llegar con fin de línea CRLF.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests -Djacoco.skip=true package \
 && cp target/einvoice-*.jar app.jar
# El jar se descomprime por capas: las dependencias cambian poco y quedan en capas propias que el
# registro reutiliza, y la JVM arranca antes que con el jar anidado.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination /extracted

FROM eclipse-temurin:21-jre
# Usuario sin privilegios y sin directorio propio: la aplicación no escribe en disco (solo /tmp, que
# en Cloud Run vive en memoria y se pierde con la instancia).
RUN groupadd --system --gid 10001 einvoice && useradd --system --uid 10001 --gid 10001 --no-create-home einvoice
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
# Opciones de la JVM en una variable, para cambiarlas al desplegar sin reconstruir (quien la redefina
# debe repetir estas): el heap se mide contra la memoria del contenedor, y una JVM sin memoria
# termina para que la plataforma la reinicie en vez de quedar a medias. Los registros salen en JSON.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash
USER 10001:10001
# Escucha en PORT (8080 si no se define).
EXPOSE 8080
# La JVM es el proceso 1 y recibe SIGTERM directamente: cierra de forma ordenada (application.yml).
# infra/docker-compose.stack.yml repite esta orden en su entrypoint: cambiar las dos a la vez.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
