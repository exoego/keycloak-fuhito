# The image that scripts/aislop.sh runs. It holds aislop, PMD, and the Java that PMD needs.
# The build has the network and no project file. The scan runs from this image with no network.

FROM eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07 AS pmd
# renovate: datasource=github-releases depName=pmd/pmd extractVersion=^pmd_releases/(?<version>.+)$
ARG PMD_VERSION=7.29.0
# The checksum of the zip of that version. Renovate cannot update it, so change it by hand with the version.
ARG PMD_SHA256=d27177c185b837fa57f097e482d1253468aa035379c5eb2671fa0a279109b179
WORKDIR /opt
RUN curl -fsSL -o pmd.zip \
        "https://github.com/pmd/pmd/releases/download/pmd_releases/${PMD_VERSION}/pmd-dist-${PMD_VERSION}-bin.zip" \
    && echo "${PMD_SHA256}  pmd.zip" | sha256sum -c - \
    && jar xf pmd.zip \
    && mv "pmd-bin-${PMD_VERSION}" pmd \
    && chmod +x pmd/bin/pmd \
    && rm pmd.zip

FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2 AS java

FROM node:24-slim@sha256:dc26ae92c9fb15cf6b1d63e20f0944b2efe0f115b20e00be48fb23fceb061c54
# renovate: datasource=npm depName=aislop
ARG AISLOP_VERSION=0.20.0
COPY --from=java /opt/java/openjdk /opt/java/openjdk
COPY --from=pmd /opt/pmd /opt/pmd
RUN npm install --prefix /opt/aislop --ignore-scripts --no-audit --no-fund --loglevel=error \
        "aislop@${AISLOP_VERSION}" \
    && npm cache clean --force
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/aislop/node_modules/.bin:/opt/pmd/bin:/opt/java/openjdk/bin:$PATH
USER node
ENTRYPOINT ["aislop"]
