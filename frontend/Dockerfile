# MediCore frontend review image (plan Task 6). Multi-stage: the pinned-major
# Node build stage produces the static SPA bundle; the runtime is the
# unprivileged nginx image (non-root uid 101, listens on 8080). Only the
# built dist/ output and the reviewed nginx.conf enter the final image —
# never source tests, env files, or credential-shaped files.
FROM node:24-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=12 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
