# The real, production-shaped image of the service.
#
# Built from the layered boot jar rather than `bootBuildImage`: the layers are cached by Docker, so
# a change to the application code only rebuilds the smallest layer. The black-box suite runs
# against this image and nothing else, which is the point - it never sees the application's classes.

FROM eclipse-temurin:21-jre AS extract

WORKDIR /builder
# The build pins this file name (see the bootJar configuration in build.gradle.kts) so the copy is
# unambiguous - build/libs otherwise also holds the `-plain` jar.
COPY build/libs/application.jar application.jar
# `jarmode=tools extract --layers` unpacks the jar into dependencies / spring-boot-loader /
# snapshot-dependencies / application, in ascending order of how often they change.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted


FROM eclipse-temurin:21-jre

# curl is not in the base image and both this healthcheck and the compose healthcheck need it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /application
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./

# Configured only through environment variables, exactly as production would be. See
# compose.blackbox.yaml for the values the black-box suite supplies.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

HEALTHCHECK --interval=2s --timeout=2s --start-period=30s --retries=30 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
