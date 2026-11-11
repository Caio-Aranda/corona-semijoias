# Imagem da API para produção (Fase 9 mínima, ADR-0009). Contexto de build: a pasta "backend/".
# Versões fixas (nada de "latest"): a mesma do ADR-0001 (Java 21.0.13).

FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Os testes rodam no CI (precisam do Docker); aqui só se empacota.
RUN ./mvnw -B -q -DskipTests package && cp target/corona-api-*.jar /build/corona-api.jar

FROM eclipse-temurin:21.0.13_9-jre-noble
# Usuário sem privilégio; o mesmo número é usado para as pastas do servidor.
RUN groupadd --system --gid 10001 corona && useradd --system --uid 10001 --gid corona --no-create-home corona \
    && mkdir -p /app /dados/arquivos && chown -R corona:corona /dados
WORKDIR /app
COPY --from=build /build/corona-api.jar /app/corona-api.jar
USER corona
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
# O comando de redefinir a senha da dona usa o mesmo jar: "java -jar /app/corona-api.jar redefinir-senha-admin".
ENTRYPOINT ["java", "-jar", "/app/corona-api.jar"]
