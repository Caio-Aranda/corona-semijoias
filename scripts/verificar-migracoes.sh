#!/usr/bin/env bash
# Confere as migrações do Flyway de um Pull Request (usado pelo job "migracoes" do CI).
# Uso: bash scripts/verificar-migracoes.sh <commit-da-base-do-PR>
# Regras (CLAUDE.md: "Migrações Flyway publicadas nunca são editadas: criar uma nova"):
#   1. só arquivos novos: alterar, renomear ou apagar uma migração existente reprova;
#   2. a versão de cada migração nova é maior que a maior versão da base;
#   3. nenhuma versão se repete.
set -euo pipefail

base="${1:?Informe o commit da base do Pull Request.}"
pasta="backend/src/main/resources/db/migration"
erro=0

falha() {
  echo "::error::$1"
  erro=1
}

# "V001__base.sql" -> "1"; "V010_2__x.sql" -> "10.2" (o Flyway lê "_" como ponto).
versao() {
  local v="${1#V}"
  v="${v%%__*}"
  echo "${v//_/.}" | sed -E 's/(^|\.)0+([0-9])/\1\2/g'
}

maior() { printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1; }

# Migrações que já existem na base.
existentes=" "
maior_versao=""
maior_nome=""
while IFS= read -r caminho; do
  nome="${caminho##*/}"
  [[ "$nome" == V* ]] || continue
  v="$(versao "$nome")"
  existentes+="$v "
  if [ -z "$maior_versao" ] || { [ "$v" != "$maior_versao" ] && [ "$(maior "$v" "$maior_versao")" = "$v" ]; }; then
    maior_versao="$v"
    maior_nome="${nome%%__*}"
  fi
done < <(git ls-tree --name-only "$base" -- "$pasta/")

# Mudanças do PR (renomear aparece como apagar + criar).
novas=()
while IFS=$'\t' read -r situacao arquivo; do
  [ -n "$situacao" ] || continue
  nome="${arquivo##*/}"
  if [ "$situacao" != "A" ]; then
    falha "A migração $nome já existia e foi alterada, renomeada ou apagada. Migrações publicadas nunca são editadas (regra do CLAUDE.md): desfaça a mudança e crie uma migração nova."
  else
    novas+=("$nome")
  fi
done < <(git diff --no-renames --name-status "$base"...HEAD -- "$pasta")

# Versões das migrações novas.
vistas="$existentes"
for nome in "${novas[@]}"; do
  if [[ "$nome" =~ ^R__.+\.sql$ ]]; then
    continue
  fi
  if ! [[ "$nome" =~ ^V[0-9]+(_[0-9]+)*__.+\.sql$ ]]; then
    falha "O arquivo $nome não segue o padrão das migrações (por exemplo V002__descricao.sql). Renomeie o arquivo."
    continue
  fi
  v="$(versao "$nome")"
  etiqueta="${nome%%__*}"
  if [[ "$vistas" == *" $v "* ]]; then
    falha "A versão $etiqueta já está em uso por outra migração. Cada migração precisa de um número único. Renumere o arquivo $nome."
    continue
  fi
  vistas+="$v "
  if [ -n "$maior_versao" ] && [ "$(maior "$v" "$maior_versao")" != "$v" ]; then
    falha "A migração $etiqueta precisa ter número maior que a última da main ($maior_nome). Renumere o arquivo $nome."
  fi
done

if [ "$erro" -ne 0 ]; then
  exit 1
fi
echo "Migrações em ordem: ${#novas[@]} nova(s), nenhuma existente foi mexida."
