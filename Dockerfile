# ─────────────────────────────────────────────
# Stage 1: Build the JAR
# ─────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy parent pom first
COPY pom.xml .

# Copy module poms
COPY Api/pom.xml Api/pom.xml
COPY RestApi/pom.xml RestApi/pom.xml

# Download dependencies (cached layer unless pom changes)
RUN mvn dependency:go-offline -B

# Copy source code
COPY Api/src Api/src
COPY RestApi/src RestApi/src

# Build the shaded JAR
RUN mvn clean package -DskipTests -B

# ─────────────────────────────────────────────
# Stage 2: Run the JAR
# ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy the shaded JAR from the build stage
COPY --from=builder /app/RestApi/target/RestApi-1.0-SNAPSHOT-shaded.jar app.jar

# Copy openapi.yaml and swagger UI resources (Vert.x reads these at runtime)
COPY --from=builder /app/RestApi/src/main/resources/openapi.yaml openapi.yaml
COPY --from=builder /app/RestApi/src/main/resources/swagger swagger/

# Expose the API port
EXPOSE 8888

# Start the server
ENTRYPOINT ["java", "-jar", "app.jar"]
