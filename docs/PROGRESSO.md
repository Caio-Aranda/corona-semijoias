# Progresso

Atualizado pela implementadora ao fim de cada tarefa: o que foi feito, o que ficou pendente e as decisões tomadas.

## Fase 0

### Plano da Fase 0

Lista oficial, aprovada pelo responsável. Os ADRs e as tarefas usam estes números e títulos.

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 1 | docs: registrar na especificação as decisões da Fase 0 (PR 1) | Concluída · [detalhe](progresso/fase-0.md#tarefa-1-especificação-v11-com-as-decisões-da-fase-0) |
| 2 | docs: glossário da interface (PR 2) | Concluída · [detalhe](progresso/fase-0.md#tarefa-2-glossário-da-interface-e-especificação-v12) |
| 2.1 | docs: decisões do responsável, especificação v1.3 e glossário (PR 3) | Concluída · [detalhe](progresso/fase-0.md#tarefa-21-decisões-do-responsável-especificação-v13-e-glossário) |
| 3 | docs: ADRs de fundação | Concluída (PR 4) · [detalhe](progresso/fase-0.md#tarefa-3-adrs-de-fundação) |
| 4 | chore: esqueleto do monorepo e higiene do repositório (npm workspaces, TS, ESLint, Prettier, .gitignore, .gitattributes, .env.example, modelo de PR, README) | Concluída (PR 5) · [detalhe](progresso/fase-0.md#tarefa-4-esqueleto-do-monorepo) |
| 5 | chore(infra): Docker Compose com PostgreSQL de desenvolvimento | Concluída (PR 6) · [detalhe](progresso/fase-0.md#tarefa-5-postgresql-de-desenvolvimento) |
| 6 | feat(backend): esqueleto Spring Boot 4.1.x com Flyway, OpenAPI e Testcontainers (UTC, erros em português, grupos dona/vendedor no OpenAPI) | Concluída (PR 7) · [detalhe](progresso/fase-0.md#tarefa-6-esqueleto-do-backend) |
| 7 | ci: GitHub Actions e Dependabot | Concluída (PR 8) · [detalhe](progresso/fase-0.md#tarefa-7-ci-e-dependabot) |
| 8 | feat(backend): convenções do schema e sync_seq seguro (gatilho, pull com limite seguro, utilitário de dinheiro HALF_UP, teste de convenções, testes de concorrência e de estresse) | Concluída (PR 12) · [detalhe](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro) |
| 8.1 | docs: três moedas e cotação diária (especificação v1.5, ADR-0006, glossário, CLAUDE.md) | Concluída (PR 13) · [detalhe](progresso/fase-0.md#tarefa-81-três-moedas-e-cotação-diária-só-documentos) |
| 9 | feat(auth): usuários, perfis e login com JWT (perfis extensíveis, rotas por perfil, primeiro admin pelo .env) | Concluída (PR 14) · [detalhe](progresso/fase-0.md#tarefa-9-usuários-perfis-e-login-com-jwt) |
| 10 | feat(auth): a dona cria usuários e redefine a senha temporária (troca obrigatória, regras da senha) | Concluída (PR 15) · [detalhe](progresso/fase-0.md#tarefa-10-a-dona-cria-usuários-e-redefine-a-senha-temporária) |
| 11 | feat(auth): sessão longa, limite de tentativas e registro de acessos (refresh com rotação, identificador do aparelho, bloqueio 5/15, prazo offline guardado) | Concluída (PR 16) · [detalhe](progresso/fase-0.md#tarefa-11-sessão-longa-limite-de-tentativas-e-registro-de-acessos) |
| 12 | feat(api): versão mínima e tipo de app (cabeçalhos; o tipo é conveniência, não segurança) | Concluída (PR 18) · [detalhe](progresso/fase-0.md#tarefa-12-versão-mínima-e-tipo-de-app) |
| 13 | feat(api-client): clientes gerados do OpenAPI, um da dona e um do vendedor | Concluída (PR 19) · [detalhe](progresso/fase-0.md#tarefa-13-clientes-gerados-do-openapi) |
| 14 | feat(ui): tema da marca e componentes base em packages/ui | Pendente |
| 15 | feat(owner-app): Corona Gestão com login e tela inicial | Pendente |
| 16 | feat(seller-app): Corona Vendas com login online e barreira contra código da dona | Pendente |
| 17 | chore: VS Code, comando único de setup e roteiro do teste de uso | Pendente |
| 18 | spike: SQLCipher no Electron dentro do monorepo (tempo limitado, gera o ADR-0005) | Pendente |
| 19 | feat(backend): cotações do Banco Central (tabela `cotacoes`, busca diária, conversão para Dólar e Guarani, aviso de falha; ADR-0006). Antes da Fase 1. Sem rota nem tela, por isso não depende das tarefas 9 a 18 | Pendente |


## Pendências abertas

O que ainda está em aberto nas tarefas concluídas (o que já foi resolvido por tarefa seguinte não entra). Atualize ao fechar cada item.

- Pendências do cliente (§17 e §21 da especificação), entre elas a pendência 12, "Entrada" (resposta do cliente antes da Fase 3). ([tarefa 1](progresso/fase-0.md#tarefa-1-especificação-v11-com-as-decisões-da-fase-0))
- Nome de tela de cada relatório: definido na tarefa daquele relatório. ([tarefa 2.1](progresso/fase-0.md#tarefa-21-decisões-do-responsável-especificação-v13-e-glossário))
- Base da comissão (§17.6), desconto em porcentagem por linha (confirmar na Fase 4) e retenção de `sessoes` e `tentativas_login` (ADR futuro). ([tarefa 3](progresso/fase-0.md#tarefa-3-adrs-de-fundação))
- ADR-0005 (SQLCipher): tarefa 18. ([tarefa 3](progresso/fase-0.md#tarefa-3-adrs-de-fundação))
- A regra do `../` do Corona Vendas (tarefa 16); o Vitest entrou na tarefa 13. ([tarefa 4](progresso/fase-0.md#tarefa-4-esqueleto-do-monorepo))
- Node 26.13.0 e npm 11.20.0 ainda não instalados na máquina de desenvolvimento; `npm audit` com avisos moderados em dependências de desenvolvimento (Dependabot). ([tarefa 4](progresso/fase-0.md#tarefa-4-esqueleto-do-monorepo))
- VS Code (Boot Dashboard com o perfil `dev`) e `.vscode/`: tarefa 17. ([tarefa 6](progresso/fase-0.md#tarefa-6-esqueleto-do-backend))
- Teste automático das palavras proibidas do glossário contra as mensagens da API (tarefa que ler os blocos do glossário). ([tarefa 6](progresso/fase-0.md#tarefa-6-esqueleto-do-backend))
- Vulnerabilidade sem nota de gravidade no OSV não reprova o CI. ([tarefa 7](progresso/fase-0.md#tarefa-7-ci-e-dependabot))
- Rotas `/sync/push` e `/sync/pull`, `sync_log`, idempotência, cursor inválido e filtro por vendedor (Fase 5). ([tarefa 8](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro))
- Chave estrangeira de `origem_dispositivo` para `dispositivos`; validação do id nos DTOs; alerta de atraso do pull. ([tarefa 8](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro))
- Valor mínimo de parcela (Fase 4); backup de produção e envio para fora da VPS (Fase 9). ([tarefa 8](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro))
- Tarefa 19 (prompt abaixo); telas da Fase 1; rota do vendedor na Fase 4; pull na Fase 5; `cotacao_id` no acerto na Fase 7. ([tarefa 8.1](progresso/fase-0.md#tarefa-81-três-moedas-e-cotação-diária-só-documentos))
- Dúvida do ADR-0006: incluir o aviso da cotação na lista da §19.3; pendência 13 da §17 (cartão), para o cliente. ([tarefa 8.1](progresso/fase-0.md#tarefa-81-três-moedas-e-cotação-diária-só-documentos))
- Chave estrangeira de `usuarios.vendedor_id` para `vendedores` e `CHECK` "VENDEDOR tem vendedor_id" (Fase 1). ([tarefa 9](progresso/fase-0.md#tarefa-9-usuários-perfis-e-login-com-jwt))
- Fase 1: auditoria "pelo servidor" da ação do comando; telas (senha mostrada uma vez, listar, editar e desativar usuários). ([tarefa 10](progresso/fase-0.md#tarefa-10-a-dona-cria-usuários-e-redefine-a-senha-temporária))
- `npm run api:redefinir-senha` e `docker compose exec api ...` não rodados de ponta a ponta (contêiner só na Fase 9). ([tarefa 10](progresso/fase-0.md#tarefa-10-a-dona-cria-usuários-e-redefine-a-senha-temporária))
- Mais de uma instância da API pede um ADR (a trava por IP fica na memória). ([tarefa 11](progresso/fase-0.md#tarefa-11-sessão-longa-limite-de-tentativas-e-registro-de-acessos))
- Tarefas 15 e 16: o processo principal do Electron renova uma vez por vez. ([tarefa 11](progresso/fase-0.md#tarefa-11-sessão-longa-limite-de-tentativas-e-registro-de-acessos))
- Fase 1: desativar usuário revoga as famílias. Fase 5: tabela `dispositivos`, segundo aparelho e prazo offline. ([tarefa 11](progresso/fase-0.md#tarefa-11-sessão-longa-limite-de-tentativas-e-registro-de-acessos))

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
