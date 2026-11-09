# Progresso

Atualizado pela implementadora ao fim de cada tarefa: o que foi feito, o que ficou pendente e as decisões tomadas.

## Fase 0

### Plano da Fase 0

Lista oficial, aprovada pelo responsável. Os ADRs e as tarefas usam estes números e títulos.

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 1 | docs: registrar na especificação as decisões da Fase 0 (PR 1) | Concluída |
| 2 | docs: glossário da interface (PR 2) | Concluída |
| 2.1 | docs: decisões do responsável, especificação v1.3 e glossário (PR 3) | Concluída |
| 3 | docs: ADRs de fundação | Concluída (PR 4) |
| 4 | chore: esqueleto do monorepo e higiene do repositório (npm workspaces, TS, ESLint, Prettier, .gitignore, .gitattributes, .env.example, modelo de PR, README) | Concluída (PR 5) |
| 5 | chore(infra): Docker Compose com PostgreSQL de desenvolvimento | Concluída (PR 6) |
| 6 | feat(backend): esqueleto Spring Boot 4.1.x com Flyway, OpenAPI e Testcontainers (UTC, erros em português, grupos dona/vendedor no OpenAPI) | Concluída (PR 7) |
| 7 | ci: GitHub Actions e Dependabot | Concluída (PR 8) |
| 8 | feat(backend): convenções do schema e sync_seq seguro (gatilho, pull com limite seguro, utilitário de dinheiro HALF_UP, teste de convenções, testes de concorrência e de estresse) | Concluída (PR 12) |
| 8.1 | docs: três moedas e cotação diária (especificação v1.5, ADR-0006, glossário, CLAUDE.md) | Concluída (PR 13) |
| 9 | feat(auth): usuários, perfis e login com JWT (perfis extensíveis, rotas por perfil, primeiro admin pelo .env) | Em revisão (PR 14) |
| 10 | feat(auth): a dona cria usuários e redefine a senha temporária (troca obrigatória, regras da senha) | Pendente |
| 11 | feat(auth): sessão longa, limite de tentativas e registro de acessos (refresh com rotação, identificador do aparelho, bloqueio 5/15, prazo offline guardado) | Pendente |
| 12 | feat(api): versão mínima e tipo de app (cabeçalhos; o tipo é conveniência, não segurança) | Pendente |
| 13 | feat(api-client): clientes gerados do OpenAPI, um da dona e um do vendedor | Pendente |
| 14 | feat(ui): tema da marca e componentes base em packages/ui | Pendente |
| 15 | feat(owner-app): Corona Gestão com login e tela inicial | Pendente |
| 16 | feat(seller-app): Corona Vendas com login online e barreira contra código da dona | Pendente |
| 17 | chore: VS Code, comando único de setup e roteiro do teste de uso | Pendente |
| 18 | spike: SQLCipher no Electron dentro do monorepo (tempo limitado, gera o ADR-0005) | Pendente |
| 19 | feat(backend): cotações do Banco Central (tabela `cotacoes`, busca diária, conversão para Dólar e Guarani, aviso de falha; ADR-0006). Antes da Fase 1. Sem rota nem tela, por isso não depende das tarefas 9 a 18 | Pendente |

### Tarefa 1: especificação v1.1 com as decisões da Fase 0

**O que foi feito**
- `docs/ESPECIFICACAO.md` atualizado para a versão 1.1 (08/11/2026) com as 11 mudanças da tarefa: perfis (§3), API (§5), premissa do aparelho por vendedor (§6), arredondamento HALF_UP escala 2 (§7.5 e §13), tempo máximo offline e limite seguro do pull (§9 e §10), segurança (§11), fluxo Git e árvore do repositório (§14), registro de problemas de uso (§19).
- Nova seção 22, "Histórico de mudanças".
- Criado `docs/PROBLEMAS-DE-USO.md` com um modelo de registro.

**O que ficou pendente**
- Nada desta tarefa. Seguem fora do escopo: glossário (tarefa 2), ADRs (tarefa 3), pendências do cliente (§17 e §21).

**Decisões tomadas**
- §3: o texto diz que a API recusa o perfil incompatível com o app no login e que cada rota exige o perfil certo. Não diz "valida o tipo de app por rota", para não contradizer o §11 (o cabeçalho de tipo de app não é segurança). A forma de implementar os perfis fica para o ADR-0004 e a tarefa 9.
- Os termos "issue", "Closes #", "main protegida" e `loja-semijoias` aparecem só na seção 22. Nas seções 14 e 19 o novo fluxo é descrito sem citá-los.
- Os nomes exatos dos cabeçalhos de versão e de tipo de app não foram definidos na especificação.
- A estratégia do limite seguro do pull não foi detalhada; fica no ADR-0002.

### Tarefa 2: glossário da interface e especificação v1.2

**O que foi feito**
- Criado `docs/glossario-interface.md` com as 10 partes pedidas: termos da loja, lista de palavras proibidas em dois blocos com marcadores (A, sem diferenciar maiúsculas; B, só a forma exata em maiúsculas), situações na tela, botões e posição, modelo de mensagem, mensagens padrão da Fase 0, estado de conexão do app do vendedor, formatos, termos a confirmar e dúvidas para a dona.
- `docs/ESPECIFICACAO.md` na versão 1.2 (09/11/2026): §14 passa a dizer que o prefixo da branch é o tipo do Conventional Commits; §22 com a entrada da v1.2. Nenhuma outra linha mudou.

**O que ficou pendente**
- Verificador automático das palavras proibidas (fora de escopo; o glossário só entrega as listas).
- As 12 dúvidas da parte 10 do glossário, que dependem da dona ou do responsável.

**Decisões tomadas**
- "Cancelar" segue a §19.5: o botão genérico é "desistir sem salvar"; cancelar venda, pagamento ou devolução é ação nomeada com o objeto, com confirmação dizendo que nada é apagado. "Anular" não foi adotado e ficou só como dúvida para a dona.
- Posição dos botões (Voltar no alto à esquerda; Cancelar e depois o botão principal no rodapé, à direita) é proposta deste glossário, pois a especificação só pede posição igual.
- Regras da senha nova e `{minutos}` do bloqueio são valores propostos, a fixar na decisão de autenticação.
- Conflito não resolvido na especificação (registrado como dúvida 12, sem alterar a especificação): a §12 usa "Remessas", "Movimentações" e "sincronização" em nomes de relatório e no aviso de dados, o que conflita com §19.1 e §19.11.

### Tarefa 2.1: decisões do responsável (especificação v1.3 e glossário)

**O que foi feito**
- `docs/ESPECIFICACAO.md` na versão 1.3 (09/11/2026): nomes Corona Gestão e Corona Vendas (§18); "leia o código de novo" no lugar de "bipe novamente" (§8); senha nova, senha da própria dona, prazo offline esgotado e bloqueio de 15 minutos após 5 erros (§11); `configuracoes` com tentativas de login e tempo de bloqueio (§9); novo aviso de dados e nota sobre nomes de relatório (§12); pendência 12, "Entrada" (§17); regras de "vendedor", "ler o código", "produto" e "peça" (§19.1); "envios com problema" (§19.3); entrada v1.3 na §22.
- `docs/glossario-interface.md`: seção "Decisões tomadas"; "bipar" e "bipe" no bloco A de palavras proibidas; nomes dos aplicativos nos termos e nas mensagens de aplicativo errado; senha, bloqueio e prazo offline com os valores definitivos; textos de "itens" e "vendas" aguardando envio; "Entrada" como a confirmar; parte 10 só com a dúvida da "Entrada".

**O que ficou pendente**
- Pendência 12 da §17 ("Entrada"): precisa de resposta do cliente antes da Fase 3. A regra de negócio não foi definida.
- Nome de tela de cada relatório: definido na tarefa daquele relatório.

