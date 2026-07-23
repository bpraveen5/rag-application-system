# ================================================================
# Multi-stage Dockerfile for RAG Application
# Stage 1: Build with official Maven + JDK 17 image (no mvnw needed)
# Stage 2: Minimal JRE runtime image
# ================================================================

# ── Stage 1: Build ───────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /workspace

# Copy POM first — Docker caches this layer so deps are only
# re-downloaded when pom.xml changes, not on every source change.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build the fat JAR, skipping tests (run them separately)
COPY src src
RUN mvn package -DskipTests -q

# Extract Spring Boot layertools for an efficient runtime image
RUN java -Djarmode=layertools \
         -jar target/rag-application-1.0.0.jar \
         extract

# ── Stage 2: Runtime ─────────────────────────────────────────────
FROM eclipse-temurin:17-jre AS runtime

# Security: run as non-root
RUN groupadd --system ragapp && \
    useradd --system --gid ragapp --no-create-home ragapp

WORKDIR /app

# Copy Spring Boot layers (most-stable → least-stable for cache hits)
COPY --from=builder /workspace/dependencies/          ./
COPY --from=builder /workspace/spring-boot-loader/    ./
COPY --from=builder /workspace/snapshot-dependencies/ ./
COPY --from=builder /workspace/application/           ./

# Create directories for logs and document uploads
RUN mkdir -p /app/logs /tmp/rag-documents && \
    chown -R ragapp:ragapp /app /tmp/rag-documents

USER ragapp

EXPOSE 8080

# JVM flags tuned for containers
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
  CMD curl -f http://localhost:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
