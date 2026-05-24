# Dumps the repositories and branches tables from the running Docker postgres container.
# Usage: .\query-repositories.ps1 [container-name]
param(
    [string]$Container = "reviewtoolcentralindexer-postgres-1"
)

Write-Host "`n=== repositories ===" -ForegroundColor Cyan
docker exec -i $Container psql -U postgres -d indexer -c `
    "SELECT owner, repository, url, kalynx_review_head FROM repositories ORDER BY owner, repository;"

Write-Host "`n=== branches ===" -ForegroundColor Cyan
docker exec -i $Container psql -U postgres -d indexer -c `
    "SELECT owner, repository, branch_name, head_commit FROM branches ORDER BY owner, repository, branch_name;"
