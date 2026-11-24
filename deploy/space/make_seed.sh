#!/bin/bash
# Snapshots the demo workspace of a seeded stack (started with `docker compose -p gbseed ...` and filled by deploy/seed_demo.py) into deploy/space/seed/,
# which the single-container image restores on every start. Run it from the repository root.
set -euo pipefail
P=${1:-gbseed}
OUT=deploy/space/seed
mkdir -p "$OUT"
docker exec "${P}-db-1" psql -U postgres -d glassbox -qc "UPDATE workspaces SET name = 'Harbor Logistics demo' WHERE name = 'My workspace'"
docker exec "${P}-db-1" pg_dump -U postgres -d glassbox -Fc --no-owner > "$OUT/demo.dump"
MSYS_NO_PATHCONV=1 docker exec "${P}-api-1" tar -czf - -C /data/files . > "$OUT/files.tar.gz"
ls -l "$OUT"