**Decisões tomadas**
- Acréscimo autorizado pelo responsável: a §9 `configuracoes` passou a listar "tentativas de login antes do bloqueio (padrão 5)" e "tempo de bloqueio (padrão 15 minutos)", coerente com a §11.
- A §7, a §8 e a §20 continuam com "bipar" como descrição técnica, como a decisão permite.
- A §12 e a §22 da especificação não alteram nenhuma outra regra.

### Tarefa 3: ADRs de fundação

**O que foi feito**
- Criado `docs/decisoes/` com `README.md` (índice e modelo de ADR) e quatro ADRs, todos com status Proposto, data 09/11/2026:
  - ADR-0001, stack e versões: 16 itens com versão exata, fonte oficial, data da consulta e prazo de suporte (ou "sem data publicada"); regra de atualização (Dependabot; major exige ADR novo; exceção proposta para o Electron).
  - ADR-0002, convenções de dados e `sync_seq` seguro: UUID v7, tipos, arredondamento HALF_UP por valor com exemplos, dinheiro como texto decimal no JSON, campos de controle e quem preenche cada um, tabelas sincronizáveis e só do servidor, exclusão lógica no pull, pull seguro por `(sync_xid, sync_seq)` com prova, procedimento de restauração de backup, transações longas, testes de concorrência e de estresse da tarefa 8, idempotência.
  - ADR-0003, separação entre o Corona Gestão e o Corona Vendas: grupos de rotas, DTOs, clientes gerados, barreira em três camadas, dois builds.
  - ADR-0004, autenticação e sessão: JWT HS256 de 15 minutos, sessão longa com rotação e janela de tolerância, tokens no `safeStorage`, aparelho, prazo offline, BCrypt, tentativas, redefinição da senha da dona, perfis extensíveis, cabeçalhos `X-App-Versao` e `X-App-Tipo`.
- Seção "Plano da Fase 0" acima, com a lista oficial das tarefas 1 a 18.
- O desenho do `sync_seq` foi provado à parte num PostgreSQL 18.6 (pull fora de ordem, savepoint, rollback, lápide e restauração de backup). O script não foi versionado.

**O que ficou pendente**
- Revisão e merge do PR 4 pela orquestradora.
- Dúvidas que continuam abertas: base da comissão (§17.6, ADR-0002); desconto em porcentagem por linha (confirmar na Fase 4); retenção de `sessoes` e `tentativas_login` (ADR futuro).
- ADR-0005 (SQLCipher) fica para a tarefa 18.

**Decisões tomadas**
- UUID v7; arredondamento por valor gravado (total = soma dos valores arredondados); desconto em porcentagem por linha (padrão proposto, a confirmar na Fase 4); comissão descrita nos dois casos, sem escolher a base.
- Dinheiro como texto decimal no JSON e centavos inteiros só para conta no TypeScript.
- `sync_xid` (`xid8`) ao lado do `sync_seq`, cursor com época, reescrita do `sync_xid` na restauração, limites de transação só para o usuário da aplicação.
- JWT HS256 com `kid`, 15 minutos; refresh com SHA-256, 30 dias, rotação, janela de 2 minutos com sucessor derivado; BCrypt custo 12; token restrito na senha temporária; bloqueio 5/15 por login + IP e limite separado de 50 erros por IP; versão mínima na configuração da aplicação; resposta 426 com exceção para a saúde do servidor e a documentação.
- Na primeira entrega, nenhum arquivo da especificação, do glossário ou do CLAUDE.md foi alterado (a especificação mudou na segunda, abaixo).

**Decisões aprovadas pelo responsável (09/11/2026)**
- Os ADRs 0001 a 0004 passam a **Aceito**.
- Custo do produto: `produtos_custos` (só do servidor, 1 para 1 com `produtos`, gravada na mesma transação, nunca sincronizada). ADR-0002 e ADR-0003 atualizados.
- Spring Boot: política "Spring Boot 4, a versão menor mais recente com suporte; versão exata no ADR-0001". Hoje é a 4.1.2; a 4.2 entra pelo Dependabot com o CI verde, sem ADR novo.
- Versões aceitas: TypeScript 6.0.3, @hey-api/openapi-ts 0.99.0 (versão fixa), Flyway 12.4.0, Node 26.13.0 com npm 11.20.0, electron-builder 26.18.0 e electron-updater 6.8.11. Exceção do Electron aceita (versões novas sem ADR novo, sempre entre as 3 com suporte).
- Parâmetros técnicos de autenticação ficam na configuração do servidor, sem tela; `configuracoes` mantém só as chaves da §9.
- Senha temporária vale 7 dias (a tarefa 10 implementa e acrescenta ao glossário a mensagem "senha temporária vencida"); a senha definitiva não expira.
- Muitos erros num mesmo login vindos de IPs diferentes: sem bloqueio; mais de 20 erros em 1 hora geram aviso em "O que precisa da sua atenção" (limite no servidor).
- ADR-0002: a época viaja dentro do cursor `época:xid:seq`; o valor atual fica na tabela do servidor `sync_estado` (escolha da implementadora, porque `configuracoes` só guarda as chaves da §9); na Fase 4 a situação da venda é calculada a partir dos lançamentos, não gravada por cima.
- **Especificação v1.4 (09/11/2026):** cabeçalho; §5 (Spring Boot 4, versão menor mais recente com suporte); §9 (`produtos` sem `preco_custo`, nova `produtos_custos`); §11 (senha temporária de 7 dias, senha definitiva sem expiração, alerta sem bloqueio para muitos erros num login); §22 com a entrada v1.4. Nenhuma outra regra mudou.

### Tarefa 4: esqueleto do monorepo

**O que foi feito**
- npm workspaces na raiz (`apps/*` e `packages/*`) com `@corona/owner-app`, `@corona/seller-app`, `@corona/ui` e `@corona/shared`, cada um com `tsconfig.json` que estende a base e um `src/index.ts` mínimo. `backend/` só com um `README.md`.
- Versões do ADR-0001 fixadas sem `^` nem `~`: TypeScript 6.0.3, typescript-eslint 8.72.1, ESLint 10.14.0, `@eslint/js` 10.0.1, eslint-config-prettier 10.1.8 e Prettier 3.9.10. `.nvmrc` (26.13.0), `engines`, `packageManager` (`npm@11.20.0`) e `package-lock.json` versionado.
- `tsconfig.base.json` estrito: `strict`, `noUncheckedIndexedAccess`, `noImplicitOverride`, `noFallthroughCasesInSwitch`.
- ESLint em formato flat, com verificação de tipos e Prettier sem conflito. Barreira de importação, primeira camada (ADR-0003): o Corona Vendas não importa `apps/owner-app` nem `@corona/api-client-dona`; `packages/*` não importa `apps/*` nem `@corona/api-client-dona`. As mensagens de erro estão em português.
- `.prettierrc.json`, `.prettierignore`, `.editorconfig`, `.gitattributes` (LF; CRLF só para `.bat`, `.cmd` e `.ps1`; binários marcados), `.gitignore` corrigido (`.vscode/` e `build/` deixam de ser ignorados; exceção para `maven-wrapper.jar`; `release/`, logs e arquivos do sistema), `.env.example`, modelo de PR e `README.md`.
- Scripts na raiz: `lint`, `typecheck`, `format` e `format:check`.

**O que ficou pendente**
- Electron, React, Vite, clientes da API, backend, Docker, CI e Dependabot (tarefas 5 a 7 e 13 a 16).
- Conteúdo de `.vscode/` e seção "Comandos" do CLAUDE.md (tarefa 17).
- Vitest, que entra com o primeiro código testável.
- A regra do `../` que sai da pasta do Corona Vendas (ADR-0003, camada 1) fica para a tarefa 16.
- Node 26.13.0 e npm 11.20.0 ainda não estão instalados na máquina de desenvolvimento (Node 24.18.1 e npm 11.16.0): o `npm ci` avisa sobre `engines`, mas funciona. O CI (tarefa 7) usa o 26.13.0.
- `npm audit` acusa 10 avisos de gravidade moderada, em dependências de desenvolvimento do ESLint e do typescript-eslint (por exemplo `minimatch`). Não corrigi com `--force`; ver o Dependabot na tarefa 7.

