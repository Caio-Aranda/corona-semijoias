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
| 1.5 | backend: produtos, `produtos_custos` e `produto_codigos` (regras do código, ADR-0007), rotas da dona | Concluída (PR 31) · [detalhe](progresso/fase-1.md#tarefa-15-produtos-custos-e-códigos-backend) |
| 1.6 | backend: fotos de produto (arquivos, três tamanhos, importação em massa) e relatório de produtos sem foto | Concluída (PR 32) · [detalhe](progresso/fase-1.md#tarefa-16-fotos-de-produto-backend) |
| 1.7 | backend: rota da cotação do dia e do aviso de falha (`AvisoDeCotacao`) | Concluída (PR 33) · [detalhe](progresso/fase-1.md#tarefa-17-rota-da-cotação-do-dia-e-dos-avisos-backend) |
| 1.8 | owner-app: base de navegação e listas, telas de usuários (senha temporária mostrada uma vez), cotação em Real/Dólar/Guarani e aviso em "O que precisa da sua atenção", mitigação das 3 falhas de renovação sem internet | Concluída (PR 34) · [detalhe](progresso/fase-1.md#tarefa-18-base-do-corona-gestão-usuários-cotação-e-sessão-sem-internet-owner-app) |
| 1.9 | owner-app: fornecedores, categorias, praças, semanas, restrições e clientes | Concluída (PR 35) · [detalhe](progresso/fase-1.md#tarefa-19-cadastros-básicos-owner-app) |
| 1.10 | owner-app: vendedores (abas da referência) | Concluída (PR 36) · [detalhe](progresso/fase-1.md#tarefa-110-vendedores-owner-app) |
| 1.11 | owner-app: produtos, fotos, importação em massa e leitura de código | Concluída (PR 37) · [detalhe](progresso/fase-1.md#tarefa-111-produtos-códigos-custo-fotos-importação-em-massa-e-leitor-owner-app) |
| 1.12 | relatórios de cadastro (backend + tela) | Concluída (PR 38) · [detalhe](progresso/fase-1.md#tarefa-112-relatórios-de-cadastro-backend--tela--fecha-a-fase-1) |

| 1.13 | ajustes de fechamento: fotos rápidas (cache em disco), filtros que faltavam nos relatórios de cadastro e resultado das importações | Concluída (PR 39) · [detalhe](progresso/fase-1.md#tarefa-113-ajustes-de-fechamento-da-fase-1) |
| 1.14 | ativo/inativo em todos os cadastros e data de nascimento do cliente (aniversariantes de clientes) | Concluída (PR 40) · [detalhe](progresso/fase-1.md#tarefa-114-ativoinativo-em-todos-os-cadastros-e-data-de-nascimento-do-cliente) |

**Fase 1 concluída** (tarefas 1.1 a 1.14). O próximo passo é a Fase 2.

## Fase 2

### Plano da Fase 2

Estoque e compras (§15). Segue o mesmo formato da Fase 1. O modelo do estoque está no [ADR-0008](decisoes/ADR-0008-estoque-e-movimentacoes.md).

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 2.1 | docs: plano da Fase 2 e ADR do estoque | Mesclada (PR 41) · [detalhe](progresso/fase-2.md#tarefa-21-plano-da-fase-2-e-adr-do-estoque-só-documentos) |
| 2.2 | backend: movimentações de estoque (migração, serviço único que muda `estoque_atual`, estorno, ajuste, rotas da dona, testes de concorrência) | Mesclada (PR 42) · [detalhe](progresso/fase-2.md#tarefa-22-backend-movimentações-de-estoque) |
| 2.3 | backend: compras e itens de compra (entrada de compra por código, idempotência, custo por compra e histórico de custo) | Mesclada (PR 43) · [detalhe](progresso/fase-2.md#tarefa-23-backend-compras) |
| 2.4 | backend: estoque mínimo com aviso e inventário (contagem, divergências, fechamento que gera ajuste) | Mesclada (PR 44) · [detalhe](progresso/fase-2.md#tarefa-24-backend-estoque-minimo-e-contagem-do-estoque) |
| 2.5 | backend: relatórios de estoque e de compras (posição atual, movimentações por período, estoque mínimo, produtos parados, inventário e divergências, compras por fornecedor e período, histórico de custo por produto) com exportação | Mesclada (PR 45) · [detalhe](progresso/fase-2.md#tarefa-25-backend-relatórios-de-estoque-e-de-compras) |
| 2.6 | owner-app: entrada de compra com leitor, cadastro rápido e importação da compra por planilha | Mesclada (PR 46) · [detalhe](progresso/fase-2.md#tarefa-26-corona-gestão-entrada-de-compra) |
| 2.7 | owner-app: movimentações e ajustes, estoque mínimo e inventário por leitor | Mesclada (PR 47) · [detalhe](progresso/fase-2.md#tarefa-27-corona-gestão-estoque-ajustes-e-contagem) |
| 2.8 | owner-app: telas dos relatórios de estoque e compras (filtros, totais, exportação) | Mesclada (PR 48) · [detalhe](progresso/fase-2.md#tarefa-28-corona-gestão-relatórios-de-estoque-e-de-compras-fecha-a-fase-2) |
| 2.9 | fechamento da Fase 2: fechar contagem com peça excluída, rota só do estoque mínimo, aviso de reenvio (200 x 201) na ponte | Concluída (PR 49) · [detalhe](progresso/fase-2.md#tarefa-29-fechamento-da-fase-2) |

**Fora desta fase:** mercadoria em maletas por vendedor (Fase 3) e curva ABC (Fase 8).

**Fase 2 concluída** (tarefas 2.1 a 2.9; a 2.9 foi a revisão de fechamento). Resumo: o estoque tem um único serviço que muda a quantidade (movimentos imutáveis, desfazer lançamento, saldo nunca negativo, conferência automática); as compras entram com custo e histórico; há aviso de estoque mínimo e contagem do estoque por leitor; a dona tem as telas de estoque, entradas e saídas, compras e dez relatórios com exportação em PDF, Excel e CSV. **Segue para a Fase 3:** mercadoria em maletas por vendedor nos relatórios de estoque, uso dos tipos Maleta e Devolução da maleta e a conciliação "estoque + maletas". **Segue para a Fase 8:** curva ABC. Dúvidas abertas: [fase-2.md](progresso/fase-2.md#tarefa-28-corona-gestão-relatórios-de-estoque-e-de-compras-fecha-a-fase-2).

## Fase 9 mínima

### Plano da Fase 9 mínima

Colocar o Corona Gestão no ar para a dona (prazo: sexta, 14/11/2026). É infraestrutura, empacotamento e atualização: não muda regra de negócio, migração publicada, cadastros nem estoque. As decisões estão no [ADR-0009](decisoes/ADR-0009-implantacao-certificado-atualizacao-e-backup.md).

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 9.1 | docs: plano da Fase 9 mínima e ADR-0009 (implantação, certificado fixado, atualização, backup, riscos) | Mesclada (PR 50, junto no PR 57) · [detalhe](progresso/fase-9.md#tarefa-91-plano-e-adr-só-documentos) |
| 9.2 | deploy: Docker Compose de produção (API, PostgreSQL, proxy HTTPS), perfil `prod`, script de instalação do servidor, saúde e aviso de queda | Mesclada (PR 52, junto no PR 57) · [detalhe](progresso/fase-9.md#tarefa-92-implantação-da-api) |
| 9.3 | backup e restauração: `pg_dump` diário, cópia semanal por `rclone`, restauração testada num banco limpo | Mesclada (PR 53, junto no PR 57) · [detalhe](progresso/fase-9.md#tarefa-93-backup-e-restauração) |
| 9.4 | Corona Gestão: instalador `.exe` (NSIS), endereço e certificado fixado como configuração de build, `npm run owner:empacotar` | Mesclada (PR 54, junto no PR 57) · [detalhe](progresso/fase-9.md#tarefa-94-instalador-do-corona-gestão-e-certificado-fixado) |
| 9.5 | Corona Gestão: atualização automática (publicação na VPS, checagem, hash, instalação ao fechar, aviso na tela) | Mesclada (PR 56, junto no PR 57) · [detalhe](progresso/fase-9.md#tarefa-95-atualização-automática) |
| 9.6 | roteiro de entrega (`docs/entrega-corona-gestao.md`) | Mesclada (PR 57) · [detalhe](progresso/fase-9.md#tarefa-96-roteiro-de-entrega) |
| 9.7 | extra: Corona Vendas com instalador e atualização automática reaproveitando o mecanismo (pacote neutro `conexao-segura`) | Mesclada (PR 61) · [detalhe](progresso/fase-9.md#tarefa-97-corona-vendas-instalador-e-atualização-reaproveitáveis-extra) |

**Fora desta fase mínima:** domínio e Let's Encrypt, assinatura do instalador, backup diário fora da VPS, atualização do Corona Vendas (Fase 9 completa).

**Depende do responsável:** contratar a VPS, entregar o IP e a chave SSH, autorizar o Google Drive no `rclone`, escolher o aviso de queda.

**Estado (tarefas 9.1 a 9.7 mescladas; testadas no computador de desenvolvimento).** A pilha de produção, o backup, a restauração, o instalador e a atualização automática foram provados com a pilha de produção rodando localmente (inclusive instalar a versão 1.0.0, publicar a 1.0.1 e ver o app se atualizar sozinho). **Falta, e só dá com a VPS:** rodar `instalar-servidor.sh`, `verificar-portas.sh` e os timers; o **teste de restauração na VPS com produtos reais** (obrigatório antes de dado real); o envio ao Google Drive; a publicação pela rede (`ssh`/`scp`); e a primeira instalação no computador da dona. Lista de conferência: [entrega-corona-gestao.md](entrega-corona-gestao.md#10-antes-de-a-dona-lançar-dado-real-lista-de-conferência).

## Fase 3

### Plano da Fase 3

Maleta (§15). Segue o mesmo formato da Fase 2. O modelo da maleta está no [ADR-0010](decisoes/ADR-0010-maleta-e-remessa.md).

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 3.1 | docs: plano da Fase 3 e ADR-0010 (maleta e remessa) | Mesclada (PR 51) · [detalhe](progresso/fase-3.md#tarefa-31-plano-da-fase-3-e-adr-da-maleta-só-documentos) |
| 3.2 | backend: remessas e itens (migração V021 ou a próxima livre, remessa NOVA/REPOSICAO pelo serviço de estoque, limite e restrição no servidor, preço congelado, idempotência por UUID, rotas só ADMIN) | Mesclada (PR 55) · [detalhe](progresso/fase-3.md#tarefa-32-backend-das-remessas-montar-enviar-limite-restrição-preço-congelado) |
| 3.3 | backend: devolução da maleta à dona, cancelamento/estorno, conciliação "estoque central + maletas" | Mesclada (PR 58) · [detalhe](progresso/fase-3.md#tarefa-33-backend-devolução-da-maleta-cancelamento-e-conciliação) |
| 3.4 | backend: nota de remessa em PDF e relatórios de maleta (remessas por vendedor e semana, saldo por maleta, mercadoria em maletas por vendedor, maletas em aberto/atrasadas, reposições) com exportação | Mesclada (PR 60) · [detalhe](progresso/fase-3.md#tarefa-34-backend-nota-de-remessa-e-relatórios-da-maleta) |
| 3.5 | owner-app: montagem por leitor (foto grande, bloqueios claros) | Mesclada (PR 62) · [detalhe](progresso/fase-3.md#tarefa-35-corona-gestão-montar-a-maleta-por-leitor) |
| 3.6 | owner-app: lista e ficha da maleta, reposição, devolução, cancelamento | Mesclada (PR 63) · [detalhe](progresso/fase-3.md#tarefa-36-corona-gestão-gerir-a-maleta) |
| 3.7 | owner-app: telas dos relatórios de maleta e impressão da nota | Mesclada (PR 64) · [detalhe](progresso/fase-3.md#tarefa-37-corona-gestão-relatórios-da-maleta-e-nota-de-remessa) |
| 3.8 | fechamento da Fase 3 (revisão pós-fase) | Mesclada (PR 65) · [detalhe](progresso/fase-3.md#tarefa-38-fechamento-da-fase-3) |

**Fora desta fase:** conferência pelo vendedor e um dispositivo por maleta (Fase 4); venda, perda e troca (Fases 4 e 6); acerto (Fase 7); conciliação completa com vendas (Fase 8).

**Fase 3 concluída** (tarefas 3.1 a 3.8; a 3.8 foi a revisão de fechamento). Resumo: a dona monta a maleta com o leitor (rascunho não mexe no estoque), envia (baixa o estoque central de uma vez, preço congelado, limite e restrição conferidos no servidor), devolve peças, desfaz devolução, cancela e repõe; a conciliação "estoque da loja + maletas" fecha e aparece na tela Conferência do estoque; há cinco relatórios da maleta e a nota de remessa em PDF. **Segue para a Fase 4:** conferência pelo vendedor, um aparelho por maleta e a leitura da própria maleta pelo vendedor, sem custo. O encerramento da maleta é o acerto (Fase 7): maleta com tudo devolvido continua enviada.

## Extra: testes de ponta a ponta

Playwright com o Electron de verdade (`npm run e2e`, pasta `testes-e2e/`): login da dona com troca obrigatória de senha, cadastro de fornecedor, entrada de compra lendo o código (digitar + Enter) com conferência do estoque e exportação de relatório em CSV. Banco e API de teste isolados (porta 5499 e 8099, projeto `corona-e2e`, sem volume), limpos no fim; nunca o banco de desenvolvimento nem a pilha de produção. **Não está no CI de cada PR** (sobe Docker, API e Electron, vários minutos): o workflow `E2E` roda à mão e toda segunda. O workflow rodou no GitHub pela primeira vez em 11/11/2026, disparado à mão na auditoria (run 43025414672: 4 testes verdes em 1,4 min). Nada do Corona Vendas antes da Fase 4.

## Fase 4

### Plano da Fase 4

App do vendedor online (§15): login, conferência, venda, clientes, consulta, relatórios próprios e três moedas. Funciona **online, mas já preparado para sync** (UUID, vendas imutáveis, idempotência). O modelo está no [ADR-0011](decisoes/ADR-0011-venda-do-vendedor-online.md).

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 4.1 | docs: plano da Fase 4 e ADR-0011 (venda do vendedor) | Mesclada (PR 66) · [detalhe](progresso/fase-4.md#tarefa-41-plano-da-fase-4-e-adr-da-venda-só-documentos) |
| 4.2 | backend (alto): vendas, itens e pagamentos, venda a partir da maleta, preço congelado, desconto, idempotência (200/201/409), estorno, totais no servidor, cotação | Mesclada (PR 67) · [detalhe](progresso/fase-4.md#tarefa-42-backend-das-vendas-vender-estornar-totais-no-servidor) |
| 4.3 | backend: rotas do vendedor (própria maleta sem custo, conferência e dispositivo único, clientes dele, cotação, relatórios próprios), 403 cruzado nos dois sentidos | Mesclada (PR 68, junto no PR 71) · [detalhe](progresso/fase-4.md#tarefa-43-backend-das-rotas-do-vendedor-maleta-conferência-clientes-cotação-relatórios) |
| 4.4 | seller-app (alto): base de telas, conferência por leitor com foto grande, venda, escolha e cadastro de cliente, estorno | Mesclada (PR 69, junto no PR 71) · [detalhe](progresso/fase-4.md#tarefa-44-corona-vendas-conferência-da-maleta-venda-cliente-e-estorno) |
| 4.5 | seller-app: Minha maleta, Meus clientes, Meus números (relatórios próprios) e valores nas três moedas | Mesclada (PR 70, junto no PR 71) · [detalhe](progresso/fase-4.md#tarefa-45-corona-vendas-meus-clientes-e-meus-números) |
| 4.6 | fechamento da Fase 4 (revisão pós-fase) | Mesclada (PR 71) · [detalhe](progresso/fase-4.md#tarefa-46-fechamento-da-fase-4-revisão-pós-fase) |

**Fora desta fase:** SQLite criptografado, outbox e sync (Fase 5); devolução, troca e garantia (Fase 6); acerto, comissão e contas a receber completas (Fase 7).

**Fase 4 concluída** (tarefas 4.1 a 4.6; a 4.6 foi a revisão de fechamento). Resumo: o vendedor entra no Corona Vendas e, com internet, vê a própria maleta (sem custo), confere as peças lendo o código, vende (preço congelado, desconto dentro do limite da dona, à vista ou a prazo), estorna a venda no mesmo dia, cadastra e busca clientes e vê os números dele (vendas, recebimentos e quanto os clientes devem) em Real, Dólar e Guarani. O servidor recalcula tudo, nunca recebe preço do app e liga cada maleta a um aparelho só. **Segue para a Fase 5:** sem internet (SQLite, outbox, sync). Pendências: [fase-4.md](progresso/fase-4.md#tarefa-46-fechamento-da-fase-4-revisão-pós-fase).

## Fase 5

### Plano da Fase 5

App do vendedor sem internet: SQLite criptografado, fila de envios e sincronização. O modelo está no [ADR-0013](decisoes/ADR-0013-sincronizacao-e-offline.md) (Aceito em 11/11/2026); as tarefas, no [plano da Fase 5](progresso/fase-5-plano.md).

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 5.1 | docs: plano da Fase 5 e ADR-0013 (sincronização e offline) | Mesclada (PR 74) · [plano](progresso/fase-5-plano.md) |
| 5.2 | seller-app: banco local criptografado (SQLCipher, chave pela senha e resgate pelo cofre, migrações com cópia, esquema local 1) | Mesclada (PR 97) · [detalhe](progresso/fase-5.md#tarefa-52-banco-local-criptografado-corona-vendas) |
| 5.3 | backend: computadores do vendedor (`dispositivos`, segundo computador, trocar e bloquear, ordem de apagar, vender só depois de conferir) | Mesclada (PR 96) · [detalhe](progresso/fase-5.md#tarefa-53-computadores-do-vendedor-backend) |
| 5.4 | backend: pull do vendedor (regra por tabela, diretório de clientes, expansão, conferência por resumo, pertença garantida no banco) | Mesclada (PR 98) · [detalhe](progresso/fase-5.md#tarefa-54-pull-do-vendedor-backend) |
| 5.5 | backend: push do vendedor (`sync_log` com 90 dias, resultado por operação, hora corrigida, cliente campo a campo, envios com problema) | Mesclada (PR 99) · [detalhe](progresso/fase-5.md#tarefa-55-push-do-vendedor-backend) |
| 5.6 | seller-app: motor de sincronização (aplicador do pull, camada de pendentes, operações locais com a outbox, ciclo envio → recebimento → conferência, agenda, estado na ponte) | Mesclada (PR 102) · [detalhe](progresso/fase-5.md#tarefa-56-motor-de-sincronização-corona-vendas) |
| 5.7 | seller-app: telas no banco local (Vender, Minha maleta, Clientes e Meus números sem internet), indicador de envio, envios não aceitos com "Entendi", cotação com a data, edição de cliente | Mesclada (PR 104) · [detalhe](progresso/fase-5.md#tarefa-57-telas-no-banco-local-corona-vendas) |
| 5.8 | app e backend: prazo offline (10 dias, aviso aos 3), abrir sem internet pela senha, bloqueio das gravações, quarentena (V034) e apagar os dados | Mesclada (PR 105) · [detalhe](progresso/fase-5.md#tarefa-58-prazo-offline-abrir-sem-internet-bloqueio-quarentena-e-apagar) |
| 5.9 | app: fotos sem internet (cache em disco, download separado do envio, `fotos.ver` pelo disco, "Baixando fotos: N de M") | Mesclada (PR 106) · [detalhe](progresso/fase-5.md#tarefa-59-fotos-sem-internet) |
| 5.11 | backend: métricas e alertas do sync (idade da transação mais antiga, duração do pull e do push, defeito da conferência, computador parado, avisos da dona) | Mesclada (PR 101) · [detalhe](progresso/fase-5.md#tarefa-511-métricas-e-alertas-do-sync-backend) |
| 5.10 | owner-app e backend: computadores do vendedor, envios com problema, CPF repetido, vendas de computador bloqueado, valores de cliente trocados e avisos | Mesclada (PR 107) · [detalhe](progresso/fase-5.md#tarefa-510-telas-da-dona) |
| 5.13 | app e backend: atualização com envios aguardando (formato antigo por 90 dias, envio e renovação abaixo da versão mínima, preparo antes de instalar, bancos de exemplo de cada versão) | Mesclada (PR 108) · [detalhe](progresso/fase-5.md#tarefa-513-atualização-com-envios-aguardando-e-migração-entre-versões) |
| 5.12 | testes-sync: duas pontas (o motor do Corona Vendas em Node, SQLCipher de verdade, contra a API de verdade), proxy de rede ruim com semente, 13 cenários do ADR-0013 item 20, estresse curto nos PRs do sync e longo semanal, oráculo e conciliação | Mesclada (PR 112) · [detalhe](progresso/fase-5.md#tarefa-512-duas-pontas-rede-ruim-e-estresse) |
| 5.14 | fechamento da Fase 5: revisão das tarefas, estresse longo, achados da 5.12 (mensagem do 403, regra da venda no computador antigo, comando no `CLAUDE.md`), teste do `X-Conteudo-Sha256`, roteiros do teste sem internet e do instalador, perguntas 26 e 27 | Em revisão (PR 123) · [detalhe](progresso/fase-5.md#tarefa-514-fechamento-da-fase-5) |

**Fase 5 concluída** (tarefas 5.1 a 5.14; a 5.14 foi o fechamento). Resumo: o vendedor vende, estorna, confere a maleta e cadastra cliente **sem internet**, no Corona Vendas, com os dados num banco criptografado no computador dele, protegido pela senha dele (o programa pede a senha ao abrir, também sem internet). Tudo é enviado sozinho quando a internet volta, uma vez só e na ordem; o servidor recalcula tudo e o que não aceitar vai para "Envios com problema" da dona. O computador sem internet por mais de 10 dias para de gravar (aviso aos 3 dias); a dona troca ou bloqueia o computador, e o computador bloqueado guarda as vendas em quarentena até ela aprovar. As fotos da maleta ficam no computador. A atualização do programa espera o envio e o servidor aceita o formato antigo por 90 dias. Testes de duas pontas (o motor real contra a API real, com rede ruim) e estresse longo verde. **Pendências** (com dono, no [relatório](progresso/fase-5.md#pendências-da-fase-5-e-dono-de-cada-uma)): o aviso de pouco espaço na tela e o "computador reiniciou" no servidor (Fase 6); o teste com um vendedor real sem internet ([roteiro](roteiro-teste-vendedor-sem-internet.md)) e o instalador trocando de versão ([roteiro](roteiro-atualizacao-com-vendas-aguardando.md)), do responsável; o desenho das telas da dona e da edição de cliente, para a dona (perguntas 26 e 27 de [perguntas-para-a-dona.md](perguntas-para-a-dona.md)). As demais tarefas estão no [plano](progresso/fase-5-plano.md).

## Fase 6 (código concluído; falta o teste de uso do responsável)

Devolução, troca e garantia. O modelo está no [ADR-0014](decisoes/ADR-0014-devolucao-troca-e-garantia.md) (Aceito em 12/11/2026); as tarefas e as dúvidas, no [plano da Fase 6](progresso/fase-6-plano.md).

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 6.1 | docs: plano da Fase 6 e ADR-0014 (devolução, troca e garantia) | Mesclada (PR 100) · [plano](progresso/fase-6-plano.md); R1–R6 aceitas em 12/11/2026 |
| 6.2 | backend: garantia e parâmetros (V035, rotas da dona, `parametros` do pull, especificação) | PR 124 · [detalhe](progresso/fase-6.md) |
| 6.3 | backend: devolução do cliente à vista (V036, peça boa e com defeito, reembolso, cancelar devolução, estorno recusado) | PR 125 · [detalhe](progresso/fase-6.md#tarefa-63-devolução-do-cliente-à-vista) |
| 6.4 | backend: autorização da dona, revisão da devolução e antifraude (V037, limite da semana, valor de aprovação, Manter e Não aceitar, taxa de devolução e avisos) | PR 126 · [detalhe](progresso/fase-6.md#tarefa-64-autorização-revisão-e-antifraude) |
| #127 | fix(sync): troca de computador não perde envio (o antigo só apaga com a fila zerada; desligando, nada grava) | PR 128 · [detalhe](progresso/fase-6.md#correção-troca-de-computador) |
| 6.5 | backend: crédito do cliente e troca (V038, troca tudo ou nada e Cancelar troca, crédito em aberto, uso na venda, transferência pela dona, devolução de venda paga com crédito) | PR 129 · [detalhe](progresso/fase-6.md#tarefa-65-crédito-do-cliente-e-troca) |
| 6.7 | sync: push e pull das tabelas da Fase 6 (tipos novos da outbox, VENDA com crédito, crédito sem saldo para revisão na V039, migração local 3 e recarga dirigida; nota de deploy da versão mínima) | PR 131 · [detalhe](progresso/fase-6.md#tarefa-67-push-e-pull-das-tabelas-da-fase-6) |
| 6.8 | seller-app: devolução, troca e crédito no Corona Vendas (cinco operações pela outbox sem valor do app, prévia de valor e prazo, venda com crédito, números locais com devolução e troca, quinto botão e as telas) | PR 134 · [detalhe](progresso/fase-6.md#tarefa-68-corona-vendas-devolução-troca-e-crédito) · desenho não aprovado pela dona (pergunta 29) |
| 6.9 | owner-app: devoluções e créditos no Corona Gestão (Devoluções para autorizar e para revisar, Devoluções com registrar e cancelar, Trocas, Créditos dos clientes, Garantia e limites; a dona registra a devolução, a peça vai à loja) | PR 132 (rascunho) · [detalhe](progresso/fase-6.md#tarefa-69-corona-gestão-devoluções-e-créditos) · desenho não aprovado pela dona (pergunta 28) |
| 6.10 | relatórios de devolução e troca (sete relatórios da dona com exportação e três moedas por lançamento, conferência do banco inteiro com cinco conferências, Meus números do vendedor com devoluções, trocas e crédito, recebimentos sem crédito) | PR 135 · [detalhe](progresso/fase-6.md#tarefa-610-relatórios-de-devolução-e-troca-e-conferências) |
| 6.11 | sync: duas pontas da Fase 6 (máquina do tempo para a venda "antiga", devolução fora do prazo com relógio certo, atrasado e adiantado, troca e crédito sem internet, a dona mexendo no mesmo dinheiro, rede ruim no envio da troca; conferência da Fase 6 em SQL próprio e as da 6.10 na afirmação final) | PR 136 · [detalhe](progresso/fase-6.md#tarefa-611-duas-pontas-da-fase-6) |
| 6.13 | respostas da dona de 13/11: prazos de garantia de 45 dias (migração V040, só muda se ainda estiver em 7 e 30) e, nas telas do cliente (venda, troca, devolução no Corona Vendas e registrar devolução no Corona Gestão), só Dinheiro e Transferência (gravada como PIX; a API e o banco seguem aceitando as quatro e o acerto não muda); glossário "Transferência" | PR 142 · [detalhe](progresso/fase-6.md#tarefa-613-respostas-da-dona-de-1311) |
| 6.12 | docs: fechamento da Fase 6 (revisão, respostas da dona de 13/11, pendências da Fase 7 e do responsável, roteiro do teste de uso; ADR-0014 já Aceito) | PR 143 · [detalhe](progresso/fase-6.md#tarefa-612-fechamento-da-fase-6) |
| #130 | fix(testes-sync): pull "perde" cliente era defeito do teste (conferência do fim em voltas até o servidor parar; semana da dona dentro do limite da API; `sincronizar` sem giro em falso) | PR 133 · [detalhe](progresso/fase-6.md#correção-pull-perde-cliente) |

## Fase 7 (em andamento)

Acerto semanal e financeiro. O modelo está no [ADR-0012](decisoes/ADR-0012-acerto-e-financeiro.md), **aceito pelo responsável em 13/11/2026** com quatro mudanças (não existe entrada, não existe cheque, repasse em dinheiro, transferência ou boleto, comissão por meta de 700.000 Gs com 30% ou 40%); as tarefas, no [plano da Fase 7](progresso/fase-7-plano.md); o detalhe de cada tarefa, em [fase-7.md](progresso/fase-7.md).

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 7.1 | docs: plano da Fase 7 e ADR-0012 (acerto e financeiro) | Mesclada (PR 73) · [plano](progresso/fase-7-plano.md) |
| 7.1b | docs: decisões do responsável de 13/11 no ADR-0012 (Aceito), no plano da Fase 7 e na especificação v1.14 (sem entrada, sem cheque, repasse em dinheiro, transferência ou boleto, comissão por meta, dúvidas da dona respondidas, última migração V040) | PR 159 · [detalhe](progresso/fase-7.md#tarefa-71b-decisões-do-responsável-de-1311) |
| 7.2 | feat: limite de consignação, conferência contra o plano (a V026 e o cadastro já vinham do PR 86) mais o que faltava: testes do preço congelado, da reposição e do "Sem limite", coluna do limite na lista de vendedores, especificação v1.15 e dúvidas 1, 2 e 6 do ADR-0010 resolvidas | PR 160 · [detalhe](progresso/fase-7.md#tarefa-72-limite-de-consignação) |
| 7.3 | feat: contas a receber do cliente (nível 1): parcelas da venda a prazo calculadas pelo servidor, recebimento posterior distribuído por vencimento, estorno do recebimento, estorno da venda cancelando o que está em aberto, leitura para a dona e o vendedor, push e pull das tabelas novas e o motor do Corona Vendas (migração local 4); migração V041, especificação v1.16 | PR 162 · [detalhe](progresso/fase-7.md#tarefa-73-contas-a-receber-do-cliente) |
| 7.4 | feat: acerto, parte 1 (sem dinheiro): iniciar (dona ou vendedor) ligando todas as maletas abertas, travas na venda, estorno, devolução, troca, devolução à dona, cancelamento, conferência e envio de remessa, exceção da venda e do estorno feitos sem internet antes do início, contagem por leituras idempotentes, desistir (só a dona), leitura para a dona e o vendedor, pull de `acertos` e `leituras_acerto` e o motor do Corona Vendas (migração local 6); migração V043, especificação v1.18 | PR 165 · [detalhe](progresso/fase-7.md#tarefa-74-acerto-parte-1-iniciar-travas-contagem-e-desistir) |
| 6.6 | feat: devolução e troca de venda a prazo (abate as parcelas, depois crédito e dinheiro; cancelar desfaz; troca com A a prazo; Corona Vendas migração local 5 e Corona Gestão); migração V042, especificação v1.17 | PR 164 · [detalhe](progresso/fase-6.md#tarefa-66-devolução-e-troca-de-venda-a-prazo) |

## Auditoria geral (check-up) de 11/11/2026

Revisão do que foi feito nas Fases 0 a 4 e na Fase 9 mínima, antes da Fase 5: [relatório](auditoria/2026-11-11-checkup.md) (PR 75). Três achados altos, cada um com PR próprio e teste que falha antes: ordem das travas do estoque (PR 76), segunda trava do saldo da maleta no banco (PR 77) e Dólar e Guarani pela cotação do dia de cada lançamento no Corona Vendas (PR 78). Médios e baixos ficam no relatório como tarefas.

## Leva de correções antes da Fase 5 (11/11/2026)

Um PR por item, a partir do relatório da auditoria: **PR 80** (M7, testes isolados), **PR 81** (M2 e B4, 409 na idempotência), **PR 82** (B7, `CHECK` do sinal na maleta, V025), **PR 83** (M1, nomes únicos no OpenAPI), **PR 84** (D1, "Minha maleta" pela cotação de hoje), **PR 85** (D2, vendedor inativo não vende e inativar desativa o usuário), **PR 86** (limite de consignação obrigatório com "Sem limite", V026) e este de documentos. Antes da Fase 5 ficam só os médios M3, M4, M5 e M6 e os baixos B1, B2, B3 e B6 do relatório.

## Pendências abertas

- Fase 3 (maleta), para a dona ou o responsável: (dúvidas 1, 2 e 6 do ADR-0010 resolvidas pelo responsável em 11/11/2026: limite a preço de venda congelado, reposição dentro do limite, limite obrigatório com "Sem limite" explícito e zero valendo zero; PR 86 e tarefa 7.2); peça repetida na mesma maleta (dúvida 3, hoje uma linha por peça); foto na nota (dúvida 4); prazo de atraso (hoje 7 dias, dúvida 5); formato do número da maleta (hoje S12-V7-1). Com o responsável: leitor de código físico e app instalado ainda não testados. **A Fase 4 herda:** conferência da maleta, dispositivo único por maleta e leitura da própria maleta pelo vendedor sem custo. O ADR-0010 está Aceito. ([tarefa 3.8](progresso/fase-3.md#tarefa-38-fechamento-da-fase-3))

- Fase 2: a dona aceitar a saída recusada quando falta peça (ADR-0008); formato do código da loja no cadastro rápido (ADR-0007, item 5); foto nos arquivos exportados e ordenação por coluna dos relatórios de estoque; leitor de código real e pacote instalado ainda não testados. ([tarefas 2.1 a 2.9](progresso/fase-2.md#tarefa-29-fechamento-da-fase-2))

O que ainda está em aberto nas tarefas concluídas (o que já foi resolvido por tarefa seguinte não entra). Atualize ao fechar cada item.

- Auditoria de 11/11/2026: os achados médios e baixos e as decisões D1 e D2 de [auditoria/2026-11-11-checkup.md](auditoria/2026-11-11-checkup.md) viram tarefas. ([auditoria](auditoria/2026-11-11-checkup.md))

- Pendências do cliente (§17 e §21 da especificação), entre elas a pendência 12, "Entrada" (resposta do cliente antes da Fase 3). ([tarefa 1](progresso/fase-0.md#tarefa-1-especificação-v11-com-as-decisões-da-fase-0))
- Nome de tela de cada relatório: definido na tarefa daquele relatório. ([tarefa 2.1](progresso/fase-0.md#tarefa-21-decisões-do-responsável-especificação-v13-e-glossário)) **Relatórios de cadastro: resolvido na tarefa 1.12 (glossário).** Os das outras áreas seguem a regra.
- Base da comissão (§17.6) e retenção de `sessoes` e `tentativas_login` (ADR futuro). ([tarefa 3](progresso/fase-0.md#tarefa-3-adrs-de-fundação))
- ADR-0005 (SQLCipher): aceito; os itens do spike foram provados na tarefa 5.2 (falta só queda de energia de verdade). ([tarefa 5.2](progresso/fase-5.md#tarefa-52-banco-local-criptografado-corona-vendas))
- `npm audit` com avisos moderados em dependências de desenvolvimento (Dependabot). ([tarefa 4](progresso/fase-0.md#tarefa-4-esqueleto-do-monorepo))
- Teste automático das palavras proibidas do glossário contra as mensagens da API (tarefa que ler os blocos do glossário). ([tarefa 6](progresso/fase-0.md#tarefa-6-esqueleto-do-backend)) **Resolvida na tarefa 1.4.**
- Vulnerabilidade sem nota de gravidade no OSV não reprova o CI. ([tarefa 7](progresso/fase-0.md#tarefa-7-ci-e-dependabot))
- Rotas `/sync/push` e `/sync/pull`, `sync_log`, idempotência, cursor inválido e filtro por vendedor (Fase 5). ([tarefa 8](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro)) **Resolvida na Fase 5 (tarefas 5.4 e 5.5; testes de duas pontas na 5.12).**
- Chave estrangeira de `origem_dispositivo` para `dispositivos`; validação do id nos DTOs; alerta de atraso do pull. ([tarefa 8](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro)) **Resolvida na Fase 5 (5.3 e 5.11).**
- Valor mínimo de parcela (Fase 4); backup de produção e envio para fora da VPS (escritos na Fase 9 mínima, tarefa 9.3; o teste na VPS e o Google Drive dependem do responsável). ([tarefa 8](progresso/fase-0.md#tarefa-8-convenções-do-schema-e-sync_seq-seguro))
- Telas e rota das cotações na Fase 1 (a dona, com o aviso); rota do vendedor na Fase 4; pull na Fase 5; `cotacao_id` no acerto na Fase 7; conversão com `BigInt` no TypeScript quando o primeiro front-end mostrar valores (mesmos exemplos do ADR-0006). ([tarefa 8.1](progresso/fase-0.md#tarefa-81-três-moedas-e-cotação-diária-só-documentos)) **Resolvida na tarefa 1.7 (rota) e 1.8 (tela).**
- Dúvida do ADR-0006: incluir o aviso da cotação na lista da §19.3; pendência 13 da §17 (cartão), para o cliente. ([tarefa 8.1](progresso/fase-0.md#tarefa-81-três-moedas-e-cotação-diária-só-documentos))
- Chave estrangeira de `usuarios.vendedor_id` para `vendedores` e `CHECK` "VENDEDOR tem vendedor_id" (Fase 1). ([tarefa 9](progresso/fase-0.md#tarefa-9-usuários-perfis-e-login-com-jwt)) **Resolvida na tarefa 1.3.**
- Fase 1: auditoria "pelo servidor" da ação do comando; telas (senha mostrada uma vez, listar, editar e desativar usuários). ([tarefa 10](progresso/fase-0.md#tarefa-10-a-dona-cria-usuários-e-redefine-a-senha-temporária)) **Resolvida na tarefa 1.4 (auditoria) e 1.8 (telas).**
- `npm run api:redefinir-senha` e `docker compose exec api ...` não rodados de ponta a ponta (o contêiner existe desde a tarefa 9.2, mas o comando ainda não foi rodado nele; fica para o teste na VPS). ([tarefa 10](progresso/fase-0.md#tarefa-10-a-dona-cria-usuários-e-redefine-a-senha-temporária))
- Mais de uma instância da API pede um ADR (a trava por IP fica na memória). ([tarefa 11](progresso/fase-0.md#tarefa-11-sessão-longa-limite-de-tentativas-e-registro-de-acessos))
- Fase 1: desativar usuário revoga as famílias. Fase 5: tabela `dispositivos`, segundo aparelho e prazo offline. ([tarefa 11](progresso/fase-0.md#tarefa-11-sessão-longa-limite-de-tentativas-e-registro-de-acessos)) **Resolvida na tarefa 1.4 (a parte da Fase 1).**
- Corona Vendas: prazo offline guardado mas ainda não aplicado, login offline e bloqueio do segundo aparelho (Fase 5). **Instalador, ícone, atualização automática e endereço da API no build: resolvidos na tarefa 9.7 (PR 61).** ([tarefa 16](progresso/fase-0.md#tarefa-16-corona-vendas-com-login-online-e-barreira-contra-código-da-dona)) **Resolvido na Fase 5 (5.3 e 5.8): falta só o teste com o vendedor real, pendência 14 do responsável.**
- Corona Vendas: o login que entra, a troca de senha e a tela inicial só foram vistos nos testes, não com conta de vendedor real (fica para o teste de uso, com o vendedor de teste da tarefa 17). ([tarefa 16](progresso/fase-0.md#tarefa-16-corona-vendas-com-login-online-e-barreira-contra-código-da-dona))

- Vendedor de demonstração sem cadastro de vendedor (`vendedor_id` vazio) até a Fase 1. ([tarefa 17](progresso/fase-0.md#tarefa-17-vs-code-setup-e-roteiro-do-teste-de-uso)) **Resolvida na tarefa 1.3.**
- Cotações: o calendário de feriados fica no código (feriado nacional novo exige mudança); a busca roda dentro da API (uma instância só); retenção de `cotacoes` não definida. ([tarefa 19](progresso/fase-0.md#tarefa-19-cotações-do-banco-central))
- Etiquetas: a dona ainda não confirmou o formato (leitura no Bloco de Notas, impressora, preço = custo x 7?); a Fase 1 usa o padrão seguro do ADR-0007, que está Proposto, aguardando o responsável. ([tarefa 1.1](decisoes/ADR-0007-codigos-das-etiquetas.md))
- Cadastros básicos: excluir categoria ou fornecedor em uso só pode ser bloqueado quando o produto existir (1.5); praça e restrição em uso já são bloqueadas (1.3); divisão sincronizável x só do servidor a confirmar. ([tarefa 1.2](progresso/fase-1.md#tarefa-12-cadastros-básicos-backend)) **Categoria e fornecedor em uso por produto: resolvido na tarefa 1.5.**
- Aniversariantes de clientes, situação de cliente e fornecedor e resultado das importações: **resolvidos nas tarefas 1.13 e 1.14**. Em aberto: a dona confirmar que a data de nascimento do cliente vai ao app do vendedor; alinhar "Desativar" para "Inativar" em usuários e produtos. ([tarefa 1.14](progresso/fase-1.md#tarefa-114-ativoinativo-em-todos-os-cadastros-e-data-de-nascimento-do-cliente))
