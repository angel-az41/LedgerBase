# ============================================================
# LEDGERBASE — ACCOUNTING CORE CONTAINER
# ============================================================
#
# PURPOSE:
#
# Build and package the Accounting Core Spring Boot
# application as a standalone Docker image.
#
# BUILD STRATEGY:
#
# Use a multi-stage build:
#
# - Build stage compiles the application using Java 25.
# - Runtime stage contains only the Java runtime and JAR.
#
# The final container does not require Maven or source code.
#
# Database, Redis, and Kafka connections will be supplied
# through environment variables at container startup.


# ============================================================
# BUILD STAGE
# ============================================================

FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /workspace

# Copy the Maven wrapper and project definition.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Copy application source code.
COPY src/ src/

# Build the executable Spring Boot JAR.
#
# Automated tests are run separately before image builds.
# Skipping test execution here prevents Docker builds from
# requiring access to the test PostgreSQL database.
RUN chmod +x ./mvnw \
    && ./mvnw -B -DskipTests package


# ============================================================
# RUNTIME STAGE
# ============================================================

FROM eclipse-temurin:25-jre-noble AS runtime

WORKDIR /app

# Copy only the application JAR from the build stage.
# The source code and Maven build tools are excluded.
COPY --from=build /workspace/target/*.jar /app/app.jar

# Run the application as a non-root user.
USER 10001:10001

# Accounting Core listens on port 8080.
EXPOSE 8080

# Start Spring Boot using the packaged application.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