**Decisões tomadas**
- ESLint 10 (aceito pelo typescript-eslint 8.72.1) e integração com o Prettier só por `eslint-config-prettier`. O formato é conferido por `format:check`, não pelo lint.
- `docs/`, `CLAUDE.md` e `.claude/` ficam fora do Prettier, para esta tarefa não reescrever a documentação.
- `.npmrc` com `save-exact=true`, para que instalações futuras não gravem `^`.
- `moduleResolution` Bundler, pois o `node10` está obsoleto no TypeScript 6.
- `.env.example` segue o ADR-0002 (papéis separados no banco): administrador do container, usuário das migrações e usuário da aplicação; mais `JWT_SECRET` e o primeiro administrador. Os nomes das variáveis foram aprovados pelo responsável.
- `.claude/settings.local.json` entrou no `.gitignore`, para o repositório não depender da regra global de cada máquina.
- `core.autocrlf=true` nesta máquina deixava a documentação em CRLF na pasta de trabalho; o índice já estava em LF e nenhum conteúdo mudou (`git add --renormalize .` não alterou arquivo algum da documentação).

### Tarefa 5: PostgreSQL de desenvolvimento

**O que foi feito**
- `docker-compose.yml` na raiz: serviço `postgres` (`postgres:18.6`), volume nomeado `corona_pg_dev`, healthcheck com `pg_isready`, porta só em `127.0.0.1:${DB_PORT}`, credenciais todas do `.env` (o Compose falha com mensagem em português se faltar alguma). `max_prepared_transactions=0` e `timezone=UTC` explícitos (ADR-0002).
- `infra/postgres/init/01-papeis.sh`: cria o usuário das migrações (dono do schema `corona`) e o usuário da aplicação (sem superusuário, sem CREATE, `transaction_timeout` 60s e `idle_in_transaction_session_timeout` 30s só para ele), `search_path` dos dois no schema `corona`, `REVOKE` do acesso público ao banco e ao `public`, e privilégios padrão (SELECT, INSERT, UPDATE nas tabelas futuras; USAGE e SELECT nas sequências; nada de DELETE nem TRUNCATE).
- Scripts na raiz: `db:up`, `db:down`, `db:reset`. README com a seção "Banco de desenvolvimento".

**O que ficou pendente**
- Container da API, Nginx, HTTPS, backup e restauração (Fase 9; a restauração do ADR-0002 é testada na tarefa 8). Migrações e tabelas (tarefas 6 e 8). CI (tarefa 7).

**Decisões tomadas**
- Schema `corona`: curto, igual ao nome do banco e da marca, e evita usar `public`.
- Script em `.sh` (não `.sql`), para ler as senhas do ambiente; elas entram no SQL por variáveis do psql (`:'var'`), então caracteres especiais não quebram.
- Volume montado em `/var/lib/postgresql` (formato da imagem 18) e com nome fixo; `.env.example` não precisou mudar.
- O script só roda com o volume vazio; mudou o script, rode `npm run db:reset`.
- Nesta máquina a porta 5432 está reservada pelo Windows; os testes usaram `DB_PORT=5433` no `.env` local.

### Tarefa 6: esqueleto do backend

**O que foi feito**
- `backend/` com Maven (`br.com.coronasemijoias:corona-api`, pacote base `br.com.coronasemijoias.api`), Java 21 e Spring Boot 4.1.2. Maven wrapper 3.3.4 em modo só-script (sem `.jar`), apontando para o Maven 3.9.16; `mvnw` versionado com bit de execução. Dependências: web (MVC), validação, JPA, Flyway com `flyway-database-postgresql`, driver PostgreSQL, actuator, springdoc 3.2.0 (única versão fixada à mão) e, nos testes, Testcontainers (PostgreSQL).
- Configuração pelo `.env` da raiz (`spring.config.import` opcional com `../.env` e `./.env`): datasource com `DB_APP_*`, Flyway com `DB_MIGRACAO_*` e schema `corona` sem criar schema. Se faltar o arquivo ou uma variável `DB_*`, um `FailureAnalyzer` explica em português o que fazer.
- `V001__base.sql`: só um `COMMENT ON SCHEMA`. UTC na JVM (`TimeZone.setDefault` e `-Duser.timezone=UTC` no Maven), no Hibernate e no Jackson.
- Dinheiro estrito no JSON (ADR-0002, R6): `BigDecimal` sai como texto com duas casas (`"7.50"`) e só entra como texto no padrão `^-?\d{1,12}(\.\d{1,2})?$`; número JSON, notação científica e mais de 2 casas dão `DADOS_INVALIDOS`. No OpenAPI o `BigDecimal` aparece como `string`, `format: decimal` e o mesmo `pattern`, nos dois grupos.
- Tratador global de erros: `ProblemDetail` com `codigo` (`ROTA_NAO_ENCONTRADA`, `DADOS_INVALIDOS`, `PEDIDO_INVALIDO`, `ERRO_INTERNO`), título e mensagem em português, lista `campos` na validação. Erro interno devolve texto genérico e registra o detalhe só no log. Os textos foram acrescentados antes ao `docs/glossario-interface.md` ("Erros gerais da API").
- Actuator: só `/actuator/health`, sem detalhes e sem a página de descoberta `/actuator`. OpenAPI com os grupos `dona` e `vendedor` (`/api/auth/**` nos dois); Swagger e JSON só no perfil `dev`.
- Testes (26, em `./mvnw verify`): classe base que sobe `postgres:18.6` rodando o mesmo `infra/postgres/init/01-papeis.sh`, com os dois usuários reais (sem `@ServiceConnection`, que entregaria o superusuário). Cobrem Flyway, dono do histórico, `current_user`, fuso `UTC`, `Instant` com `Z`, dinheiro, erros sem nada técnico, health, grupos do OpenAPI nos dois perfis e a mensagem de variável ausente.
- Scripts `api:run` e `api:test` na raiz, via `scripts/mvnw.mjs` (escolhe `mvnw.cmd` ou `./mvnw`). README com a seção "API" e aviso sobre `$` nas senhas (também no `.env.example`). Seção "Comandos" do CLAUDE.md preenchida com os comandos de banco e de API.

**O que ficou pendente**
- Spring Security, login, usuários e JWT (tarefas 9 a 11); tabelas, gatilho de sync e utilitário de dinheiro (tarefa 8); CI (tarefa 7); Dockerfile e deploy (Fase 9).
- Configuração do VS Code (Boot Dashboard com o perfil `dev`): tarefa 17.
- A mensagem de falha de inicialização usa o cabeçalho do Spring ("APPLICATION FAILED TO START"), em inglês; só a descrição e a ação estão em português. É texto de desenvolvedor, não de tela.
- Teste automático das palavras proibidas do glossário contra as mensagens da API: fica para a tarefa que ler os blocos do glossário.

**Decisões tomadas**
- Subpacotes `dona` e `vendedor` dentro de cada funcionalidade (regra de dependência do ADR-0003), registrados em `backend/README.md`.
- O perfil `dev` é ligado por `spring-boot.run.profiles` no `pom.xml`: só `spring-boot:run` (e `npm run api:run`) o ativam; um jar de produção nunca sobe com Swagger por engano.
- O valor de dinheiro sai sempre com pelo menos duas casas (completa com zeros, sem arredondar); o arredondamento HALF_UP continua só no utilitário da tarefa 8.
- Erros de dado dentro do corpo (`DatabindException`) viram `DADOS_INVALIDOS`; JSON quebrado, método e tipo de conteúdo errados viram `PEDIDO_INVALIDO`.
- `JAVA_HOME` desta máquina aponta para o Temurin 21.0.12.1, enquanto o `java` do PATH é o 21.0.13 do ADR-0001; o build funciona nos dois.

### Tarefa 7: CI e Dependabot

