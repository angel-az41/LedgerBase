# ============================================================
# LEDGERBASE — SPRING CLOUD GATEWAY CONTAINER
# ============================================================
#
# PURPOSE:
#
# Package the Spring Cloud Gateway application as an
# independently deployable Docker container.
#
# BUILD STRATEGY:
#
# - Compile the Gateway module using Java 25.
# - Reuse the existing LedgerBase Maven wrapper.
# - Package the executable Spring Boot JAR.
# - Copy only the JAR into the runtime image.
#
# The build context is the LedgerBase repository root
# because the Maven wrapper is stored in backend/.
#
# Runtime upstream service addresses are supplied
# through environment variables in Docker Compose.


# ============================================================
# BUILD STAGE
# ============================================================

FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /workspace

# Reuse the existing Maven wrapper.
COPY backend/.mvn/ .mvn/
COPY backend/mvnw ./mvnw

# Copy the Gateway Maven configuration.
COPY gateway/pom.xml ./gateway/pom.xml

# Copy Gateway application source files.
COPY gateway/src/ ./gateway/src/

# Build the executable Spring Boot application.
#
# Unit tests are executed separately during normal
# development and CI verification.
RUN chmod +x ./mvnw \
    && ./mvnw -B -f gateway/pom.xml -DskipTests package


# ============================================================
# RUNTIME STAGE
# ============================================================

FROM eclipse-temurin:25-jre-noble AS runtime

WORKDIR /app

# Include only the packaged Gateway application.
COPY --from=build /workspace/gateway/target/*.jar /app/app.jar

# Execute as a non-root user.
USER 10001:10001

# Spring Cloud Gateway listens on port 8081.
EXPOSE 8081

# Start Gateway using its packaged Spring Boot JAR.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
