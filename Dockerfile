# ==============================================================================
# Build Stage: Compile and Package Spring Boot Application
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Install dos2unix and bash to handle line endings safely across platforms
RUN apk add --no-cache bash dos2unix

# Copy Maven wrapper files and pom.xml first to take advantage of Docker layer caching
COPY .mvn .mvn
COPY mvnw pom.xml ./

# Normalize line endings and ensure execution rights
RUN dos2unix mvnw && chmod +x mvnw

# Download dependencies
RUN ./mvnw dependency:go-offline -B || true

# Copy project source code
COPY src ./src

# Package production executable JAR without running tests during image build
RUN ./mvnw clean package -DskipTests -B

# ==============================================================================
# Runtime Stage: Minimal JRE 21 Container
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runner

WORKDIR /app

# Install curl for health checking
RUN apk add --no-cache curl

# Create non-root user and persistent storage directories
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && \
    mkdir -p /app/data /app/uploads && \
    chown -R appuser:appgroup /app

# Copy executable jar from builder stage
COPY --from=builder /build/target/*.jar app.jar

USER appuser

# Expose Spring Boot HTTP port
EXPOSE 8080

# Default environment configuration
ENV PORT=8080 \
    PUBLIC_BASE_URL=http://localhost:8080 \
    ANALYTICS_URL=http://analytics:8000

# Health check to ensure service responds
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/auth/login | grep -E "400|405|200" || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
