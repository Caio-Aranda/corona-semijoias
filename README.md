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

**Login e token de acesso.** A API também precisa, no `.env`:

- `JWT_CHAVE_ATUAL_ID` e `JWT_CHAVE_ATUAL`: a chave que assina os tokens (gere o valor com `openssl rand -base64 32`; o comando do PowerShell está no `.env.example`). O valor em Base64 pode ter `+`, `/` e `=`, e isso não atrapalha;
- `SESSAO_SEGREDO_RENOVACAO`: o segredo dos tokens de renovação (sessão longa de 30 dias). Gere do mesmo jeito, com um valor **diferente** das chaves do token: vazio, curto ou igual a elas, a API não sobe e explica;
- `CORONA_PROXIES_CONFIAVEIS` (opcional; vazio em desenvolvimento): o endereço do Nginx na frente da API, o único de quem vale o cabeçalho com o IP real, usado no limite de tentativas;
- `ADMIN_NOME`, `ADMIN_LOGIN` e `ADMIN_SENHA`: a conta da dona, criada na primeira subida sem nenhum administrador no banco. Elas só são exigidas enquanto o banco não tem administrador: depois da primeira subida, não são mais lidas, e a senha inicial pode (e deve) sair do `.env` do servidor.

Quem já tinha um `.env` de antes precisa acrescentar essas variáveis (a antiga `JWT_SECRET` não é mais usada; a `SESSAO_SEGREDO_RENOVACAO` é nova da tarefa 11). Para testar no Swagger: `POST /api/auth/login` com o login e a senha da dona, copie o `tokenDeAcesso` e use o botão **Authorize**. Toda rota, menos o login, a renovação, a saúde e (no perfil `dev`) a documentação, exige o token.

**Sessão longa e limite de tentativas.** O login devolve também o `tokenDeRenovacao` (30 dias, contados de cada renovação). `POST /api/auth/renovar` com ele (e o mesmo `dispositivoId` do login, no Corona Vendas) devolve um token de acesso novo e um token de renovação novo; o anterior deixa de valer. `POST /api/auth/sair` encerra a sessão. O vendedor manda o `dispositivoId` no login; a dona não. Depois de 5 erros seguidos do mesmo usuário no mesmo endereço, o login responde 429 por 15 minutos (os dois números ficam na tabela `configuracoes`).

**Primeira entrada e senha temporária.** A conta do `.env` nasce com a troca de senha obrigatória: o login devolve só um token restrito (`trocaDeSenhaObrigatoria: true`), que só abre `POST /api/auth/trocar-senha` (senha nova com pelo menos 8 caracteres, letras e números, diferente da atual e com até 72 bytes); essa rota devolve o token normal e o de renovação. A dona cria os outros usuários em `POST /api/dona/usuarios` (a senha temporária aparece uma única vez na resposta e vale 7 dias) e redefine senhas em `POST /api/dona/usuarios/{id}/redefinir-senha`. Se a dona esquecer a senha e não houver outro administrador: `npm run api:redefinir-senha -- <login>` em desenvolvimento, e o passo a passo de produção em [`docs/redefinir-senha-da-dona.md`](docs/redefinir-senha-da-dona.md).

```bash
npm run api:test
```

Roda `./mvnw verify`: compila e executa todos os testes. **O Docker Desktop precisa estar aberto**, porque os testes sobem um PostgreSQL 18.6 de verdade (Testcontainers) com o mesmo script `infra/postgres/init/01-papeis.sh` e os dois usuários do banco. Não precisam do `.env` nem do `npm run db:up`.

Os comandos passam por `scripts/mvnw.mjs`, que escolhe `mvnw.cmd` no Windows e `./mvnw` no Linux; os dois funcionam no PowerShell e no Git Bash. Para rodar o Maven direto, entre em `backend/` e use `./mvnw` (Git Bash) ou `.\mvnw.cmd` (PowerShell). O Maven em si não precisa estar instalado: o wrapper baixa o 3.9.16 na primeira vez.

O `.env` é encontrado tanto rodando de `backend/` quanto da raiz do projeto (por exemplo, pelo VS Code). O perfil `dev` (documentação ligada) só é ativado por `npm run api:run`; ao subir de outra forma, a API funciona igual, mas sem o Swagger. A configuração do VS Code entra na tarefa 17.

Como criar uma tabela nova (convenções do ADR-0002) e como rodar os testes do sync: [backend/README.md](backend/README.md). Restauração de backup sem perder registros na sincronização: [docs/restauracao-de-backup.md](docs/restauracao-de-backup.md).

## CI

A cada Pull Request e a cada push na `main`, o GitHub Actions (`.github/workflows/ci.yml`) roda quatro conferências:

- `frontend`: instala, lint, verificação de tipos, formatação, testes e builds dos apps;
- `backend`: `./mvnw -B verify` com Java 21.0.13 e PostgreSQL de verdade (Testcontainers);
- `migracoes`: só migrações novas, com número maior que o da `main` e sem repetir (só em Pull Request; rode `bash scripts/verificar-migracoes.sh <commit-da-base>` para conferir no seu computador);
- `dependencias`: `npm audit` e OSV-Scanner; reprova com falha de segurança alta ou crítica (nota 7.0 ou mais).

Para ver o resultado, abra o Pull Request e olhe a lista de verificações no fim da página, ou a aba **Actions** do repositório. O Dependabot abre, toda segunda, PRs de atualização agrupados (`.github/dependabot.yml`).

Além do CI, o workflow **Estresse do sync** (`.github/workflows/estresse-sync.yml`) roda toda segunda, às 3h de Brasília, a versão longa (10 minutos) do teste de estresse da sincronização. Para rodar na hora: aba **Actions**, "Estresse do sync", **Run workflow** (dá para mudar a duração e a semente).

**Não se faz merge com CI vermelho.** A `main` não tem proteção técnica no plano gratuito; essa regra é de disciplina.