**O que foi feito**
- `.github/workflows/ci.yml` (em `pull_request` e push na `main`; `permissions: contents: read`; `concurrency` que cancela a execução anterior só em PR). Jobs: `frontend` (Node pela `.nvmrc`, cache do npm, lint, typecheck, format:check, test e build com `--if-present`), `backend` (Temurin 21.0.13+9, cache do Maven, `./mvnw -B verify`), `migracoes` (só em PR) e `dependencias` (`npm audit --audit-level=high` e OSV-Scanner com filtro de gravidade).
- `scripts/verificar-migracoes.sh`: reprova migração alterada, renomeada ou apagada, versão nova que não seja maior que a maior da base e versão repetida, com mensagens em português citando a regra do CLAUDE.md.
- Actions de terceiros fixadas pelo SHA do commit, com a versão em comentário.
- Mockito como agente do Java no surefire (`maven-dependency-plugin` `properties` e `-javaagent`), sem o aviso do modo antigo.
- `.github/dependabot.yml`: npm, maven, github-actions e docker-compose; semanal, patch e minor agrupados por ecossistema, major em PR separado, limite de 3 PRs por ecossistema, prefixos `chore(deps)` e `ci(deps)`.
- Seção "CI" no README.

**O que ficou pendente**
- Tempos dos jobs e prova do job `migracoes`: no texto do PR.
- Vulnerabilidades sem nota de gravidade no OSV não reprovam (só aparecem no log).

**Decisões tomadas**
- Limite que reprova: alta ou crítica (CVSS 7.0 ou mais), nos dois verificadores.
- `cancel-in-progress` só em PR, nunca na `main`.
- Versão do Flyway: `V001_1` equivale a 1.1, ou seja, maior que `V001`; por isso a regra de "número maior" usa a ordem de versões do Flyway.


### Tarefa 8: convenções do schema e sync_seq seguro

**O que foi feito**
- `V002__convencoes_do_schema.sql`: sequência global `corona.sync_seq_global`; gatilho `corona.controle_sincronizavel` (preenche `sync_seq`, `sync_xid`, `version`, `created_at`, `updated_at`, preserva `id` e `origem_dispositivo` no update, ignora o que o cliente mandar); gatilho `corona.controle_do_servidor`; recusa de DELETE, TRUNCATE e (nas imutáveis) UPDATE; funções `corona.aplicar_convencoes_sincronizavel(tabela, imutavel)` e `corona.aplicar_convencoes_do_servidor(tabela, editavel)`, que criam as colunas de controle, o `CHECK` de UUID v4 ou v7, os gatilhos e o índice `(sync_xid, sync_seq)`; tabela `sync_estado` (uma linha só, época começando em 1).
- `V003__restauracao_do_sync.sql`: `corona.preparar_sync_apos_restauracao()` (sync_xid = 0 com os gatilhos desligados e época + 1), rodada pelo usuário das migrações. Passo a passo em `docs/restauracao-de-backup.md`.
- Tabelas só de teste em `src/test/resources/db/migration-teste` (`V9001`), ligadas só nos testes; o job `migracoes` do CI não olha essa pasta (conferido: o script conta só V002 e V003).
- Java: `Dinheiro` (arredondar, somar, subtrair, preço da linha, porcentagem, parcelas, estornos proporcionais, comissão por venda e sobre o total; testes escritos antes, com os exemplos do ADR e os casos de borda), com um teste de guarda que reprova `RoundingMode` fora dele; `UuidV7` e `Identificadores`; superclasses `EntidadeSincronizavel` e `EntidadeDoServidor`; `OrigemDoDispositivo`; `RegistroDeTabelas`; `CursorDeSync` (`época:xid:seq`); `ServicoDePull` (sem rota) com `FiltroDoVendedor` como parâmetro.
- Testes (todos no `./mvnw verify`): gatilhos e privilégios, superclasses JPA e edição otimista, pull determinístico com duas conexões, cursor, teste de convenções (com o "teste do teste" que cria tabelas erradas numa transação e desfaz), restauração com um segundo PostgreSQL e estresse de 20 s.
- Workflow `.github/workflows/estresse-sync.yml`: estresse de 10 minutos toda segunda às 3h de Brasília e sob demanda (`workflow_dispatch`, com duração e semente).
- `backend/README.md` ("Como criar uma tabela" e "Testes do sync"), `README.md` (links e o workflow novo) e uma linha no "Comandos" do `CLAUDE.md`.

**O que ficou pendente**
- Rotas `/sync/push` e `/sync/pull`, `sync_log` e as respostas de idempotência (Fase 5). A resposta "cursor inválido" vira rota e mensagem do glossário na Fase 5.
- A regra do filtro por vendedor (Fase 5). Atenção registrada no `FiltroDoVendedor`: uma linha que passa a ser do vendedor sem ser alterada (por exemplo, um produto que entra numa maleta nova) não volta sozinha no pull.
- Chave estrangeira de `origem_dispositivo` para `dispositivos`, quando a tabela existir.
- Validação do id nos DTOs (anotação de Bean Validation): fica para quando existirem DTOs com id; hoje há `Identificadores.aceito` e o `CHECK` no banco.
- Alerta de atraso do pull (idade da transação mais antiga, ADR-0002): não estava nesta tarefa.
- Valor mínimo de parcela é regra de negócio (Fase 4); o utilitário só recusa a divisão que inverteria o sinal da última parcela.
- Usuário do backup de produção e envio para fora da VPS (Fase 9).

