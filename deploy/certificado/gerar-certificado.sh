#!/usr/bin/env bash
# Certificado HTTPS autoassinado do servidor, com o IP no campo de nomes alternativos (ADR-0009, item 2).
# Rode NO SERVIDOR, como administrador (sudo). A chave privada nunca sai do servidor nem vai ao Git.
#
#   gerar-certificado.sh novo <IP>        gera chave E certificado (primeira vez, ou troca de chave)
#   gerar-certificado.sh renovar <IP>     novo certificado com a MESMA chave (o app continua funcionando)
#   gerar-certificado.sh reserva <pasta>  gera uma chave reserva numa pasta (para guardar FORA do servidor)
#   gerar-certificado.sh pin              mostra a impressão digital (SHA-256 da chave pública) do certificado atual
#
# Variáveis: CORONA_PASTA_CERTIFICADO (padrão /etc/corona/tls), DIAS (padrão 730).
set -euo pipefail

PASTA="${CORONA_PASTA_CERTIFICADO:-/etc/corona/tls}"
DIAS="${DIAS:-730}"
CRT="$PASTA/servidor.crt"
KEY="$PASTA/servidor.key"

erro() { echo "ERRO: $*" >&2; exit 1; }

impressao_da_chave_publica() { # lê PEM de chave pública no stdin
  openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64
}

mostrar_pin() { # arquivo de certificado
  local pin
  pin="$(openssl x509 -in "$1" -pubkey -noout | impressao_da_chave_publica)"
  echo
  echo "Impressão digital (pin) desta chave:  sha256/$pin"
  echo "Use no build do Corona Gestão (MAIN_VITE_CORONA_API_PINS), separando a atual e a reserva por vírgula."
}

emitir() { # $1 = IP, usa $KEY
  openssl req -x509 -new -key "$KEY" -sha256 -days "$DIAS" \
    -subj "/O=Corona Semijoias/CN=$1" \
    -addext "subjectAltName=IP:$1" \
    -addext "basicConstraints=critical,CA:FALSE" \
    -addext "keyUsage=critical,digitalSignature" \
    -addext "extendedKeyUsage=serverAuth" \
    -out "$CRT"
  chmod 644 "$CRT"
}

validar_ip() { [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || erro "Informe o endereço IP da VPS, por exemplo 203.0.113.10."; }

case "${1:-}" in
  novo)
    validar_ip "${2:-}"
    if [[ -e "$KEY" ]]; then erro "Já existe uma chave em $KEY. Para só renovar o certificado, use: renovar <IP>. Para trocar a chave de verdade, mova o arquivo antigo para fora (e leia o README)."; fi
    install -d -m 755 "$PASTA"
    umask 077
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$KEY"
    chmod 600 "$KEY"
    emitir "$2"
    echo "Certificado novo gerado em $CRT (vale $DIAS dias)."
    mostrar_pin "$CRT"
    ;;
  renovar)
    validar_ip "${2:-}"
    [[ -f "$KEY" ]] || erro "Não achei a chave em $KEY. Use: novo <IP>."
    cp -p "$CRT" "$CRT.anterior" 2>/dev/null || true
    emitir "$2"
    echo "Certificado renovado (mesma chave, mais $DIAS dias). O app NÃO precisa ser atualizado."
    echo "Recarregue o proxy:  docker compose -f /opt/corona/deploy/docker-compose.yml restart proxy"
    mostrar_pin "$CRT"
    ;;
  reserva)
    DEST="${2:-}"; [[ -n "$DEST" ]] || erro "Informe a pasta onde guardar a chave reserva."
    install -d -m 700 "$DEST"
    umask 077
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$DEST/reserva.key"
    echo "Chave reserva gerada em $DEST/reserva.key. GUARDE FORA DO SERVIDOR (cofre de senhas) e apague daqui."
    pin="$(openssl pkey -in "$DEST/reserva.key" -pubout | impressao_da_chave_publica)"
    echo "Impressão digital da reserva:  sha256/$pin"
    ;;
  pin)
    [[ -f "$CRT" ]] || erro "Não achei o certificado em $CRT."
    mostrar_pin "$CRT"
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
