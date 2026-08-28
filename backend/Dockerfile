FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# gradlew сам скачает нужный дистрибутив Gradle (см. gradle/wrapper/gradle-wrapper.properties) —
# отдельный gradle/maven-образ не нужен, достаточно JDK.
COPY gradlew .
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew
# Кешируем разрешение зависимостей отдельным слоем — при правках только src
# пересборка не тянет Maven Central/Gradle Plugin Portal заново.
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