**Decisões tomadas**
- As funções `aplicar_convencoes_*` **criam** as colunas de controle (a tabela declara só `id uuid PRIMARY KEY` e as colunas de negócio), para nenhuma tarefa futura errar tipo ou esquecer peça. Elas recusam com mensagem clara tabela sem `id uuid`, com coluna de controle declarada à mão ou, na sincronizável, com linhas.
- As recusas de DELETE, TRUNCATE e UPDATE são gatilhos `BEFORE ... FOR EACH STATEMENT`: disparam antes de qualquer linha e recusam até o comando que não acharia nada. A recusa de TRUNCATE é proteção extra contra o dono da tabela. Consequência para a Fase 5: `INSERT ... ON CONFLICT DO UPDATE` é recusado em tabela imutável; a idempotência usa `ON CONFLICT DO NOTHING` (ADR-0002, item 6).
- `origem_dispositivo` vem de `set_config('corona.origem_dispositivo', ..., true)`, que vale só até o fim da transação (o pool nunca leva o valor para outra requisição); o gatilho ignora o corpo e preserva a origem no update.
- UUID v4 ou v7 também no banco: `CHECK (coalesce(uuid_extract_version(id), 0) IN (4, 7))`.
- `sync_estado`: `id uuid` com `uuidv7()`, coluna `unica` com `CHECK` e `UNIQUE` (uma linha só); a aplicação só lê.
- `flyway_schema_history`: a aplicação perdeu todo acesso (decisão da orquestradora); o `BancoDeDadosTest` passou a ler o histórico com o usuário das migrações.
- Funções novas não ficam executáveis por todos (`ALTER DEFAULT PRIVILEGES ... REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC` para o usuário das migrações).
- **Edição otimista:** `@Version` + `@Generated` (o Java nunca grava a versão). O Hibernate 7.4.13 usa `UPDATE ... RETURNING` para reler os campos do gatilho e, quando a versão já mudou, devolve um erro genérico ("no natively generated values") em vez de `OptimisticLockException`. Por isso a API usa `DialetoPostgreSqlCorona`, igual ao do PostgreSQL mas sem `RETURNING` no UPDATE (a inserção continua com `RETURNING`). O teste `SuperclassesJpaTest` prova o conflito certo.
- **UUID v7 próprio** (cerca de 30 linhas, `SecureRandom`): evita uma dependência a mais para uma função pequena e de especificação curta; testado com o exemplo da RFC 9562.
- **Pull:** transação própria `REPEATABLE READ READ ONLY`, limite `pg_snapshot_xmin` calculado uma vez e usado em todas as tabelas, junção pela ordem `(sync_xid, sync_seq)` e corte no lote.
- **Defeito achado pelo estresse e corrigido:** a primeira versão da consulta dava ao texto do xid o apelido `sync_xid` e ordenava por `sync_xid`; no `ORDER BY` o apelido vale antes da coluna, a ordem virava a do texto ('1000' antes de '999') e o `LIMIT` cortava as linhas erradas, perdendo registros perto do xid 1000. Agora a posição sai como `posicao_xid` e a ordem usa `t.sync_xid, t.sync_seq`; há um teste de regressão determinístico (xid 999 e 1000). O ADR não muda: o desenho estava certo, o erro era da consulta.
- `Dinheiro.somar` recusa valor com mais de duas casas (total é soma de valores já arredondados); `dividirEmParcelas` recusa quando a regra deixaria a última parcela com o sinal invertido (por exemplo 0,18 em 12 vezes); estornos proporcionais recebem todas as devoluções da venda em ordem.
- Estresse curto (20 s): pausas de 0 a 250 ms e transação longa de 2 s, para gerar volume em pouco tempo; o longo (10 min) usa os números do ADR (0 a 2 s e 5 s). Antes de comparar, o teste espera o `xmin` passar de um identificador novo (todas as transações, inclusive as do autovacuum, terminaram).
- O estresse longo é semanal (pedido da tarefa), na madrugada de segunda. Como o `workflow_dispatch` só funciona com o arquivo na `main`, a execução de prova do PR usou um gatilho de `push` temporário neste branch, retirado antes da revisão.
- Restauração: `pg_dump -Fc -n corona` e `pg_restore` com a lista do backup sem a linha `SCHEMA - corona` (o schema já existe no servidor novo, criado pelo `01-papeis.sh`).
- **Correção pedida na revisão do PR 12 (estorno proporcional):** a primeira versão arredondava cada estorno sozinho e o último levava o resto; os erros se acumulavam (comissão de 0,05 devolvida em 10 x 0,10 dava 0,09 estornado e o último -0,04; 22,73 em 30 x 10,10 dava 29 x 0,76 e o último 0,69). Agora o cálculo é pelo acumulado, `arredondar(original x devolvido acumulado / base) - já estornado`, e o ADR-0002 foi alterado antes do código (regra no item 2 e o exemplo das 10 devoluções na tabela). Testes escritos antes: os dois casos e uma propriedade com 5000 casos aleatórios de semente fixa (metade em centavos, onde o erro pesa; com valores só grandes a propriedade não pegava o defeito antigo).
- ADR-0002, item 5: o estresse longo passou a ser descrito como "semanal (segunda às 03:00 de Brasília) e sob demanda pelo botão do workflow". O ADR ganhou uma linha "Alterado em" no cabeçalho, porque o `docs/decisoes/README.md` diz que ADR aceito não se reescreve; a alteração foi pedida pela orquestradora neste PR.
- Revisão do PR 12 (orquestradora): aprovados o dialeto sem `RETURNING` no UPDATE, as recusas por comando e as pausas menores do estresse curto.

### Tarefa 8.1: três moedas e cotação diária (só documentos)

**O que foi feito**
- `docs/ESPECIFICACAO.md` na versão 1.5 (09/11/2026). Mudaram só estas partes:
  - cabeçalho;
  - §7.5: sem execução de pagamentos; três moedas; cotação diária; falha; fim de semana e feriado; acerto grava a cotação;
  - §9: `cotacoes`, e `acertos` com a cotação usada;
  - §10: cotações no que o vendedor recebe, na política de conflito e sem internet;
  - §12: relatórios nas três moedas e data da cotação no aviso do vendedor;
  - §13: conciliações em Real; cotação em `NUMERIC(18,8)`;
  - §15 e §21: fases;
  - §17: pendência 13, pagamento com cartão;
  - §22: entrada v1.5.
- `docs/decisoes/ADR-0006-moedas-e-cotacao.md` (Aceito na revisão do plano). Decide:
  - fonte: o arquivo diário de fechamento do BCB;
  - leitura estrita;
  - calendário, horário em UTC, tentativas e recuperação;
  - tabela `cotacoes` com as quatro colunas de cada moeda;
  - taxa de venda;
  - fórmula;
  - que cotação vale para cada valor;
  - `NUMERIC(18,8)` e a mudança no teste de convenções;
  - JSON e OpenAPI;
  - arredondamento HALF_UP (2 casas no Dólar, 0 no Guarani), também no TypeScript, com `BigInt`;
  - totais como soma dos convertidos;
  - acerto: a cotação mais recente no fechamento, uma só para todos os valores dele, gravada em `cotacao_id`;
  - aviso à dona;
  - proteções;
  - 17 exemplos numéricos com as cotações reais de 30/10, 05/11 e 06/11/2026, inclusive a diferença entre o acerto e o relatório de vendas e o valor máximo em `BigInt`.
  - Índice dos ADRs atualizado.
- `docs/glossario-interface.md`:
  - termo "cotação";
  - `PTAX` no bloco A das palavras proibidas;
  - formatos de Dólar, Guarani (Gs. 1.379.397) e cotação;
  - parte 11, "Três moedas na tela": ordem, destaque, grades e os textos do acerto, do vendedor e do aviso à dona;
  - "Cartão" na parte 9 (a confirmar).
- `CLAUDE.md`: linha nova nas regras inegociáveis.

**O que ficou pendente**
- Tudo o que é código: tarefa 19 (prompt sugerido abaixo), telas da Fase 1, rota do vendedor na Fase 4, pull na Fase 5 e `cotacao_id` no acerto na Fase 7.
- Dúvida do ADR-0006: incluir o aviso da cotação na lista da §19.3.
- Pendência 13 da §17 (cartão), para o cliente.

**Decisões tomadas**
- **Fonte:** a API PTAX (Olinda) não publica o Guarani; em 09/11/2026 só listava 10 moedas. A orquestradora escolheu a alternativa A: o arquivo diário de fechamento do BCB para as duas moedas, o mesmo que a página oficial do PTAX mostra. O PYG vem por paridade com o Dólar.
- **Fases:** a tarefa propunha o envio ao vendedor só na Fase 5. Como o vendedor já vê preços na Fase 4 (online), a §21 põe a cotação pela API do vendedor na Fase 4 e o pull na Fase 5.
- **Ajustes da orquestradora na aprovação do plano (09/11/2026):**
  - ADR-0006 aceito, com a taxa de venda, as Fases 4 e 5 e o número 19;
  - `BigInt` obrigatório no TypeScript, porque centavos × 10⁸ passa de 2⁵³;
  - acerto com a cotação mais recente no fechamento, uma só para todos os valores, e a diferença para o relatório de vendas registrada nas consequências;
  - o cartão sai da §7.5 (entrou por engano) e vira pendência 13 da §17;
  - negativos do Dólar e do Guarani iguais ao do Real;
  - testes de calendário no prompt da tarefa 19.
- **Classificação:** `cotacoes` nasce só no servidor, mas, pelo critério do ADR-0002, é sincronizável só de leitura (chega ao app) e imutável. O ADR-0002 não foi alterado; o ADR-0006 o complementa.
- **Antes da busca do dia,** o lançamento de hoje aparece pela cotação do último dia útil, com a data na tela, e muda para a de hoje quando ela chega. O acerto não muda, porque grava `cotacao_id`.
- **Formato do Guarani:** "Gs." com ponto nos milhares e sem centavos. O símbolo ₲ foi evitado porque pode faltar nas fontes e no PDF.
- **Nenhuma pendência nova na §17:** as dúvidas são do responsável, não do cliente.

### Prompt sugerido para a tarefa 19

> **Recomendação de sessão:** modo Plan, modelo Opus 5.5, esforço alto (dinheiro e dado imutável), conversa nova.

