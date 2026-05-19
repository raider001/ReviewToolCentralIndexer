FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/central-indexer.jar central-indexer.jar
EXPOSE 8765
ENTRYPOINT ["java", "-jar", "central-indexer.jar"]

