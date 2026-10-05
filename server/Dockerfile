FROM rust:1.99-bookworm AS build

RUN apt-get update && apt-get install -y --no-install-recommends build-essential libssl-dev pkg-config \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
COPY templates ./templates
RUN cargo build --locked --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl libssl3 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/imu-nav-cell-server /usr/local/bin/imu-nav-cell-server
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/imu-nav-cell-server"]
CMD ["--config", "/etc/imu-nav/config.toml"]
