# Shared build for every Java service. The service to build is passed as SERVICE.
#
# Two properties matter here: the dependency layer is cached separately from the source, so a code
# change does not re-download the world; and the runtime image carries a JRE and a non-root user,
# not a build toolchain.

FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /build

# Poms first: this layer only changes when a dependency does.
COPY pom.xml ./
COPY platform/hms-common/pom.xml platform/hms-common/
COPY services/gateway/pom.xml services/gateway/
COPY services/identity-service/pom.xml services/identity-service/
COPY services/patient-service/pom.xml services/patient-service/
COPY services/scheduling-service/pom.xml services/scheduling-service/
COPY services/laboratory-service/pom.xml services/laboratory-service/
COPY services/notification-service/pom.xml services/notification-service/
COPY services/admissions-service/pom.xml services/admissions-service/
COPY services/pharmacy-service/pom.xml services/pharmacy-service/
COPY services/billing-service/pom.xml services/billing-service/
COPY services/interop-service/pom.xml services/interop-service/
COPY services/imaging-service/pom.xml services/imaging-service/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY platform platform
COPY services services
RUN mvn -B -q -pl "services/${SERVICE}" -am package -DskipTests \
    && cp "services/${SERVICE}/target/${SERVICE}"-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-noble
ARG SERVICE
LABEL org.opencontainers.image.title="medsync-${SERVICE}"
LABEL org.opencontainers.image.source="https://github.com/smkazi/medsync"

# Runs unprivileged: a container that never needs root should never have it.
RUN useradd --system --uid 10001 --create-home medsync
WORKDIR /app
COPY --from=build --chown=medsync:medsync /build/app.jar app.jar
USER 10001

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080} && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
ENTRYPOINT ["java", "-jar", "app.jar"]
