FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S nab && adduser -S nab -G nab
WORKDIR /app
RUN mkdir -p /app/data/photos && chown -R nab:nab /app
COPY --from=build /build/target/nab-api-1.0.0.jar app.jar
USER nab
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
