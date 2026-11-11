#!/usr/bin/env bash
# Backup diário (ADR-0009, item 5). Roda pelo timer do systemd às 03:30 UTC. Faz:
#   1. pg_dump do schema "corona" (formato custom) pelo usuário das migrações, conferido com pg_restore -l;
#   2. cópia compactada da pasta de arquivos enviados (fotos e documentos);
#   3. soma de verificação (SHA-256) de cada arquivo;
#   4. apaga o que passou de 14 dias (sempre guarda os 3 mais recentes, mesmo que antigos).
# Qualquer falha avisa o responsável (monitoramento/avisar.sh) e termina com erro.
#
# Variáveis (opcionais; padrões de produção): CORONA_DEPLOY (/opt/corona/deploy), PASTA_DO_BACKUP
# (/var/backups/corona), CORONA_PASTA_ARQUIVOS (/srv/corona/arquivos), RETENCAO_DIAS (14).
set -euo pipefail

DEPLOY="${CORONA_DEPLOY:-/opt/corona/deploy}"
DESTINO="${PASTA_DO_BACKUP:-/var/backups/corona}/diario"
ARQUIVOS="${CORONA_PASTA_ARQUIVOS:-/srv/corona/arquivos}"
RETENCAO="${RETENCAO_DIAS:-14}"
DIR="$(cd "$(dirname "$0")" && pwd)"
AGORA="$(date -u +%Y%m%dT%H%M%SZ)"

falhou() {
  echo "FALHA no backup: $*" >&2
  "$DIR/../monitoramento/avisar.sh" "Corona: o backup FALHOU" "$* (em $AGORA). Veja: journalctl -u corona-backup" || true
  exit 1
}
trap 'falhou "erro na linha $LINENO"' ERR

# Lê só o que precisa do .env (não faz "source": valores com espaço, como o nome da dona, quebrariam).
lerenv() { grep -E "^$1=" "$DEPLOY/.env" | tail -1 | cut -d= -f2-; }
DB_MIGRACAO_USER="$(lerenv DB_MIGRACAO_USER)"
DB_NAME="$(lerenv DB_NAME)"
[[ -n "$DB_MIGRACAO_USER" && -n "$DB_NAME" ]] || falhou "DB_MIGRACAO_USER ou DB_NAME ausente em $DEPLOY/.env"
mkdir -p "$DESTINO"
umask 077

DUMP="$DESTINO/corona-$AGORA.dump"
echo "[$AGORA] pg_dump -> $DUMP"
# Pela conexão local do contêiner (soquete): não passa senha por argumento nem sai pela rede.
( cd "$DEPLOY" && docker compose exec -T postgres pg_dump -U "$DB_MIGRACAO_USER" -d "$DB_NAME" -n corona -Fc ) > "$DUMP.parcial"
[[ -s "$DUMP.parcial" ]] || falhou "o pg_dump gerou um arquivo vazio"
# O arquivo precisa ser legível pelo pg_restore e ter tabelas.
LINHAS="$( cd "$DEPLOY" && docker compose exec -T postgres pg_restore -l < "$DUMP.parcial" | grep -c ' TABLE ' || true )"
[[ "$LINHAS" -gt 0 ]] || falhou "o backup não tem nenhuma tabela (pg_restore -l)"
mv "$DUMP.parcial" "$DUMP"

if [[ -d "$ARQUIVOS" ]]; then
  TAR="$DESTINO/arquivos-$AGORA.tar.gz"
  tar -czf "$TAR.parcial" -C "$ARQUIVOS" .
  mv "$TAR.parcial" "$TAR"
fi

( cd "$DESTINO" && sha256sum "corona-$AGORA.dump" ${TAR:+"arquivos-$AGORA.tar.gz"} > "soma-$AGORA.sha256" )

# Retenção: apaga o que tem mais de RETENCAO dias, mas nunca deixa menos de 3 backups do banco.
TOTAL="$(ls -1 "$DESTINO"/corona-*.dump | wc -l)"
if [[ "$TOTAL" -gt 3 ]]; then
  find "$DESTINO" -maxdepth 1 -type f \( -name 'corona-*.dump' -o -name 'arquivos-*.tar.gz' -o -name 'soma-*.sha256' \) -mtime +"$RETENCAO" -print -delete
fi

echo "[$AGORA] backup concluído: $(du -h "$DUMP" | cut -f1) de banco; $(ls -1 "$DESTINO"/corona-*.dump | wc -l) backups guardados."
