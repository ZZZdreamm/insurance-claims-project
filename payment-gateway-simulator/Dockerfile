FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY payment-gateway-simulator/pom.xml pom.xml
COPY .mvn .mvn
RUN mvn -B -q dependency:go-offline || true
COPY payment-gateway-simulator/src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /build/target/payment-gateway-simulator-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
