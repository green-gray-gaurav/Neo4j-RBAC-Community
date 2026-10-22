FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY target/neo4j-rbac-parser-proof-0.1.0-SNAPSHOT-service.jar /app/service.jar
ENTRYPOINT ["java", "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-jar", "/app/service.jar"]
