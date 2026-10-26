FROM gradle:8.13-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/install/plantplacement-backend/ ./
ENV PORT=8080
EXPOSE 8080
CMD ["./bin/plantplacement-backend"]
