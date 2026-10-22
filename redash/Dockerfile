ARG REDASH_IMAGE
FROM ${REDASH_IMAGE}
COPY redash/neo4j_rbac.py /app/redash/query_runner/neo4j_rbac.py
RUN cp /app/client/dist/images/db-logos/url.png /app/client/dist/images/db-logos/neo4j_rbac.png
