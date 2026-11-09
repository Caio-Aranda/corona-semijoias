# Corona Semijoias

Sistema de semijoias consignadas, com três partes: o **Corona Gestão** (aplicativo da dona, sempre online), o **Corona Vendas** (aplicativo do vendedor, que funciona sem internet) e uma **API** única.

A especificação completa está em [docs/ESPECIFICACAO.md](docs/ESPECIFICACAO.md). As decisões de arquitetura estão em [docs/decisoes/](docs/decisoes/README.md).

## Estrutura de pastas

```
corona-semijoias/
├── .github/            modelo de Pull Request (workflows entram na tarefa 7)
├── backend/            API Spring Boot (criado na tarefa 6)
├── apps/owner-app/     Corona Gestão (Electron + React; só online)
├── apps/seller-app/    Corona Vendas (Electron + React; offline)
├── packages/ui/        componentes de interface compartilhados
├── packages/shared/    validações e formatadores compartilhados
├── infra/postgres/     script de criação dos papéis do banco de desenvolvimento
├── docker-compose.yml  PostgreSQL de desenvolvimento
├── docs/               especificação, glossário, decisões (ADRs) e progresso
├── CLAUDE.md           instruções para o Claude Code
└── README.md
```

Ainda vão entrar: `packages/api-client-dona`, `packages/api-client-vendedor`, `.vscode/`. O Corona Vendas nunca importa código da dona (ADR-0003).

## Pré-requisitos

Versões fixadas no [ADR-0001](docs/decisoes/ADR-0001-stack-e-versoes.md):

- Node.js 26.13 (o arquivo `.nvmrc` indica a versão)
- npm 11.20
- JDK 21 (Temurin)
- Docker Desktop

## Como rodar

Na pasta do projeto:

```bash
npm ci
```

```bash
npm run lint
```

```bash
npm run typecheck
```

```bash
npm run format:check
```

Para arrumar o formato dos arquivos automaticamente, use `npm run format`. Os próximos comandos entram nas tarefas seguintes.

## Banco de desenvolvimento

O PostgreSQL 18.6 roda em um container do Docker, **só para desenvolvimento** (a produção entra na Fase 9). O banco fica acessível apenas neste computador (`127.0.0.1`).

1. Instale e abra o Docker Desktop.
2. Copie o arquivo de exemplo e troque os valores `troque-isto` (o `.env` nunca vai para o Git). Os três usuários (administrador, migrações e aplicação) precisam ter nomes diferentes:

```bash
cp .env.example .env
```

3. Use os comandos:

```bash
npm run db:up
```

Sobe o banco e espera ficar saudável. Na primeira vez cria os usuários e o schema `corona`.

```bash
npm run db:down
```

Para o banco **sem apagar** os dados.

```bash
npm run db:reset
```

**Apaga todos os dados de desenvolvimento** e recria o banco do zero, com os usuários de novo. Use também depois de mudar o script de `infra/postgres/init/`, que só roda com o banco vazio.

Para conectar com um cliente de banco: host `localhost`, porta `DB_PORT` do `.env`, banco `DB_NAME`, usuário `DB_APP_USER` e a senha dele. O usuário da aplicação lê, insere e atualiza, mas não apaga linhas nem cria tabelas; quem cria tabelas é o usuário das migrações (`DB_MIGRACAO_USER`), dono do schema `corona`. Se a porta 5432 já estiver em uso no seu computador, troque `DB_PORT` no `.env`.
