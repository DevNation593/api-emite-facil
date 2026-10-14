# Imagen de la API y del worker (la misma: EINVOICE_WORKER_ENABLED decide qué hace cada instancia).
#   docker build -t einvoice-api backend
# Las pruebas no corren aquí: las ejecuta el CI antes de construir la imagen.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# En un checkout de Windows el script puede llegar con fin de línea CRLF.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests -Djacoco.skip=true package \
 && cp target/einvoice-*.jar /app.jar

FROM eclipse-temurin:21-jre
# Usuario sin privilegios y sin directorio propio: la aplicación no escribe en disco.
RUN groupadd --system --gid 10001 einvoice && useradd --system --uid 10001 --gid 10001 --no-create-home einvoice
WORKDIR /app
COPY --from=build /app.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
