# Multi-stage build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy everything from project root
COPY . .

# Make gradlew executable
RUN chmod +x ./gradlew

# Build
RUN ./gradlew clean bootJar -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user
RUN addgroup -g 1001 -S appuser && adduser -S appuser -u 1001

# Copy JAR from builder
COPY --from=builder /app/build/libs/wallet-service-*.jar app.jar

# Create logs directory
RUN mkdir -p /app/logs && chown -R appuser:appuser /app

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
