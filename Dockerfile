# Native image build. For a JVM image use Dockerfile.jvm.
#   docker build -t flagpole .
#   docker run -p 8080:8080 -v "$PWD/flags.yaml:/config/flags.yaml:ro" flagpole

FROM ghcr.io/graalvm/native-image-community:21 AS build
# gradlew needs xargs, which the slim Oracle Linux base image lacks.
RUN microdnf install -y findutils && microdnf clean all
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties openapi.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon nativeCompile -x test -PmostlyStatic

FROM gcr.io/distroless/base-debian12:nonroot
COPY --from=build /build/build/native/nativeCompile/flagpole /app/flagpole
ENV FLAGS_FILE=/config/flags.yaml
ENV LOG_FORMAT=json
EXPOSE 8080
ENTRYPOINT ["/app/flagpole"]
