FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /opt/app
RUN useradd --system --home /opt/app --shell /usr/sbin/nologin todo
COPY --from=build /workspace/target/*.jar app.jar
USER todo
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/opt/app/app.jar"]
