# ============================================================
# LEDGERBASE — REACT FRONTEND CONTAINER
# ============================================================
#
# PURPOSE:
#
# Build and serve the React + TypeScript frontend.
#
# BUILD STRATEGY:
#
# - Node.js installs dependencies and builds the app.
# - Nginx serves the generated static assets.
# - Spring Cloud Gateway handles backend API routing.
#
# The final image does not contain Node.js or the
# frontend development dependencies.


# ============================================================
# BUILD STAGE
# ============================================================

FROM node:22-alpine AS build

WORKDIR /app

# Copy dependency manifests first so Docker can
# reuse the dependency layer when source files change.
COPY package.json package-lock.json ./

# Install exact dependency versions from the lockfile.
RUN npm ci

# Copy frontend source and configuration.
COPY . .

# Compile TypeScript and build optimized assets.
RUN npm run build


# ============================================================
# RUNTIME STAGE
# ============================================================

FROM nginx:stable-alpine AS runtime

# Replace the default Nginx configuration with
# LedgerBase's SPA routing and API proxy configuration.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy only the production frontend assets.
COPY --from=build /app/dist /usr/share/nginx/html

# Nginx listens on port 80 inside the container.
EXPOSE 80

# Keep Nginx in the foreground for Docker lifecycle
# management and container logging.
CMD ["nginx", "-g", "daemon off;"]
