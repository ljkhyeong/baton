ARG CADDY_BUILDER_IMAGE=caddy:2.11.4-builder-alpine@sha256:7bac9be4072f7c4db2ccc7350750e0705004bf02da2ac7d96b1469ca4f28bb7c
ARG CADDY_IMAGE=caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648
ARG CADDY_VERSION=v2.11.4
ARG CADDY_RATE_LIMIT_MODULE=github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/index.html frontend/tsconfig.json frontend/tsconfig.app.json frontend/tsconfig.node.json frontend/tsconfig.test.json frontend/vite.config.ts ./
COPY frontend/public ./public
COPY frontend/src ./src

ARG VITE_SENTRY_DSN=
ARG VITE_SENTRY_ENVIRONMENT=production
ARG VITE_STATUS_PAGE_ENABLED=false
ARG SENTRY_SOURCEMAPS_UPLOAD=false
ARG SENTRY_ORG=
ARG SENTRY_PROJECT=
RUN --mount=type=secret,id=sentry_auth_token,env=SENTRY_AUTH_TOKEN npm run build

FROM ${CADDY_BUILDER_IMAGE} AS caddy-build
ARG CADDY_VERSION
ARG CADDY_RATE_LIMIT_MODULE
RUN GOTOOLCHAIN=local xcaddy build "${CADDY_VERSION}" \
    --output /usr/bin/caddy \
    --with "${CADDY_RATE_LIMIT_MODULE}" \
    && caddy list-modules | grep -Fxq 'http.handlers.rate_limit'

FROM ${CADDY_IMAGE}

COPY --from=caddy-build /usr/bin/caddy /usr/bin/caddy
COPY ops/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/frontend/dist /srv

RUN command -v setcap >/dev/null \
    && command -v getcap >/dev/null \
    && { setcap -r /usr/bin/caddy 2>/dev/null || true; } \
    && test -z "$(getcap /usr/bin/caddy)" \
    && BATON_HOST=baton.invalid \
    BATON_ROUND_RUNTIME_ENABLED=false \
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile \
    && caddy list-modules | grep -Fxq 'http.handlers.rate_limit'
