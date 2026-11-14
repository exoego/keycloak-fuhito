# The image that scripts/aislop.sh runs. It holds aislop and nothing else.
# The build has the network and no project file. The scan runs from this image with no network.

FROM node:24-slim@sha256:dc26ae92c9fb15cf6b1d63e20f0944b2efe0f115b20e00be48fb23fceb061c54
# renovate: datasource=npm depName=aislop
ARG AISLOP_VERSION=0.20.0
RUN npm install --prefix /opt/aislop --ignore-scripts --no-audit --no-fund --loglevel=error \
        "aislop@${AISLOP_VERSION}" \
    && npm cache clean --force
ENV PATH=/opt/aislop/node_modules/.bin:$PATH
USER node
ENTRYPOINT ["aislop"]
