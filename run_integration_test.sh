#!/bin/bash
set -e

CONTAINER_NAME="nf-irods-test-server"

echo "=== 1. Building the plugin ==="
make assemble

echo "=== 2. Installing the plugin locally ==="
mkdir -p ~/.nextflow/plugins
# Remove any previous version to ensure clean install
rm -rf ~/.nextflow/plugins/nf-irods-*
unzip -o build/distributions/nf-irods-*.zip -d ~/.nextflow/plugins/

echo "=== 3. Starting iRODS container ==="
# Check if container already exists and remove it
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "Stopping and removing existing container..."
    docker stop ${CONTAINER_NAME} >/dev/null 2>&1 || true
    docker rm ${CONTAINER_NAME} >/dev/null 2>&1 || true
fi

docker run -d --name ${CONTAINER_NAME} -p 1247:1247 --hostname localhost mjstealey/irods-provider-postgres:4.2.2 -i run_irods

# Ensure cleanup on exit
cleanup() {
    echo "=== Cleaning up ==="
    echo "Stopping iRODS container..."
    docker stop ${CONTAINER_NAME} >/dev/null 2>&1 || true
    docker rm ${CONTAINER_NAME} >/dev/null 2>&1 || true
    echo "Cleanup complete."
}
trap cleanup EXIT

echo "=== 4. Waiting for iRODS server to be ready ==="
IS_READY=false
for i in {1..40}; do
    # Try running ils as irods user inside the container
    if docker exec -u irods ${CONTAINER_NAME} ils &>/dev/null; then
        echo "iRODS is ready!"
        IS_READY=true
        break
    fi
    echo "Still waiting... ($i/40)"
    sleep 3
done

if [ "$IS_READY" = false ]; then
    echo "Error: iRODS server failed to start in time. Container logs:"
    docker logs ${CONTAINER_NAME}
    exit 1
fi

echo "=== 5. Running Nextflow test pipeline ==="
nextflow run test_plugin.nf -c test_nextflow.config

echo "=== Integration Test Passed Successfully! ==="
