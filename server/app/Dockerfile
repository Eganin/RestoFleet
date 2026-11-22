# Образ бэкенда стенда (план 2.4): JRE 21 + дистрибутив installDist. Сборка из корня репозитория:
#   ./gradlew :server:app:installDist && docker compose -f deploy/docker-compose.yml build backend
# Контекст — server/app (в образ попадает только build/install, см. .dockerignore). Секретов в образе нет: они
# монтируются compose `secrets` в /run/secrets, CA стенда — в /certs/ca.crt (реш. 252).
FROM eclipse-temurin:21-jre

RUN groupadd --system --gid 10001 restofleet \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin restofleet

COPY --chown=root:root build/install/restofleet-backend/ /opt/restofleet/

USER 10001:10001
WORKDIR /opt/restofleet
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/opt/restofleet/bin/restofleet-backend"]
CMD ["serve"]
