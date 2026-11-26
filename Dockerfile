FROM alpine:3.23 AS build

WORKDIR /source
COPY bidstay-backend/pom.xml bidstay-backend/pom.xml
COPY bidstay-backend/src/ bidstay-backend/src/
COPY bidstay-portals/ bidstay-portals/

ARG VITE_MAP_TILE_URL
ARG VITE_MAP_ATTRIBUTION
RUN apk add --no-cache ca-certificates openjdk21-jdk maven nodejs npm \
    && mvn -B -f bidstay-backend/pom.xml -DskipTests package \
    && cp bidstay-backend/target/bidstay-backend-*.jar /bidstay.jar

FROM alpine:3.23

WORKDIR /app
COPY --from=build /bidstay.jar /app/bidstay.jar
# ImageIO uses the Java desktop libraries to process hotel and room photos.
RUN apk add --no-cache ca-certificates openjdk21-jre
USER 10001:10001
EXPOSE 8080
CMD ["sh", "-c", "exec java -jar /app/bidstay.jar --server.port=\"${PORT:-8080}\""]
