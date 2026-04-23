# Build stage: Gradle fat-jar on x86_64 (GLIDE publishes linux-x86_64 native)
FROM --platform=linux/amd64 gradle:8.5-jdk11 AS build
WORKDIR /src
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle --no-daemon fatJar -q

# Runtime: JDK 11.0.18 (repro is sensitive to this patch version)
FROM --platform=linux/amd64 amazoncorretto:11.0.18-al2
COPY --from=build /src/build/libs/repro.jar /repro.jar
ENTRYPOINT ["java", \
  "-Xmx1g", "-Xms1g", \
  "-XX:MaxDirectMemorySize=96m", \
  "-Dio.netty.maxDirectMemory=0", \
  "-XX:+UseG1GC", \
  "-XX:+UseStringDeduplication", \
  "-jar", "/repro.jar"]
