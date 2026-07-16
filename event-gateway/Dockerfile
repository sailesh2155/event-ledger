# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy only the POMs first and pre-fetch dependencies, so Docker layer
# caching skips the (slow) dependency download when only source changes.
COPY pom.xml .
COPY event-gateway/pom.xml event-gateway/
COPY account-service/pom.xml account-service/
RUN mvn -q -pl event-gateway -am dependency:go-offline

COPY event-gateway/src event-gateway/src
RUN mvn -q -pl event-gateway -am package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-root user (least privilege)
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /build/event-gateway/target/event-gateway-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
