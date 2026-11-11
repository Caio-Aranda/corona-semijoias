#!/usr/bin/env bash
# Confere que SÓ o proxy publica porta (443) e que o banco e a API não publicam nada.
# O Docker pode furar o firewall (ufw) quando publica porta; este teste pega esse erro (ADR-0009, item 1).
set -uo pipefail
FALHOU=0

echo "Portas publicadas pelos contêineres:"
docker ps --format '{{.Names}}  {{.Ports}}'

while read -r nome portas; do
  [[ -z "$nome" ]] && continue
  if [[ "$portas" == *"->"* && "$nome" != *proxy* ]]; then
    echo "ERRO: o contêiner $nome publica porta para fora ($portas). Só o proxy pode."
    FALHOU=1
  fi
done < <(docker ps --format '{{.Names}} {{.Ports}}')

echo
echo "Portas escutando neste servidor:"
ss -ltnH | awk '{print $4}' | sort -u
if ss -ltnH | awk '{print $4}' | grep -qE ':(5432|8080)$'; then
  echo "ERRO: a porta 5432 ou 8080 está escutando no servidor."
  FALHOU=1
fi

if [[ $FALHOU -eq 0 ]]; then
  echo "OK: só o proxy publica porta."
else
  exit 1
fi