```markdown
# Tarefa 19: cotações do Banco Central (backend, antes da Fase 1)

Leia o `CLAUDE.md`, a `docs/ESPECIFICACAO.md` (v1.5, §7.5, §9, §10, §12 e §13), o ADR-0002 e o ADR-0006 inteiro antes de planejar. Atualize a main e crie a branch `feat/cotacoes-banco-central`. Comece em modo de plano: mostre o plano e espere a aprovação. É parte crítica (dinheiro e dado imutável): escreva os testes primeiro.

## Objetivo
Implementar no backend a regra do ADR-0006: a tabela `cotacoes`, a busca diária no arquivo de fechamento do BCB, a recuperação de dias perdidos, a conversão de Real para Dólar e Guarani e o cálculo do aviso de falha. Sem rota e sem tela.

## Entregas
1. Migração nova (`V004__cotacoes.sql` ou a próxima livre): tabela `cotacoes` como no item 4 do ADR (`data_cotacao date UNIQUE`, 8 colunas `NUMERIC(18,8) NOT NULL`, `buscada_em`, `fonte`), criada com `corona.aplicar_convencoes_sincronizavel('cotacoes', true)`. Registrar no `RegistroDeTabelas` como sincronizável.
2. Teste de convenções: `numeric(18,8)` aceito só nas 8 colunas de `cotacoes` (lista explícita `tabela.coluna`); em outra coluna, a mensagem "numeric(18,8) só em colunas de cotação (ADR-0006)"; caso novo no `ConvencoesDoSchemaTest`.
3. `ConversaoDeMoeda` em `comum.dinheiro`: US$ = R$ ÷ usd_venda com 2 casas, Gs. = R$ ÷ pyg_venda com 0 casas, HALF_UP numa divisão só. Nunca grava. Inclui o valor máximo de `NUMERIC(14,2)`. `ArredondamentoSoNoUtilitarioTest` aceita `RoundingMode` só em `Dinheiro` e em `ConversaoDeMoeda`. Testes com **todos** os exemplos do item 14 do ADR.
4. Leitor estrito do CSV (item 2 do ADR): 8 colunas em toda linha, data igual ao dia pedido, uma linha 220/A/USD e uma 450/A/PYG, números no padrão, maiores que zero, compra ≤ venda, paridade do USD igual a 1. Testes com arquivos de exemplo em `src/test/resources` (sem rede): o arquivo real de 06/11/2026 e um arquivo para cada regra quebrada (coluna a mais, data trocada, PYG ausente, PYG duplicado, ponto decimal, zero, compra > venda).
5. Calendário de feriados bancários nacionais (fixos + Carnaval, Sexta-feira Santa e Corpus Christi pela Páscoa). Testes:
   - 2026: Carnaval 16 e 17/02, Sexta-feira Santa 03/04, Corpus Christi 04/06;
   - 2027: Carnaval 08 e 09/02, Sexta-feira Santa 26/03, Corpus Christi 27/05;
   - Finados numa segunda (02/11/2026) e 20/11/2026 (sexta);
   - a Quarta-feira de Cinzas (18/02/2026) é dia útil.
6. Busca agendada (item 3 do ADR): segunda a sexta, 16:30 a 21:00 UTC a cada 30 min, 20 s por pedido, gravação das duas moedas na mesma transação, recuperação dos últimos 10 dias, dia gravado nunca relido. O relógio e o cliente HTTP entram por parâmetro, para os testes não dependerem da hora nem da internet. Testes: atrasado (404 antes das 21:00 UTC, sem aviso), feriado (404 na última tentativa, sem aviso), falha (404 num dia útil, erro de rede, arquivo fora da regra até o fim), **arquivo existe num dia marcado como feriado: grava**, recuperação, nunca parcial, nunca sobrescreve.
7. Serviço "qual cotação vale" (item 7): maior `data_cotacao` ≤ data do lançamento em Brasília; a mais recente para o que não é lançamento e para o acerto no momento do fechamento. Testes: sábado, 23:30 de sexta em Brasília, Finados.
8. Serviço do aviso (item 12): dias úteis sem cotação nos últimos 10 dias, contando hoje só depois das 21:00 UTC; devolve a data da cotação em uso. Sem rota: a tela e a rota vêm na Fase 1, depois do login.
9. JSON e OpenAPI: tipo próprio para a cotação, texto com 8 casas (`^\d{1,10}\.\d{8}$`), sem passar pelo padrão de dinheiro. Teste de serialização.
10. URL do arquivo e horários na configuração da aplicação (`application.properties`), sem segredo.

## Fora de escopo
Rotas e telas (Fase 1 para a dona, Fase 4 para o vendedor), pull (Fase 5), `cotacao_id` no acerto (Fase 7), TypeScript (a conversão com `BigInt` entra com o primeiro front-end que mostrar valores, com os mesmos exemplos do ADR), mudar o `Dinheiro`.

## Verificação
`npm run api:test` verde; os exemplos do ADR-0006 batem nos testes; nenhum teste depende da internet; o diff das migrações publicadas está vazio (só migração nova).

## Ao terminar
Atualize `docs/PROGRESSO.md` e abra o PR em Conventional Commits (`feat(backend): ...`). No relatório, diga o que fez, o que não fez e as dúvidas.
```

### Tarefa 9: usuários, perfis e login com JWT

**O que foi feito**
- `V004__usuarios_e_perfis.sql`: `perfis` (só do servidor, só inserção, a aplicação só lê; linhas ADMIN e VENDEDOR) e `usuarios` (só do servidor, editável: nome, login único e guardado normalizado, `senha_hash` BCrypt, perfil com chave estrangeira para `perfis.codigo`, `ativo`, `vendedor_id` sem chave estrangeira até a Fase 1, `CHECK` de ADMIN sem vendedor). As duas no `RegistroDeTabelas`; o teste de convenções passa sem exceção nova.
- Biblioteca: `spring-boot-starter-security-oauth2-resource-server` (Spring Security 7.1.2 e Nimbus JOSE 10.9.1, gerenciados pelo Boot 4.1.2; nenhuma versão fixada à mão). O `NimbusJwtEncoder` assina; o `NimbusJwtDecoder` confere com um seletor de chave próprio (só HS256, `kid` obrigatório e igual ao da chave atual ou da anterior), validade sem tolerância de relógio, `iss` e presença de `sub`, `perfil` e `jti`.
- `POST /api/auth/login`: token de 15 minutos com `sub`, `perfil`, `vendedor_id` (só vendedor), `jti`, `iat`, `exp`, `iss` e `kid` no cabeçalho; devolve `tokenDeAcesso`, `tipoDoToken`, `expiraEm`, `validadeEmSegundos` e `horaDoServidor`. `ConteudoDoToken` e `UsuarioAutenticado` já têm `sid`, `dispositivoId` e `escopo`, vazios até as tarefas 10 e 11.
- Senha: BCrypt com custo da configuração (12); conferência aceita custo menor e regrava no login certo; login inexistente confere contra um hash fixo gerado ao subir com o custo configurado; senha com mais de 72 bytes é senha errada (com a mesma passada no BCrypt).
- Respostas: login errado, inexistente e inativo com senha errada dão o mesmo corpo (401 `LOGIN_INVALIDO`); inativo com senha certa, 403 `USUARIO_DESATIVADO`; sem token, vencido ou adulterado, 401 `SESSAO_TERMINADA`; perfil errado, 403 `SEM_PERMISSAO`. Tudo em `ProblemDetail` com `codigo`, pela fábrica comum `Problemas`.
- A cada requisição, o usuário é lido pela chave primária: desativado perde o acesso no próximo uso (401 `USUARIO_DESATIVADO`), e token com perfil ou vendedor diferentes do cadastro é recusado.
- Permissão em duas camadas: o grupo do prefixo no Spring Security (`corona.seguranca.grupos.dona` e `.vendedor`) e a anotação `@ExigePerfil` (controlador ou método), conferida por um interceptador antes de ler o corpo. Rota sem anotação é negada; a lista pública fechada (`RotasPublicas`) tem só o login, `/actuator/health` e a documentação no perfil dev.
- `GET /api/auth/eu`: id, nome, login, perfil e vendedorId, lidos do banco.
- Primeiro administrador (`PrimeiroAdministrador`): roda depois do Flyway e antes de abrir a porta, numa transação com trava; cria só se não houver ADMIN; sem as variáveis (ou com "troque-isto"), não sobe e explica em português; a senha nunca vai para o log.
- Configuração: chaves `JWT_CHAVE_ATUAL(_ID)` e `JWT_CHAVE_ANTERIOR(_ID)` no `.env`; chave ausente, fora do Base64, com menos de 256 bits ou com nome inválido impede a subida, com explicação em português (`ConfiguracaoInvalidaException` e `AnalisadorConfiguracaoInvalida`). `.env.example` com os comandos de geração e o passo a passo da troca de chave. Os testes geram as chaves na hora.
- OpenAPI: esquema `bearer` nos dois grupos, exigido em toda operação menos nas públicas (`security: []` no login).
- Glossário: texto do 403 ("Você não tem acesso a esta parte do sistema.") em "Erros gerais da API", antes do código; nota de que quem monta o "Não foi possível entrar" é sempre o servidor (até a tarefa 12, sem a última frase).
- Login vazio, com espaço no meio ou com caractere de controle depois de normalizado: nem é procurado no banco, passa pela mesma conferência BCrypt (hash fixo) e recebe o mesmo 401 `LOGIN_INVALIDO` da senha errada. Só o campo ausente no corpo é 400.
- O hash da senha nunca sai do servidor: `Usuario.toString()` sem o hash, nenhum DTO com ele e log de SQL sem os valores em todos os perfis (`application.properties`). Um teste confere que a resposta do login, a de `/api/auth/eu` e o log capturado durante o login não têm o hash nem a senha.
- Testes (PostgreSQL real): `LoginTest`, `TokenTest`, `PermissaoTest`, `TodasAsRotasTest` (percorre todos os mapeamentos, inclusive Actuator e documentação no perfil dev), `RotaSemPerfilTest` (prova com uma rota sem anotação), `VerificadorDeRotasTest`, `SubidaDaApiTest` (sobe a API de verdade num PostgreSQL vazio: sem variáveis não sobe; primeira subida cria; segunda não duplica), `ChavesDoTokenTest`, `ConferenciaDeSenhaTest`. Os testes antigos passam a mandar token.
- `README.md` (variáveis novas, o que fazer com a senha inicial depois da primeira subida e como testar no Swagger) e `backend/README.md` ("Como proteger uma rota" e os pacotes novos).

