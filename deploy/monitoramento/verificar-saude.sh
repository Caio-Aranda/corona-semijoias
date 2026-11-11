#!/usr/bin/env bash
# Roda a cada 5 minutos (timer). Avisa se a API não responde (2 falhas seguidas, para não avisar à toa num
# reinício) ou se o disco passa de 80% (aviso pelo ntfy, ver avisar.sh). Repete o aviso de queda só a cada 6 horas.
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ESTADO="${ESTADO_DA_SAUDE:-/var/lib/corona-saude}"
mkdir -p "$ESTADO"
PORTA="${PORTA_HTTPS:-443}"
CRT="${CORONA_CERTIFICADO:-/etc/corona/tls/servidor.crt}"

# O certificado é autoassinado: a saúde é conferida pela impressão digital da chave, sem desligar a verificação.
PIN="$(openssl x509 -in "$CRT" -pubkey -noout 2>/dev/null | openssl pkey -pubin -outform der 2>/dev/null | openssl dgst -sha256 -binary | openssl base64)"
RESP="$(curl -sS -m 10 -k --pinnedpubkey "sha256//$PIN" "https://127.0.0.1:$PORTA/actuator/health" 2>/dev/null || true)"

if echo "$RESP" | grep -q '"status":"UP"'; then
  if [[ -f "$ESTADO/fora" ]]; then
    "$DIR/avisar.sh" "Corona: a API voltou" "A API respondeu de novo em $(date -u +%FT%TZ)."
    rm -f "$ESTADO/fora"
  fi
  rm -f "$ESTADO/falhas"
else
  FALHAS=$(( $(cat "$ESTADO/falhas" 2>/dev/null || echo 0) + 1 ))
  echo "$FALHAS" > "$ESTADO/falhas"
  if [[ $FALHAS -ge 2 ]]; then
    AGORA=$(date +%s)
    ULTIMO=$(cat "$ESTADO/ultimo-aviso" 2>/dev/null || echo 0)
    if [[ ! -f "$ESTADO/fora" || $((AGORA - ULTIMO)) -ge 21600 ]]; then
      "$DIR/avisar.sh" "Corona: a API NÃO está respondendo" "Sem resposta de /actuator/health. No servidor: docker compose -f /opt/corona/deploy/docker-compose.yml ps e logs api."
      echo "$AGORA" > "$ESTADO/ultimo-aviso"
    fi
    touch "$ESTADO/fora"
  fi
fi

USO="$(df --output=pcent / | tail -1 | tr -dc '0-9')"
LIMITE_DO_DISCO="${LIMITE_DO_DISCO:-80}"
if [[ "${USO:-0}" -ge "$LIMITE_DO_DISCO" ]]; then
  AGORA=$(date +%s)
  ULTIMO=$(cat "$ESTADO/aviso-disco" 2>/dev/null || echo 0)
  if [[ $((AGORA - ULTIMO)) -ge 86400 ]]; then
    "$DIR/avisar.sh" "Corona: disco do servidor em ${USO}%" "O disco passou de ${LIMITE_DO_DISCO}%. Veja o que ocupa espaço (docker system df, /var/backups/corona, /srv/corona/arquivos) antes que a API pare."
    echo "$AGORA" > "$ESTADO/aviso-disco"
  fi
fi
