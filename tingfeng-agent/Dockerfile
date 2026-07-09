FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/target/tingfeng-agent-0.1.0.jar /app/tingfeng-agent.jar
RUN mkdir -p /app/data
EXPOSE 8081
VOLUME ["/app/data"]
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENTRYPOINT exec java $JAVA_OPTS -jar /app/tingfeng-agent.jar
