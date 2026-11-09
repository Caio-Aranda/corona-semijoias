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
├── docs/               especificação, glossário, decisões (ADRs) e progresso
├── CLAUDE.md           instruções para o Claude Code
└── README.md
```

Ainda vão entrar: `packages/api-client-dona`, `packages/api-client-vendedor`, `docker-compose.yml` e `.vscode/`. O Corona Vendas nunca importa código da dona (ADR-0003).

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
