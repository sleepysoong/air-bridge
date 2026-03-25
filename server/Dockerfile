FROM golang:1.26.1-alpine AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /out/airbridge-relay ./cmd/airbridge-relay

FROM alpine:3.22

RUN addgroup -S airbridge && adduser -S airbridge -G airbridge

WORKDIR /app

COPY --from=build /out/airbridge-relay /app/airbridge-relay

RUN mkdir -p /app/data && chown -R airbridge:airbridge /app

USER airbridge

ENV AIR_BRIDGE_HTTP_ADDRESS=:8080
ENV AIR_BRIDGE_DATABASE_PATH=/app/data/airbridge-relay.db

EXPOSE 8080

ENTRYPOINT ["/app/airbridge-relay"]
