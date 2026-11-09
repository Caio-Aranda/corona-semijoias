#!/bin/bash
# Cria os papéis e o schema do projeto (ADR-0002).
#
# ATENÇÃO: o PostgreSQL só executa os arquivos desta pasta quando o volume de
# dados está VAZIO (primeira subida ou depois de "npm run db:reset"). Se mudar
# este arquivo, rode "npm run db:reset" (apaga os dados de desenvolvimento).
set -euo pipefail

# As senhas chegam por variável do psql (:'nome' vira texto entre aspas e
# :"nome" vira identificador), então caracteres especiais não quebram o SQL.
psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v dbname="$POSTGRES_DB" \
  -v mig_user="$DB_MIGRACAO_USER" -v mig_pass="$DB_MIGRACAO_PASSWORD" \
  -v app_user="$DB_APP_USER" -v app_pass="$DB_APP_PASSWORD" <<'EOSQL'

-- Usuário das migrações (Flyway): dono do schema, sem superusuário e sem limites.
CREATE ROLE :"mig_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'mig_pass';

-- Usuário da aplicação (API): sem superusuário, sem criar nada.
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_pass';

-- Schema do projeto, de propriedade do usuário das migrações.
CREATE SCHEMA corona AUTHORIZATION :"mig_user";

-- Ninguém além dos usuários do projeto acessa o banco nem o schema "public".
REVOKE ALL ON DATABASE :"dbname" FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE :"dbname" TO :"mig_user", :"app_user";

-- A aplicação só usa o schema (USAGE), sem CREATE.
GRANT USAGE ON SCHEMA corona TO :"app_user";

ALTER ROLE :"mig_user" SET search_path = corona;
ALTER ROLE :"app_user" SET search_path = corona;

-- Limites de transação só para a aplicação (não valem para as migrações).
ALTER ROLE :"app_user" SET transaction_timeout = '60s';
ALTER ROLE :"app_user" SET idle_in_transaction_session_timeout = '30s';

-- Toda tabela futura criada pelo usuário das migrações: a aplicação lê, insere e
-- atualiza, mas NÃO apaga (sem DELETE nem TRUNCATE: regra "nunca DELETE").
ALTER DEFAULT PRIVILEGES FOR ROLE :"mig_user" IN SCHEMA corona
  GRANT SELECT, INSERT, UPDATE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"mig_user" IN SCHEMA corona
  GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";

EOSQL
