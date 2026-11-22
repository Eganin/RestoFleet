# Образ симулятора (КМ4, план 4.1): JRE 21 + дистрибутив installDist + iproute2 (`tc netem` для потерь пакетов, план 4.3).
# Сборка из корня репозитория:
#   ./gradlew :server:simulator:installDist && docker compose -f deploy/docker-compose.yml --profile experiment build simulator
# Секретов в образе нет: пароль admin — compose-секрет, CA стенда и кэш учёток монтируются (deploy/docker-compose.yml).
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 restofleet \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin restofleet

COPY --chown=root:root build/install/restofleet-simulator/ /opt/simulator/
COPY --chown=root:root --chmod=755 docker-entrypoint.sh /usr/local/bin/simulator-entrypoint

WORKDIR /opt/simulator
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Точка входа стартует от root только ради `tc` (NET_ADMIN) и сразу переходит на uid 10001 (setpriv)
ENTRYPOINT ["/usr/local/bin/simulator-entrypoint"]
CMD ["experiment", "--scenario", "/scenarios/mqtt-100.yaml"]