**O que ficou pendente**
- **O comentário do `.env.example` sobre trocar a senha na primeira entrada ainda não vale:** a marca de troca obrigatória é da tarefa 10.
- Senha temporária, regras da senha nova, criação de usuários pela dona, **as regras de formato do login ao criar usuário** e o comando do servidor (tarefa 10); token de renovação, sessões, aparelho, tentativas e bloqueio (tarefa 11); cabeçalhos de versão e tipo de app e a recusa do perfil incompatível com o app (tarefa 12).
- **Tarefa 12:** com o `X-App-Tipo`, o servidor acrescenta ao `LOGIN_INVALIDO` a última frase certa do glossário ("peça uma nova à dona da loja" no Corona Vendas, "fale com o suporte do sistema" no Corona Gestão). Os apps nunca montam texto de erro.
- **Sem limite de tentativas até a tarefa 11:** cada pedido de login custa um BCrypt de custo 12. A API não deve ir para um servidor público antes da tarefa 11.
- Chave estrangeira de `usuarios.vendedor_id` para `vendedores` e o `CHECK` "VENDEDOR tem vendedor_id" (Fase 1; escrito no `COMMENT` da coluna).
- Quem já tem `.env` precisa acrescentar as chaves do token (a antiga `JWT_SECRET` não é mais usada); sem elas, a API não sobe e explica.

**Decisões tomadas**
- Aprovadas pela orquestradora com o plano: texto do 403; o servidor manda o "Não foi possível entrar" sem a última frase até a tarefa 12 (quem completa é o servidor, não o app); as variáveis do primeiro administrador só são exigidas enquanto não houver ADMIN (a senha inicial pode sair do `.env` depois); `vendedor_id` opcional também para VENDEDOR até a Fase 1 (o token só leva o campo quando preenchido); endereço inexistente sem token dá 401 (com token, continua 404).
- Ajustes da orquestradora na aprovação: (A) hash da senha fora de toString, DTO e log; (B) login de formato impossível responde como senha errada, com a mesma conferência BCrypt; (C) o PR registra que o login ainda não tem limite de tentativas.
- A conferência do perfil pela anotação fica num interceptador do Spring MVC, que roda antes de ler o corpo e da validação: o perfil errado nunca recebe 400. O grupo do prefixo fica no Spring Security; as duas camadas valem juntas, então o método só restringe.
- Os perfis aceitos em cada grupo ficam na configuração (`corona.seguranca.grupos.*`): perfil novo é uma linha na migração, a anotação e, se ele entrar num grupo, um valor na configuração. Nenhuma classe muda.
- A página de erro do servidor (`/error`) só atende o despacho de erro do contêiner; a chamada direta é negada (401 sem token, 403 com token). É a única rota que o verificador ignora.
- O login ignora um token velho no cabeçalho (um token vencido não impede de entrar).
- `perfis` usa a convenção de histórico (`editavel => false`, recusa UPDATE): perfil só nasce por migração.
- `ConferenciaDeSenha` fica em `usuarios` e lê o custo direto da configuração, para não criar dependência circular entre `usuarios` e `seguranca`.
- **Correção pedida na revisão do PR 14:** o primeiro administrador recusa `ADMIN_LOGIN` com espaço no meio ou caractere invisível (a mesma regra `Logins.podeExistir` do login). Antes, "Maria Silva" virava o login "maria silva", que nunca entraria; como as variáveis deixam de ser lidas depois que existe um ADMIN, só daria para consertar no banco. Agora a API não sobe, explica em português e não cria nenhum usuário (`SubidaDaApiTest`).


### Tarefa 10: a dona cria usuários e redefine a senha temporária

