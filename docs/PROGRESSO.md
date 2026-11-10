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
| 14 | feat(ui): tema da marca e componentes base em packages/ui | Concluída (PR 20) · [detalhe](progresso/fase-0.md#tarefa-14-tema-e-componentes-base) |
| 15 | feat(owner-app): Corona Gestão com login e tela inicial | Concluída (PR 21) · [detalhe](progresso/fase-0.md#tarefa-15-corona-gestão-com-login-e-tela-inicial) |
| 16 | feat(seller-app): Corona Vendas com login online e barreira contra código da dona | Concluída (PR 22) · [detalhe](progresso/fase-0.md#tarefa-16-corona-vendas-com-login-online-e-barreira-contra-código-da-dona) |
| 17 | chore: VS Code, comando único de setup e roteiro do teste de uso | Concluída (PR 23) · [detalhe](progresso/fase-0.md#tarefa-17-vs-code-setup-e-roteiro-do-teste-de-uso) |
| 18 | spike: SQLCipher no Electron dentro do monorepo (tempo limitado, gera o ADR-0005) | Concluída (PR 24) · [detalhe](progresso/fase-0.md#tarefa-18-spike-do-sqlcipher-no-electron) |
| 19 | feat(backend): cotações do Banco Central (tabela `cotacoes`, busca diária, conversão para Dólar e Guarani, aviso de falha; ADR-0006). Antes da Fase 1. Sem rota nem tela, por isso não depende das tarefas 9 a 18 | Concluída (PR 25) · [detalhe](progresso/fase-0.md#tarefa-19-cotações-do-banco-central) |

**Fase 0 concluída** (tarefas 1 a 19). A próxima é a Fase 1.


## Fase 1

### Plano da Fase 1

Cadastros da dona (§15). Segue o mesmo formato da Fase 0. O código das etiquetas está no [ADR-0007](decisoes/ADR-0007-codigos-das-etiquetas.md).

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 1.1 | docs: plano da Fase 1 e códigos das etiquetas | Concluída (PR 27) |
| 1.2 | backend: fornecedores, categorias, praças, semanas, restrições e clientes (migração, regras, rotas da dona, testes) | Concluída (PR 28) · [detalhe](progresso/fase-1.md#tarefa-12-cadastros-básicos-backend) |
| 1.3 | backend: vendedor completo (tabelas filhas, limite, restrição, comissão, praça, documentos como arquivos) + FK `usuarios.vendedor_id` + CHECK "VENDEDOR tem vendedor_id" + vendedor de demonstração com cadastro | Concluída (PR 29) · [detalhe](progresso/fase-1.md#tarefa-13-vendedor-completo-backend) |
| 1.4 | backend: usuários da dona (listar, editar, desativar revogando as famílias de sessão), auditoria "pelo servidor" na redefinição de senha, teste das palavras proibidas do glossário contra as mensagens da API | Concluída (PR 30) · [detalhe](progresso/fase-1.md#tarefa-14-usuários-da-dona-auditoria-e-teste-do-glossário-backend) |
| 1.5 | backend: produtos, `produtos_custos` e `produto_codigos` (regras do código, ADR-0007), rotas da dona | Em revisão · [detalhe](progresso/fase-1.md#tarefa-15-produtos-custos-e-códigos-backend) |
| 1.6 | backend: fotos de produto (arquivos, três tamanhos, importação em massa) e relatório de produtos sem foto | A fazer |
| 1.7 | backend: rota da cotação do dia e do aviso de falha (`AvisoDeCotacao`) | A fazer |
| 1.8 | owner-app: base de navegação e listas, telas de usuários (senha temporária mostrada uma vez), cotação em Real/Dólar/Guarani e aviso em "O que precisa da sua atenção", mitigação das 3 falhas de renovação sem internet | A fazer |
| 1.9 | owner-app: fornecedores, categorias, praças, semanas, restrições e clientes | A fazer |
| 1.10 | owner-app: vendedores (abas da referência) | A fazer |
| 1.11 | owner-app: produtos, fotos, importação em massa e leitura de código | A fazer |
| 1.12 | relatórios de cadastro (backend + tela) | A fazer |

## Pendências abertas

O que ainda está em aberto nas tarefas concluídas (o que já foi resolvido por tarefa seguinte não entra). Atualize ao fechar cada item.

- Pendências do cliente (§17 e §21 da especificação), entre elas a pendência 12, "Entrada" (resposta do cliente antes da Fase 3). ([tarefa 1](progresso/fase-0.md#tarefa-1-especificação-v11-com-as-decisões-da-fase-0))
- Nome de tela de cada relatório: definido na tarefa daquele relatório. ([tarefa 2.1](progresso/fase-0.md#tarefa-21-decisões-do-responsável-especificação-v13-e-glossário))
- Base da comissão (§17.6), desconto em porcentagem por linha (confirmar na Fase 4) e retenção de `sessoes` e `tentativas_login` (ADR futuro). ([tarefa 3](progresso/fase-0.md#tarefa-3-adrs-de-fundação))
- ADR-0005 (SQLCipher) está Proposto, aguardando o responsável; itens da Fase 5: SQLCipher padrão abrir o arquivo, barreira com o módulo integrado, perda da chave e da `outbox`. ([tarefa 18](progresso/fase-0.md#tarefa-18-spike-do-sqlcipher-no-electron))
- `npm audit` com avisos moderados em dependências de desenvolvimento (Dependabot). ([tarefa 4](progresso/fase-0.md#tarefa-4-esqueleto-do-monorepo))
- Teste automático das palavras proibidas do glossário contra as mensagens da API (tarefa que ler os blocos do glossário). ([tarefa 6](progresso/fase-0.md#tarefa-6-esqueleto-do-backend)) **Resolvida na tarefa 1.4.**
- Vulnerabilidade sem nota de gravidade no OSV não reprova o CI. ([tarefa 7](progresso/fase-0.md#tarefa-7-ci-e-dependabot))
- Rotas `/sync/push` e `/sync/pull`, `sync_log`, idempotência, cursor inválido e filtro por vendedor (Fase 5). ([tarefa 8](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro))
- Chave estrangeira de `origem_dispositivo` para `dispositivos`; validação do id nos DTOs; alerta de atraso do pull. ([tarefa 8](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro))
- Valor mínimo de parcela (Fase 4); backup de produção e envio para fora da VPS (Fase 9). ([tarefa 8](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro))
- Telas e rota das cotações na Fase 1 (a dona, com o aviso); rota do vendedor na Fase 4; pull na Fase 5; `cotacao_id` no acerto na Fase 7; conversão com `BigInt` no TypeScript quando o primeiro front-end mostrar valores (mesmos exemplos do ADR-0006). ([tarefa 8.1](progresso/fase-0.md#tarefa-81-três-moedas-e-cotação-diária-só-documentos)) **Resolvida na tarefa 1.7 (rota) e 1.8 (tela).**
- Dúvida do ADR-0006: incluir o aviso da cotação na lista da §19.3; pendência 13 da §17 (cartão), para o cliente. ([tarefa 8.1](progresso/fase-0.md#tarefa-81-três-moedas-e-cotação-diária-só-documentos))
- Chave estrangeira de `usuarios.vendedor_id` para `vendedores` e `CHECK` "VENDEDOR tem vendedor_id" (Fase 1). ([tarefa 9](progresso/fase-0.md#tarefa-9-usuários-perfis-e-login-com-jwt)) **Resolvida na tarefa 1.3.**
- Fase 1: auditoria "pelo servidor" da ação do comando; telas (senha mostrada uma vez, listar, editar e desativar usuários). ([tarefa 10](progresso/fase-0.md#tarefa-10-a-dona-cria-usuários-e-redefine-a-senha-temporária)) **Resolvida na tarefa 1.4 (auditoria) e 1.8 (telas).**
- `npm run api:redefinir-senha` e `docker compose exec api ...` não rodados de ponta a ponta (contêiner só na Fase 9). ([tarefa 10](progresso/fase-0.md#tarefa-10-a-dona-cria-usuários-e-redefine-a-senha-temporária))
- Mais de uma instância da API pede um ADR (a trava por IP fica na memória). ([tarefa 11](progresso/fase-0.md#tarefa-11-sessão-longa-limite-de-tentativas-e-registro-de-acessos))
- Fase 1: desativar usuário revoga as famílias. Fase 5: tabela `dispositivos`, segundo aparelho e prazo offline. ([tarefa 11](progresso/fase-0.md#tarefa-11-sessão-longa-limite-de-tentativas-e-registro-de-acessos)) **Resolvida na tarefa 1.4 (a parte da Fase 1).**
- Corona Vendas: instalador, ícone e atualização automática (Fase 9); prazo offline guardado mas ainda não aplicado, login offline e bloqueio do segundo aparelho (Fase 5). ([tarefa 16](progresso/fase-0.md#tarefa-16-corona-vendas-com-login-online-e-barreira-contra-código-da-dona))
- Corona Vendas: o login que entra, a troca de senha e a tela inicial só foram vistos nos testes, não com conta de vendedor real (fica para o teste de uso, com o vendedor de teste da tarefa 17). ([tarefa 16](progresso/fase-0.md#tarefa-16-corona-vendas-com-login-online-e-barreira-contra-código-da-dona))

- Vendedor de demonstração sem cadastro de vendedor (`vendedor_id` vazio) até a Fase 1. ([tarefa 17](progresso/fase-0.md#tarefa-17-vs-code-setup-e-roteiro-do-teste-de-uso)) **Resolvida na tarefa 1.3.**
- Cotações: o calendário de feriados fica no código (feriado nacional novo exige mudança); a busca roda dentro da API (uma instância só); retenção de `cotacoes` não definida. ([tarefa 19](progresso/fase-0.md#tarefa-19-cotações-do-banco-central))
- Etiquetas: a dona ainda não confirmou o formato (leitura no Bloco de Notas, impressora, preço = custo x 7?); a Fase 1 usa o padrão seguro do ADR-0007, que está Proposto, aguardando o responsável. ([tarefa 1.1](decisoes/ADR-0007-codigos-das-etiquetas.md))
- Cadastros básicos: excluir categoria ou fornecedor em uso só pode ser bloqueado quando o produto existir (1.5); praça e restrição em uso já são bloqueadas (1.3); divisão sincronizável x só do servidor a confirmar. ([tarefa 1.2](progresso/fase-1.md#tarefa-12-cadastros-básicos-backend)) **Categoria e fornecedor em uso por produto: resolvido na tarefa 1.5.**
