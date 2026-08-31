# Build from the repository root (multi-module: the service depends on platform-commons / platform-outbox):
#   docker build -f claim-service/Dockerfile -t claims/claim-service .
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /build
COPY pom.xml .
COPY .mvn .mvn
# every module pom is needed for the reactor to resolve, sources only for what we build
COPY platform-commons/pom.xml platform-commons/pom.xml
COPY platform-outbox/pom.xml platform-outbox/pom.xml
COPY claim-service/pom.xml claim-service/pom.xml
COPY payout-service/pom.xml payout-service/pom.xml
COPY payment-gateway-simulator/pom.xml payment-gateway-simulator/pom.xml
COPY search-service/pom.xml search-service/pom.xml
RUN mvn -B -q -pl claim-service -am dependency:go-offline -DexcludeGroupIds=com.kmultan.claims || true
COPY platform-commons/src platform-commons/src
COPY platform-outbox/src platform-outbox/src
COPY claim-service/src claim-service/src
RUN mvn -B -q -pl claim-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /build/claim-service/target/claim-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
