# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy only the POMs first and pre-fetch dependencies, so Docker layer
# caching skips the (slow) dependency download when only source changes.
# NOTE: the parent POM declares BOTH modules, so both module POMs must be
# present for Maven to read the reactor, even when building just one module.
COPY pom.xml .
COPY event-gateway/pom.xml event-gateway/
COPY account-service/pom.xml account-service/
RUN mvn -q -pl account-service -am dependency:go-offline

COPY account-service/src account-service/src
RUN mvn -q -pl account-service -am package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-root user (least privilege)
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /build/account-service/target/account-service-*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
