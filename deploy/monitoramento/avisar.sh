#!/usr/bin/env bash
# Envia um aviso curto ao responsável. Usado pela verificação de saúde e pelo backup.
# Configuração em /etc/corona/aviso.env (permissão 600, fora do Git):
#   NTFY_URL=https://ntfy.sh/um-nome-longo-e-dificil-de-adivinhar   (aviso no celular, pelo app ntfy)
#   AVISO_EMAIL=responsavel@exemplo.com                             (precisa do msmtp configurado; opcional)
# Uso: avisar.sh "assunto" "texto"
set -uo pipefail
ASSUNTO="${1:-Aviso do Corona}"
TEXTO="${2:-}"
[[ -f /etc/corona/aviso.env ]] && . /etc/corona/aviso.env
ENVIOU=1
if [[ -n "${NTFY_URL:-}" ]]; then
  curl -fsS -m 15 -H "Title: $ASSUNTO" -H "Priority: high" -d "$TEXTO" "$NTFY_URL" >/dev/null && ENVIOU=0
fi
if [[ -n "${AVISO_EMAIL:-}" ]] && command -v msmtp >/dev/null; then
  printf 'To: %s\nSubject: %s\n\n%s\n' "$AVISO_EMAIL" "$ASSUNTO" "$TEXTO" | msmtp "$AVISO_EMAIL" && ENVIOU=0
fi
logger -t corona-aviso "$ASSUNTO: $TEXTO" 2>/dev/null || true
exit $ENVIOU
