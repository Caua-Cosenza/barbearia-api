# ===========================================================================
# Stage 1 - build
# ===========================================================================
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Copia primeiro so o wrapper e o pom: enquanto as dependencias nao mudam,
# esta camada fica em cache e o build nao baixa o repositorio inteiro de novo.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B package -DskipTests

# ===========================================================================
# Stage 2 - runtime
# JRE, nao JDK: a imagem final nao precisa de compilador.
# ===========================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Usuario sem privilegios. Rodar como root em container significa que uma
# falha na aplicacao vira root dentro do container.
RUN addgroup -S -g 1001 barbearia \
 && adduser -S -u 1001 -G barbearia barbearia

WORKDIR /app

COPY --from=build --chown=barbearia:barbearia /build/target/*.jar app.jar

USER barbearia

EXPOSE 8080

# MaxRAMPercentage: sem isso a JVM dimensiona a heap pela memoria da maquina
# hospedeira, ignora o limite do container e toma OOM kill em producao.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+UseContainerSupport", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
