FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline -q || true
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN useradd -r -u 1001 jsignal
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER jsignal
ENTRYPOINT ["java", "-jar", "app.jar"]
