#!/usr/bin/env bash
# Instala os timers do backup diario e do envio semanal (systemd). Rode como root, na VPS.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "ERRO: rode como root." >&2; exit 1; }
DIR="$(cd "$(dirname "$0")" && pwd)"
RAIZ="$(cd "$DIR/.." && pwd)"
chmod +x "$DIR"/*.sh
for u in corona-backup corona-backup-semanal; do
  sed -e "s#@DIR@#$DIR#g" -e "s#@RAIZ@#$RAIZ#g" "$DIR/$u.service" > "/etc/systemd/system/$u.service"
  cp "$DIR/$u.timer" "/etc/systemd/system/$u.timer"
done
systemctl daemon-reload
systemctl enable --now corona-backup.timer
# O envio semanal so liga depois que /etc/corona/backup.env existir (veja enviar-semanal.sh).
if [[ -f /etc/corona/backup.env ]]; then systemctl enable --now corona-backup-semanal.timer; else echo "Falta /etc/corona/backup.env: o envio semanal ao Drive NAO foi ligado ainda."; fi
systemctl list-timers "corona-backup*" --no-pager
