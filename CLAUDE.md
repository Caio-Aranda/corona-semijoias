# Corona Semijoias

Sistema de semijoias consignadas: app da dona (online), app do vendedor
(offline-first) e API única. Especificação completa: docs/ESPECIFICACAO.md
(leia as seções citadas no prompt da tarefa, não o documento inteiro; se uma regra mudar, atualize o documento primeiro).

## Stack
Java 21 + Spring Boot 4 + Flyway + PostgreSQL (backend) · Electron + React +
TypeScript (dois apps separados) · SQLite criptografado no app do vendedor.

## Regras inegociáveis
- Dinheiro em NUMERIC(14,2), nunca float. Datas em UTC.
- Tudo é gravado em Real; Dólar e Guarani só aparecem na tela, pela cotação do dia do lançamento (ADR-0006).
- IDs são UUID gerados no cliente. Exclusão lógica (deleted_at), nunca DELETE.
- Vendas, pagamentos e movimentos de maleta/estoque são imutáveis: correção = estorno.
- O servidor recalcula totais, parcelas e comissões; nunca confia no app.
- O app do vendedor nunca importa código da dona e nunca recebe preço de custo
  nem dados de outros vendedores.
- Permissões validadas na API, não só na tela.
- Migrações Flyway publicadas nunca são editadas: criar uma nova.
- Código da peça é texto livre, único (sem diferenciar maiúsculas, sem espaços nas pontas), com tipo LOJA, FORNECEDOR ou EAN13; o dígito verificador só vale para EAN13 com 13 dígitos, e EAN-13 não é obrigatório (ADR-0007).
- Fotos de produto são arquivos (nunca binário no PostgreSQL); toda leitura ou
  pesquisa de produto mostra a foto, e a falta de foto nunca trava a venda.

## Interface
- Quem usa é uma pessoa leiga: linguagem da loja (maleta, acerto, vendedor, cliente),
  sem termos técnicos na tela. Erros em português simples dizendo o que fazer.
- Usar os termos de docs/glossario-interface.md.
- Marca: Corona Semijoias. Dourado só em detalhes e títulos grandes, nunca em texto
  pequeno. Telas de dados com texto escuro sobre fundo claro.

## Como trabalhar
- Plano antes do código nas partes críticas (sync, acerto, financeiro).
- Regras de dinheiro e estoque: escrever os testes primeiro.
- Uma funcionalidade por branch; commits no padrão Conventional Commits.
- Trabalhar a partir do prompt de tarefa entregue pela orquestradora; ao terminar, atualizar docs/PROGRESSO.md e abrir um Pull Request.
- Ao terminar, informar: o que fez, o que não fez e as dúvidas.
- Nada de segredos no repositório (usar .env, com .env.example versionado).
- Pronto = funcionalidade + testes + relatório correspondente + conciliação passando.

## Comandos
- `npm run setup`: prepara o computador (confere Node, Java 21 e Docker, `npm ci`, cria `.env` sem sobrescrever, sobe o banco).
- `npm run db:up` / `db:down` / `db:reset`: PostgreSQL de desenvolvimento (Docker Compose).
- `npm run api:run`: sobe a API (perfil `dev`, Swagger ligado) contra o banco do Compose.
- `npm run api:test`: `./mvnw verify` na pasta `backend/` (precisa do Docker aberto; Testcontainers).
- `npm run api:clientes`: gera os clientes da API a partir do OpenAPI. `npm run api:redefinir-senha -- <login>`: nova senha temporária da dona.
- `npm run owner:dev` / `owner:build`: Corona Gestão. `npm run seller:dev` / `seller:build` / `seller:empacotar` (pacote sem instalador, para a barreira): Corona Vendas.
- `npm run owner:empacotar` / `vendas:empacotar`: instalador de produção (lê `apps/<app>/.env.producao`; ADR-0009). `npm run owner:publicar` / `vendas:publicar`: publica a versão nova na VPS (`apps/<app>/.env.publicar`).
- `npm run barreira:varrer -- <app.asar> packages/shared packages/ui packages/conexao-segura`: confere que o pacote do Corona Vendas não leva código da dona. Precisa do caminho: rode antes `npm run seller:empacotar` e passe `apps/seller-app/release/win-unpacked/resources/app.asar` (no Linux, `linux-unpacked`), como o CI faz.
- `npm run lint`, `typecheck`, `format:check` (e `format`); `npm run setup:test` testa o script de setup; `npm run scripts:test`, os scripts de empacotar e publicar.
- `npm run e2e`: testes de ponta a ponta (Playwright + Electron) contra um banco e uma API de teste isolados (precisa do Docker aberto; veja `testes-e2e/README.md`). Roda à mão ou toda segunda no workflow E2E; não faz parte do CI de cada PR.
- `npm run test:duas-pontas -w @corona/testes-sync`: o motor real do Corona Vendas contra a API real, vários computadores e rede ruim (cenários e estresse curto, 6 a 8 min; precisa do Docker aberto; veja `testes-sync/README.md`). O estresse longo roda pelo workflow `estresse-sync.yml` ou à mão.
- Estresse longo do sync: `./mvnw test -Dtest=EstresseDoPullTest -Dcorona.estresse.duracao=PT10M` na pasta `backend/`.
