# Corona Semijoias

Sistema de semijoias consignadas, com três partes: o **Corona Gestão** (aplicativo da dona, sempre online), o **Corona Vendas** (aplicativo do vendedor, que funciona sem internet) e uma **API** única.

A especificação completa está em [docs/ESPECIFICACAO.md](docs/ESPECIFICACAO.md). As decisões de arquitetura estão em [docs/decisoes/](docs/decisoes/README.md).

## Estrutura de pastas

```
corona-semijoias/
├── .github/            modelo de Pull Request (workflows entram na tarefa 7)
├── backend/            API Spring Boot (Maven wrapper em backend/mvnw)
├── apps/owner-app/     Corona Gestão (Electron + React; só online)
├── apps/seller-app/    Corona Vendas (Electron + React; offline)
├── packages/ui/        componentes de interface compartilhados
├── packages/shared/    validações e formatadores compartilhados
├── infra/postgres/     script de criação dos papéis do banco de desenvolvimento
├── scripts/            auxiliares dos comandos npm (mvnw.mjs)
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

> **Cuidado com as senhas do `.env`: não use o caractere `$`.** O Docker Compose lê `$` como início de variável e a senha chega cortada ao banco, **sem nenhum erro**. Evite também `\` (a API lê o `.env` como arquivo de propriedades, e a barra invertida muda o sentido do que vem depois) e aspas. Use só letras, números e `-` `_` `.`.

## API

A API (Spring Boot 4.1.2, Java 21, Flyway) fica em `backend/`. Ela lê o mesmo `.env` da raiz usado pelo Docker Compose: nada de senha duplicada. O banco precisa estar de pé:

```bash
npm run db:up
```

```bash
npm run api:run
```

Sobe a API em `http://localhost:8080`, aplica as migrações com o usuário das migrações (`DB_MIGRACAO_USER`) e conecta com o usuário da aplicação (`DB_APP_USER`). Roda com o perfil `dev`, que liga a documentação:

- saúde da API: `http://localhost:8080/actuator/health` (só mostra `UP` ou `DOWN`, sem detalhes);
- Swagger: `http://localhost:8080/swagger-ui/index.html`;
- documentos OpenAPI: `http://localhost:8080/v3/api-docs/dona` e `http://localhost:8080/v3/api-docs/vendedor`.

Fora do perfil `dev` (produção), o Swagger e o OpenAPI ficam desligados. Se faltar o `.env` (ou alguma variável do banco), a API não sobe e explica em português o que fazer.

```bash
npm run api:test
```

Roda `./mvnw verify`: compila e executa todos os testes. **O Docker Desktop precisa estar aberto**, porque os testes sobem um PostgreSQL 18.6 de verdade (Testcontainers) com o mesmo script `infra/postgres/init/01-papeis.sh` e os dois usuários do banco. Não precisam do `.env` nem do `npm run db:up`.

Os comandos passam por `scripts/mvnw.mjs`, que escolhe `mvnw.cmd` no Windows e `./mvnw` no Linux; os dois funcionam no PowerShell e no Git Bash. Para rodar o Maven direto, entre em `backend/` e use `./mvnw` (Git Bash) ou `.\mvnw.cmd` (PowerShell). O Maven em si não precisa estar instalado: o wrapper baixa o 3.9.16 na primeira vez.

O `.env` é encontrado tanto rodando de `backend/` quanto da raiz do projeto (por exemplo, pelo VS Code). O perfil `dev` (documentação ligada) só é ativado por `npm run api:run`; ao subir de outra forma, a API funciona igual, mas sem o Swagger. A configuração do VS Code entra na tarefa 17.

## CI

A cada Pull Request e a cada push na `main`, o GitHub Actions (`.github/workflows/ci.yml`) roda quatro conferências:

- `frontend`: instala, lint, verificação de tipos, formatação, testes e builds dos apps;
- `backend`: `./mvnw -B verify` com Java 21.0.13 e PostgreSQL de verdade (Testcontainers);
- `migracoes`: só migrações novas, com número maior que o da `main` e sem repetir (só em Pull Request; rode `bash scripts/verificar-migracoes.sh <commit-da-base>` para conferir no seu computador);
- `dependencias`: `npm audit` e OSV-Scanner; reprova com falha de segurança alta ou crítica (nota 7.0 ou mais).

Para ver o resultado, abra o Pull Request e olhe a lista de verificações no fim da página, ou a aba **Actions** do repositório. O Dependabot abre, toda segunda, PRs de atualização agrupados (`.github/dependabot.yml`).

**Não se faz merge com CI vermelho.** A `main` não tem proteção técnica no plano gratuito; essa regra é de disciplina.
