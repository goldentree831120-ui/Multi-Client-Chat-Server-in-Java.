# Chat server image (M-A step 2).
#
#   docker build -t chat-server:local .
#   docker run --rm -p 5000:5000 -p 9100:9100 chat-server:local
#
# Two stages: the JDK image compiles the code and runs the test suite, so an image is only
# produced if all tests pass; the final image has just the JRE and the compiled classes.

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build
COPY src/ src/
COPY test/ test/
RUN javac -d out src/*.java \
 && javac -d test-out -cp out test/*.java \
 && java -ea -cp out:test-out ChatServerTest

FROM eclipse-temurin:21-jre-noble
# Run as an unprivileged user, not root.
RUN useradd --system --no-create-home --uid 10001 chat
WORKDIR /app
COPY --from=build /build/out/ /app/
USER 10001

# Chat protocol and Prometheus metrics. Console logging of every message is off by default
# in the container: it slows the server under load and fills the container log.
ENV CHAT_PORT=5000 \
    METRICS_PORT=9100 \
    CHAT_LOG_MESSAGES=false
EXPOSE 5000 9100

# Size the heap from the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/app", "ChatServer"]
