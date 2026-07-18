FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Run as non-root (quota'd cluster; matches the scopus-python image convention).
RUN addgroup -S app && adduser -S -G app -u 10001 app
USER app

ARG JAR_FILE=build/libs/*-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
