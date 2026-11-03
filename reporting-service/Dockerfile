# ============================================================
# LEDGERBASE — FINANCIAL REPORTING SERVICE CONTAINER
# ============================================================
#
# PURPOSE:
#
# Package the independent Reporting Service as a
# Docker image using Java 25.
#
# ARCHITECTURE:
#
# The service performs read-only financial reporting
# against the PostgreSQL streaming replica.
#
# Accounting Core remains responsible for financial
# writes, session validation, and authorization.
#
# Redis connectivity is configured separately from
# the container image.
#
# BUILD STRATEGY:
#
# - Compile the service in a Java 25 JDK stage.
# - Package the executable Spring Boot application.
# - Run only the application JAR in a JRE stage.
#
# Build context: LedgerBase repository root.


# ============================================================
# BUILD STAGE
# ============================================================

FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /workspace

# Reuse the Maven wrapper already maintained by
# Accounting Core.
COPY backend/.mvn/ .mvn/
COPY backend/mvnw ./mvnw

# Copy the Reporting Service project definition.
COPY reporting-service/pom.xml ./reporting-service/pom.xml

# Copy Reporting Service Java code and resources.
COPY reporting-service/src/ ./reporting-service/src/

# Compile and package the service.
#
# Backend tests are executed separately.
# This image build does not depend on a running
# PostgreSQL database.
RUN chmod +x ./mvnw \
    && ./mvnw -B -f reporting-service/pom.xml \
       -DskipTests package


# ============================================================
# RUNTIME STAGE
# ============================================================

FROM eclipse-temurin:25-jre-noble AS runtime

WORKDIR /app

# Copy only the packaged executable JAR.
COPY --from=build \
    /workspace/reporting-service/target/*.jar \
    /app/app.jar

# Run with an unprivileged container identity.
USER 10001:10001

# Reporting Service HTTP port.
EXPOSE 8082

# Start the independent Spring Boot application.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
