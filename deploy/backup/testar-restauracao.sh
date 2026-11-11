#!/usr/bin/env bash
# TESTE DE RESTAURAÇÃO (ADR-0009, item 5): restaura um backup num PostgreSQL LIMPO e descartável (um contêiner
# novo, que não toca no banco de produção) e confere o resultado. Mostra o tempo gasto.
# Faz exatamente o passo a passo de docs/restauracao-de-backup.md.
#
# Uso: testar-restauracao.sh <arquivo.dump> [pasta-do-init]
#   <arquivo.dump>   um backup feito por fazer-backup.sh
#   [pasta-do-init]  padrão: ../../infra/postgres/init (os mesmos papéis e schema da produção)
# Precisa das variáveis do banco (DB_NAME, DB_MIGRACAO_USER, DB_APP_USER, ...): o .env de deploy/ é lido.
set -euo pipefail
export MSYS_NO_PATHCONV=1 # só afeta testes no Git Bash do Windows; no servidor Linux não faz nada

DUMP="${1:?Informe o arquivo de backup (.dump)}"
DIR="$(cd "$(dirname "$0")" && pwd)"
INIT="${2:-$DIR/../../infra/postgres/init}"
DEPLOY="${CORONA_DEPLOY:-$DIR/..}"
[[ -f "$DUMP" ]] || { echo "ERRO: não achei $DUMP" >&2; exit 1; }
# Lê só o que precisa do .env (não faz "source": valores com espaço quebrariam).
lerenv() { grep -E "^$1=" "$DEPLOY/.env" | tail -1 | cut -d= -f2-; }
DB_NAME="$(lerenv DB_NAME)"; DB_MIGRACAO_USER="$(lerenv DB_MIGRACAO_USER)"; DB_APP_USER="$(lerenv DB_APP_USER)"

NOME="corona-teste-restauracao"
docker rm -f "$NOME" >/dev/null 2>&1 || true
limpar() { docker rm -f "$NOME" >/dev/null 2>&1 || true; }
trap limpar EXIT

T0=$(date +%s)
echo "1) Subindo um PostgreSQL limpo (sem nenhum dado)"
docker run -d --name "$NOME" \
  -e POSTGRES_USER=pgteste -e POSTGRES_PASSWORD=teste -e POSTGRES_DB="$DB_NAME" \
  -e DB_MIGRACAO_USER="$DB_MIGRACAO_USER" -e DB_MIGRACAO_PASSWORD=teste \
  -e DB_APP_USER="$DB_APP_USER" -e DB_APP_PASSWORD=teste \
  -v "$INIT":/docker-entrypoint-initdb.d:ro \
  postgres:18.6 postgres -c max_prepared_transactions=0 -c timezone=UTC >/dev/null
for _ in $(seq 1 60); do
  docker exec "$NOME" pg_isready -U pgteste -d "$DB_NAME" >/dev/null 2>&1 && \
    docker exec "$NOME" psql -U pgteste -d "$DB_NAME" -tAc "select 1 from pg_namespace where nspname='corona'" 2>/dev/null | grep -q 1 && break
  sleep 2
done
T1=$(date +%s)

echo "2) Restaurando $DUMP (a linha que cria o schema 'corona' é tirada da lista, porque ele já existe)"
docker cp "$DUMP" "$NOME":/tmp/backup.dump
docker exec "$NOME" bash -c "pg_restore -l /tmp/backup.dump | grep -v -E '^[0-9]+; [0-9]+ [0-9]+ SCHEMA - corona ' > /tmp/lista.txt"
docker exec "$NOME" pg_restore -U "$DB_MIGRACAO_USER" -d "$DB_NAME" --no-owner --single-transaction --exit-on-error -L /tmp/lista.txt /tmp/backup.dump
T2=$(date +%s)

echo "3) Preparando o sync depois da restauração (ADR-0002, R1)"
docker exec "$NOME" psql -U "$DB_MIGRACAO_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "SELECT corona.preparar_sync_apos_restauracao();"
T3=$(date +%s)

echo "4) Conferindo"
TABELAS="$(docker exec "$NOME" psql -U "$DB_MIGRACAO_USER" -d "$DB_NAME" -tAc "select count(*) from information_schema.tables where table_schema='corona'")"
USUARIOS="$(docker exec "$NOME" psql -U "$DB_MIGRACAO_USER" -d "$DB_NAME" -tAc "select count(*) from corona.usuarios")"
ADMINS="$(docker exec "$NOME" psql -U "$DB_MIGRACAO_USER" -d "$DB_NAME" -tAc "select count(*) from corona.usuarios where perfil='ADMIN' and ativo")"
MIGRACOES="$(docker exec "$NOME" psql -U "$DB_MIGRACAO_USER" -d "$DB_NAME" -tAc "select count(*) from corona.flyway_schema_history where success")"
echo "   tabelas: $TABELAS | usuários: $USUARIOS (administradores: $ADMINS) | migrações aplicadas: $MIGRACOES"
[[ "$TABELAS" -gt 0 && "$ADMINS" -ge 1 && "$MIGRACOES" -gt 0 ]] || { echo "ERRO: a restauração não trouxe o esperado." >&2; exit 1; }

echo
echo "OK. Tempo: subir o banco $((T1 - T0))s | restaurar $((T2 - T1))s | preparar o sync $((T3 - T2))s | total $((T3 - T0))s."