**O que foi feito**
- Glossário antes do código (parte 6): senha temporária vencida, senha com mais de 72 bytes, troca de senha obrigatória (resposta do servidor) e os erros do cadastro de usuário (login usado, formato do login, usuário não encontrado, a dona não redefine a própria senha). O ADR-0004 não foi alterado (ele já deixava para a tarefa 10 o nome do comando e o texto dos 72 bytes): o nome `redefinir-senha-admin` está no glossário, em `docs/redefinir-senha-da-dona.md` e aqui. Na tela, o campo do login se chama "Usuário".
- `V005__senha_temporaria.sql` (em `usuarios`): `troca_de_senha_obrigatoria`, `senha_temporaria_vence_em` (nulo com troca obrigatória = sem vencimento) e `credencial_alterada_em`, com `CHECK` do vencimento e `COMMENT` nas colunas.
- `POST /api/dona/usuarios` (ADMIN, 201): nome, login, perfil (validado contra a tabela `perfis`) e `vendedorId` opcional (ADMIN com vendedor = 400). Senha temporária de 12 caracteres (alfabeto de 56 sem `0 O o 1 l I`, `SecureRandom`, sorteio uniforme, sempre dentro das `RegrasDaSenha`), devolvida só na resposta (`Cache-Control: no-store`), gravada só como hash e fora do log. Vale 7 dias (`corona.seguranca.senha-temporaria.validade=P7D`). Formato do login ao criar: normaliza (tira os espaços das pontas e passa para minúsculas) e exige `^[a-z0-9][a-z0-9.]{1,38}[a-z0-9]$` (3 a 40 caracteres: letras sem acento, números e ponto, começando e terminando com letra ou número); usuário repetido = 409 `LOGIN_JA_USADO` ("Este usuário já existe.") (confere antes e também captura a violação de unicidade); fora do formato = 400 `LOGIN_FORA_DO_FORMATO` ("Este usuário não pode ser usado."). Logins que já existem (como o do `.env`) não são afetados.
- `POST /api/dona/usuarios/{id}/redefinir-senha` (ADMIN): outra temporária com nova contagem de 7 dias, marca a troca e grava `credencial_alterada_em`. Usuário inexistente = 404 `USUARIO_NAO_ENCONTRADO` ("Não encontramos esta pessoa no cadastro."); a dona não redefine a própria senha por esta rota (409 `NAO_REDEFINIR_A_PROPRIA_SENHA`).
- Login com senha temporária certa: dentro do prazo devolve só o token restrito (`escopo=TROCA_SENHA`, 15 min, sem `sid`) e `trocaDeSenhaObrigatoria: true` na resposta; vencida (`agora >= vence_em`, ou seja, no dia 8) = 401 `SENHA_TEMPORARIA_VENCIDA`, sem token, só depois de a senha conferir. Senha errada continua `LOGIN_INVALIDO`.
- `FiltroDoTokenRestrito` (logo depois do filtro do token): o token restrito só abre `ROTAS_PERMITIDAS` (hoje `POST /api/auth/trocar-senha`); qualquer outra requisição, até de endereço que não existe, recebe 403 `TROCA_DE_SENHA_OBRIGATORIA`. `TodasAsRotasTest` chama TODAS as rotas registradas com o token restrito e confere o 403.
- `POST /api/auth/trocar-senha`: só com o token restrito (token normal = 403 `SEM_PERMISSAO`). Ordem das regras: mais de 72 bytes (`SENHA_LONGA_DEMAIS`), fraca (`SENHA_FRACA`: menos de 8 caracteres, sem letra ou sem número), igual à temporária (`SENHA_IGUAL_A_TEMPORARIA`, conferido com o BCrypt do hash atual), todos 400. Ao trocar: tira a marca e o vencimento, grava `credencial_alterada_em` e devolve o token normal. A troca voluntária ficou de fora.
- Tokens anteriores param de valer: `ConversorDoToken` recusa (401 `SESSAO_TERMINADA`) o token com `iat` anterior a `credencial_alterada_em` (comparado em segundos inteiros). Também recusa token restrito de quem não deve mais trocar a senha e token normal de quem ainda deve.
- Primeiro administrador: nasce com troca obrigatória e **sem vencimento**. O comentário do `.env.example` passa a valer.
- Comando do servidor `redefinir-senha-admin`: o mesmo jar, sem servidor web (não ocupa a porta da API, e `SubidaDaApiTest` prova isso com a porta ocupada). `java -jar corona-api.jar redefinir-senha-admin --login=<login>`; sem `--login` pergunta no terminal. Só administrador ativo; não há `ApplicationRunner`: o comando só age quando a classe de entrada o chama (`CoronaApiApplication.main` com o primeiro argumento `redefinir-senha-admin`), então a subida normal da API não muda. Em modo comando a aplicação sobe inteira sem o servidor web: aplica as migrações pendentes (por isso, em produção, usa o mesmo jar da API) e o `PrimeiroAdministrador` roda como em qualquer subida (sem ADMIN no banco, exige `ADMIN_*` e cria o primeiro; com ADMIN, não faz nada). Mostra a senha uma vez no terminal (não pelo log), marca a troca, grava `credencial_alterada_em` e registra a ação no log sem a senha. Em desenvolvimento: `npm run api:redefinir-senha -- <login>`. Passo a passo em `docs/redefinir-senha-da-dona.md`.
- Testes: `RegrasDaSenhaTest`, `GeradorDeSenhaTemporariaTest` (1000 senhas), `LoginsTest`, `UsuarioTest` (prazo com relógio fixo: vale até o fim do 7º dia e vence no 8º), `UsuariosDaDonaTest`, `SenhaTemporariaTest` (token restrito, cada regra da senha, vencimento no dia 8, redefinição que invalida senha e tokens, primeiro administrador), `ComandoRedefinirSenhaDeAdministradorTest` e o caso novo do `SubidaDaApiTest`. `RelogioDeTeste` (hora real + deslocamento, `@Primary`) deixa os testes avançarem dias sem criar contexto novo.

**O que ficou pendente**
- Limpar o bloqueio por tentativas no comando do servidor (a tabela e o bloqueio são da tarefa 11) e gravar a ação na auditoria "pelo servidor" (Fase 1). Está escrito em `docs/redefinir-senha-da-dona.md`.
- **Tarefa 11:** fechar a janela do mesmo segundo. O `iat` do token e a hora da troca têm precisão de segundos, então um token emitido no mesmo segundo da redefinição, antes dela, ainda vale até vencer (no máximo 15 minutos). As famílias de sessão resolvem.
- A saída (logout) entra em `ROTAS_PERMITIDAS` junto com a tarefa 11.
- **Tarefa 12:** o `SENHA_TEMPORARIA_VENCIDA` sai do servidor só com o título e "A senha que você recebeu só vale por alguns dias, e esse prazo acabou.", como o `LOGIN_INVALIDO`. A última frase por aplicativo (Corona Vendas: "Peça uma senha nova à dona da loja."; Corona Gestão: "Peça uma senha nova a outra pessoa administradora ou fale com o suporte do sistema.") está no glossário e é acrescentada pela tarefa 12.
- Telas (Fase 1): a tela da dona mostra a senha uma vez ("Anote esta senha agora. Ela não aparece de novo.", texto já no glossário). Listar, editar e desativar usuários também são da Fase 1.
- O `npm run api:redefinir-senha` e o `docker compose exec api ...` não foram rodados de ponta a ponta contra o banco do Compose (o `.env` de desenvolvimento ainda não tem as chaves do token); o caminho real do comando, sem servidor web, é coberto pelo `SubidaDaApiTest` num PostgreSQL de verdade. O contêiner da API só nasce na Fase 9.

**Decisões tomadas**
- Um relógio só: a emissão do token (`iat`/`exp`), a conferência de validade do `JwtDecoder`, `credencial_alterada_em` e o vencimento da temporária usam o mesmo `Clock` injetado (nos testes, o `RelogioDeTeste`). A recusa é `iat < credencial_alterada_em` truncado para segundos; o teste `tokenDaTrocaDeSenhaNaoEhDerrubadoPelaPropriaTroca` prova que o token devolvido pela troca (mesmo segundo da hora da troca) funciona na chamada seguinte. Os testes de integração não rodam em paralelo (sem configuração de paralelismo no Surefire nem no JUnit), então o deslocamento do relógio compartilhado é seguro; quem avança o relógio o reinicia no `@AfterEach`.
- Token restrito emitido no prazo continua valendo por seus 15 minutos mesmo que a temporária vença nesse intervalo (teste dedicado).
- A hora da troca de credencial vem do relógio da aplicação (injetado), truncada em segundos, nunca do `now()` do banco: assim o token que a própria troca devolve nunca fica "antes" dela e os testes controlam o tempo.
- Perfil validado contra a tabela `perfis` (perfil é dado, ADR-0004 item 9); perfil novo passa a poder ser criado sem mudar classe.
- `FiltroDoTokenRestrito` não é um bean (um filtro-bean seria registrado duas vezes pelo servlet): entra só na cadeia de segurança. A regra é negar tudo, menos a lista.
- Redefinir a senha de usuário desativado é permitido (ele continua sem entrar); o comando do servidor recusa administrador desativado e nunca reativa ninguém.
- "36 caracteres" no texto de `SENHA_LONGA_DEMAIS` é a conta de 72 bytes com letras acentuadas de 2 bytes; a regra do servidor continua sendo 72 bytes.
- Acréscimos ao glossário, além do pedido, aprovados pela orquestradora: `USUARIO_NAO_ENCONTRADO` e `NAO_REDEFINIR_A_PROPRIA_SENHA` (a trava evita a única administradora se trancar do lado de fora).
