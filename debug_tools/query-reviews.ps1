# Dumps the reviews_index and review_branches tables from the running Docker postgres container.
# Usage: .\query-reviews.ps1 [container-name]
param(
    [string]$Container = "reviewtoolcentralindexer-postgres-1"
)

Write-Host "`n=== reviews_index ===" -ForegroundColor Cyan
docker exec -i $Container psql -U postgres -d indexer -c `
    "SELECT review_id, status, last_updated, repositories FROM reviews_index ORDER BY last_updated DESC;"

Write-Host "`n=== review_branches ===" -ForegroundColor Cyan
docker exec -i $Container psql -U postgres -d indexer -c `
    "SELECT review_id, owner, repository, branch_name FROM review_branches ORDER BY review_id, owner, repository;"
