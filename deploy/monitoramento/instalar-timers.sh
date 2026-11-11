#!/usr/bin/env bash
# Instala o timer do systemd da verificação de saúde (5 min). O backup tem o seu, em deploy/backup/.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "ERRO: rode como root." >&2; exit 1; }
DIR="$(cd "$(dirname "$0")" && pwd)"
chmod +x "$DIR"/*.sh
sed "s#@DIR@#$DIR#g" "$DIR/corona-saude.service" > /etc/systemd/system/corona-saude.service
cp "$DIR/corona-saude.timer" /etc/systemd/system/corona-saude.timer
systemctl daemon-reload
systemctl enable --now corona-saude.timer
systemctl list-timers corona-saude.timer --no-pager
echo "Para receber o aviso, crie /etc/corona/aviso.env (veja monitoramento/avisar.sh) e teste: $DIR/avisar.sh teste ola"
