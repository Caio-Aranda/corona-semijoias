#!/usr/bin/env bash
# Cópia semanal para FORA da VPS (Google Drive do responsável, por rclone; ADR-0009, item 5).
# Envia o backup diário mais recente (banco + arquivos + soma) e confere o que chegou.
#
# Configuração em /etc/corona/backup.env (permissão 600, fora do Git):
#   RCLONE_CONFIG=/etc/corona/rclone.conf        (criado por "rclone config"; o responsável autoriza o Drive)
#   RCLONE_DESTINO=drive-corona:corona-backups   ("nome-do-remoto:pasta")
#   SEMANAS_NO_DRIVE=8                           (quantas semanas ficam no Drive; padrão 8)
# Se usar um remoto "crypt" do rclone, o conteúdo fica criptografado no Drive.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ORIGEM="${PASTA_DO_BACKUP:-/var/backups/corona}/diario"
[[ -f /etc/corona/backup.env ]] && { set -a; . /etc/corona/backup.env; set +a; }
SEMANAS="${SEMANAS_NO_DRIVE:-8}"

falhou() {
  echo "FALHA no envio semanal: $*" >&2
  "$DIR/../monitoramento/avisar.sh" "Corona: o envio do backup ao Google Drive FALHOU" "$*. Veja: journalctl -u corona-backup-semanal" || true
  exit 1
}
trap 'falhou "erro na linha $LINENO"' ERR

[[ -n "${RCLONE_DESTINO:-}" ]] || falhou "RCLONE_DESTINO não está configurado em /etc/corona/backup.env"
command -v rclone >/dev/null || falhou "o rclone não está instalado"

ULTIMO="$(ls -1t "$ORIGEM"/corona-*.dump 2>/dev/null | head -1 || true)"
[[ -n "$ULTIMO" ]] || falhou "não há backup diário para enviar"
# O backup precisa ser recente (no máximo 2 dias); senão o diário parou e o envio copiaria coisa velha.
if [[ -z "$(find "$ULTIMO" -mtime -2 -print)" ]]; then falhou "o backup diário mais recente tem mais de 2 dias"; fi

CARIMBO="$(basename "$ULTIMO" .dump | sed 's/^corona-//')"
echo "Enviando $CARIMBO para $RCLONE_DESTINO"
rclone copy "$ORIGEM" "$RCLONE_DESTINO" --include "corona-$CARIMBO.dump" --include "arquivos-$CARIMBO.tar.gz" --include "soma-$CARIMBO.sha256" --checksum
# Confere que o que está no Drive é igual ao que está aqui.
rclone check "$ORIGEM" "$RCLONE_DESTINO" --one-way --include "corona-$CARIMBO.dump" --include "arquivos-$CARIMBO.tar.gz" --include "soma-$CARIMBO.sha256"

# Retenção no Drive.
rclone delete "$RCLONE_DESTINO" --min-age "$((SEMANAS * 7))d" || true

echo "Envio concluído: $CARIMBO"
"$DIR/../monitoramento/avisar.sh" "Corona: backup semanal enviado" "Cópia $CARIMBO conferida no Google Drive." || true
