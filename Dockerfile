FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/central-indexer.jar central-indexer.jar
EXPOSE 8765
ENTRYPOINT java \
    -Dorg.slf4j.simpleLogger.defaultLogLevel=${LOG_LEVEL:-debug} \
    -Djava.net.preferIPv4Stack=true \
    -jar central-indexer.jar ${INDEXER_ARGS:-}

