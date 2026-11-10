# Detalhe das tarefas da Fase 2

Uma seção por tarefa, no mesmo formato de [fase-1.md](fase-1.md). A tabela de acompanhamento está em [PROGRESSO.md](../PROGRESSO.md). O modelo do estoque está no [ADR-0008](../decisoes/ADR-0008-estoque-e-movimentacoes.md).

### Tarefa 2.1: plano da Fase 2 e ADR do estoque (só documentos)

**Em revisão.** Nenhum código. A especificação (v1.7) não mudou: tudo o que foi decidido cabe na §7.6, na §9 e na §13.

**O que foi feito**

- Seção "Fase 2" em `docs/PROGRESSO.md`, com o plano (2.1 a 2.8) e as pendências da Fase 1 que seguem valendo.
- [ADR-0008](../decisoes/ADR-0008-estoque-e-movimentacoes.md), Aceito: modelo de movimentações e saldo, estorno, concorrência, inventário, saldo negativo e reutilização na Fase 3.
- Glossário: termos de tela do estoque e das compras (parte 12).
- Este arquivo, com o escopo de cada tarefa.

**Regras da Fase 2 (valem para todas as tarefas abaixo)**

- **Movimentos são imutáveis.** Corrigir é **estornar**: o estorno é um movimento novo, do mesmo tipo, com sinal oposto, ligado ao original (`estorna_id`). Nunca `UPDATE` de conteúdo nem `DELETE`.
- **`produtos.estoque_atual` só muda pelo serviço de movimentações, na MESMA transação do movimento.** Nada de `UPDATE` direto, nem em importação, compra ou inventário: todos chamam o serviço.
- **Saldo nunca fica negativo.** Saída, avaria, remessa, ajuste ou estorno que deixaria abaixo de zero é recusado ("Não há peças suficientes no estoque"). A dona corrige com um ajuste, que também não passa de zero. Há `CHECK (estoque_atual >= 0)` no banco.
- **O servidor recalcula tudo** (sinal, saldo, totais, custo); o app manda só tipo, produto, quantidade e motivo.
- **Idempotência pelo UUID do cliente:** reenviar a mesma entrada, compra ou inventário não duplica; o mesmo id com conteúdo diferente é recusado.
- **Custo só no app da dona** (`produtos_custos`, nunca sincronizada). Dinheiro em `NUMERIC(14,2)`, datas em UTC, UUID gerado no cliente, exclusão lógica onde a tabela admite (movimentos não são excluídos).
- **Tipos:** `ENTRADA`, `SAIDA`, `AJUSTE`, `REMESSA`, `DEVOLUCAO`, `AVARIA`. `REMESSA` e `DEVOLUCAO` só são usados a partir da Fase 3, mas o tipo existe desde a 2.2.
- **Conciliação:** a soma dos movimentos de cada produto é igual a `estoque_atual`; há teste automático e relatório.
- Estoque, movimentos, compras e inventários são **só do servidor**: o vendedor nunca recebe o estoque central.

**Escopo e o que fica de fora de cada tarefa**

| Nº  | Escopo                                                                                                                                                                                                                                                                                | Fica de fora                                                                                                  |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| 2.2 | Migração (`movimentacoes_estoque`, `CHECK` do saldo, índice único do estorno); serviço único que muda `estoque_atual`; entrada, saída, avaria e ajuste manuais; estorno; rotas da dona em `/api/dona/estoque`; conciliação como teste; testes de concorrência (mesmo produto, vários produtos cruzados, reenvio do mesmo id); confere que `estoque_atual` e `estoque_minimo` não vão no pull | Compras, inventário, relatórios, telas; uso de `REMESSA` e `DEVOLUCAO` (Fase 3)                               |
| 2.3 | `compras` e `itens_compra` (migração); entrada de compra por código (vários itens, uma transação, chama o serviço); idempotência da compra; custo por compra, atualiza `produtos_custos` e guarda o histórico de custo; estorno da compra (estorna os movimentos)                    | Cadastro rápido e planilha na tela (2.6); contas a pagar e pagamento ao fornecedor (Fase 7); custo médio     |
| 2.4 | Aviso de estoque mínimo (lista e contagem para "O que precisa da sua atenção"); inventário (`inventarios`, `itens_inventario`): iniciar com esperado congelado, contagem por leitura, divergências, fechamento que gera `AJUSTE` com motivo                                          | Telas (2.7); inventário por vendedor, isto é, mercadoria em maleta (Fase 3)                                   |
| 2.5 | Relatórios de estoque e de compras: posição atual, movimentações por período, estoque mínimo, produtos parados, inventário e divergências, compras por fornecedor e período, histórico de custo por produto; totais, detalhamento e exportação (PDF, Excel, CSV) no padrão da 1.12; relatório de conciliação | Curva ABC (Fase 8); mercadoria em maletas por vendedor (Fase 3); telas (2.8)                                  |
| 2.6 | Corona Gestão: tela de entrada de compra com leitor, cadastro rápido de produto na hora (código desconhecido), importação da compra por planilha (com resultado, como na 1.11)                                                                                                        | Movimentações, ajuste, inventário (2.7); relatórios (2.8)                                                     |
| 2.7 | Corona Gestão: tela de entradas e saídas do estoque, ajuste e desfazer lançamento, estoque mínimo (aviso e edição), contagem do estoque por leitor                                                                                                                                   | Relatórios (2.8)                                                                                              |
| 2.8 | Corona Gestão: telas dos relatórios de estoque e de compras (filtros, totais, exportação), no padrão da 1.12                                                                                                                                                                        | Qualquer regra nova de estoque                                                                                |

**Fora desta fase**

- **Mercadoria em maletas por vendedor** e o inventário por vendedor: Fase 3 (a maleta passa a existir).
- **Curva ABC:** Fase 8 (precisa de vendas).
- **Conciliação completa** "estoque + maletas = comprado − vendido − perdido": Fase 8.
- Sincronização de estoque com o app do vendedor: não existe (premissa 5 e §4).

**Decisões que tomei**

- **Saldo negativo é recusado**, inclusive em ajuste; a dona lança a compra antes de montar a maleta. A alternativa (permitir e alertar) esconderia erros e quebraria a conciliação (ADR-0008, item 4).
- **Estorno com o mesmo tipo do original** e `estorna_id`, em vez de um sétimo tipo: os seis tipos da §7.6 ficam como estão e o relatório por tipo continua útil.
- **Inventário fecha por diferença sobre o esperado congelado**, então entradas e saídas feitas durante a contagem não são desfeitas.
- **Compra confirmada em uma operação** (sem rascunho): mesma regra de "venda imutável"; correção é estorno da compra. Se a dona quiser salvar uma compra pela metade, vira tarefa nova.
- **Custo do produto passa a ser o da compra mais recente**, com o histórico guardado nos itens de compra; custo médio fica fora.
- **A tela não usa a palavra "movimentação"** (está no bloco A das palavras proibidas do glossário): na tela é "entradas e saídas" ("Histórico do estoque"). Idem "inventário" (tela: "Contagem do estoque") e "estorno" (tela: "Desfazer lançamento").
- **A 2.2 entrega o serviço completo com todos os tipos**, para a 2.3, a 2.4 e a Fase 3 só chamarem; nenhuma delas cria outro caminho para mudar o saldo.

**O que ficou de fora**

- Nada de código, por pedido da tarefa. Nenhuma migração criada.
- O formato do código da loja gerado no cadastro rápido (ADR-0007, item 5) segue a confirmar com a dona; a 2.6 usa o padrão da 1.5.

**Dúvidas**

- A dona aceita que o sistema recuse a saída quando faltar peça no estoque (ela precisa lançar a compra antes)? Se preferir avisar e deixar passar, o ADR-0008 muda antes da 2.2.
- Ao fechar uma contagem, a peça que não foi lida deve virar "zero em estoque" ou ficar só como diferença para a dona decidir? Adotei: só vira zero se ela confirmar.

### Tarefa 2.2: backend, movimentações de estoque

**Em revisão.** Segue o [ADR-0008](../decisoes/ADR-0008-estoque-e-movimentacoes.md); a especificação não mudou.

**O que foi feito**

- Migração **V018**: `movimentacoes_estoque` (histórico imutável: gatilhos recusam `UPDATE` e `DELETE`), com `quantidade` com sinal (nunca zero), `saldo_depois`, origem (`MANUAL`, `AJUSTE_MANUAL`, `COMPRA`, `INVENTARIO`, `MALETA`, `ESTORNO`), motivo, usuário, `ocorrida_em` (UTC) e `estorna_id`. `CHECK` do sinal por tipo (exceto estorno), motivo obrigatório em ajuste e estorno, índice único em `estorna_id` (um movimento só é desfeito uma vez), índices por produto e por data. `CHECK (estoque_atual >= 0)` em `produtos`.
- **`ServicoDeEstoque`**, o único código que faz `UPDATE` em `produtos.estoque_atual`: grava o movimento e o saldo na mesma transação, trava os produtos (`FOR UPDATE`) um a um em ordem de id, recusa saldo negativo (409, "Há N peça(s) de …"), é idempotente pelo UUID (mesmo id e mesmos dados devolve o gravado; dados diferentes, 409) e aceita lotes de vários produtos, tudo ou nada (para a 2.3, a 2.4 e a Fase 3).
- **Estorno**: movimento novo, mesmo tipo, sinal oposto, `estorna_id`, motivo obrigatório; recusa desfazer duas vezes, desfazer um "desfazer" e o que deixaria o saldo negativo. **Ajuste manual** por nova quantidade ou por diferença, com motivo, na auditoria (`ESTOQUE_AJUSTADO`, `MOVIMENTO_ESTORNADO`).
- Rotas `/api/dona/estoque` (só ADMIN): `POST /movimentacoes` (entrada, saída, perda), `POST /ajustes`, `POST /movimentacoes/{id}/estorno`, `GET /movimentacoes` (filtros produto, tipo, origem, usuário, período; paginada), `GET /produtos/{id}` (saldo e histórico) e `GET /conciliacao`. Toda resposta com produto traz `fotoPrincipal`.
- **Conciliação**: `soma(movimentos) = estoque_atual` por produto, em serviço, rota e teste (inclusive provocando a divergência direto no banco).
- `estoque_atual` e `estoque_minimo` **saíram do contrato do pull** de produtos (estavam na lista desde a 1.5); teste de arquitetura garante isso e que só o `ServicoDeEstoque` altera o saldo.
- Testes: concorrência real (entradas e saídas no mesmo produto, saídas disputando saldo curto, lotes com ordens cruzadas, estorno simultâneo, reenvio simultâneo), idempotência, todas as recusas do estorno, imutabilidade, permissão, auditoria e o teste de glossário existente.

**Decisões que tomei**

- **Quantidade com sinal na tabela** (como manda o ADR), embora o prompt falasse em quantidade positiva: o app manda só a quantidade positiva e o servidor põe o sinal.
- **Produto inativo pode ser lançado** (a peça ainda existe na loja); **excluído é 404**. O ADR não dizia.
- **A rota manual aceita só ENTRADA, SAIDA e AVARIA**; `REMESSA` e `DEVOLUCAO` existem no serviço e só serão usados na Fase 3. O ajuste tem rota própria.
- **Ajuste por nova quantidade**: a diferença é calculada com o produto travado; o reenvio com o mesmo id compara o saldo final pedido. Ajuste que não muda nada é recusado.
- **O saldo muda a `version` do produto** (o gatilho de controle é por linha e não dá para pular): uma edição de cadastro aberta na tela pode receber "cadastro alterado por outra pessoa" depois de uma entrada. Aceitável com poucas pessoas; se incomodar, separar o saldo numa tabela própria.
- Período do histórico em datas UTC, `de` e `ate` inclusivos.

**O que ficou de fora**

- Compras (2.3), inventário e aviso de estoque mínimo (2.4), relatórios (2.5), telas (2.6 a 2.8).
- Relatório de conciliação em PDF/Excel/CSV: só a rota JSON e o teste; a exportação vem com a 2.5.

### Tarefa 2.3: backend, compras

**Em revisão.** Segue o [ADR-0008](../decisoes/ADR-0008-estoque-e-movimentacoes.md); a especificação não mudou.

**O que foi feito**

- Migração **V019**: `compras` (só a situação muda, por gatilho; o resto é imutável), `itens_compra` e `historico_custo` (imutáveis; recusam `UPDATE` e `DELETE`). Todas só do servidor: o vendedor nunca recebe compra nem custo (conferido no pull).
- **`ServicoDeCompras`**: uma transação grava a compra e os itens, chama o `ServicoDeEstoque` (ENTRADA, origem COMPRA, produtos em ordem de id), atualiza `produtos_custos` e grava o histórico. O servidor recalcula tudo; o total enviado pelo app é ignorado.
- Cada item vem por `produtoId` OU por `codigo` (busca do ADR-0007: sem diferenciar maiúsculas, sem espaços nas pontas). Código desconhecido ou linha inválida dá erro por linha (`itens[2].codigo`) e nada é gravado.
- **Idempotência**: mesmo UUID e mesmo conteúdo (em qualquer ordem, por produto ou por código) devolve a compra com 200, sem somar de novo; conteúdo diferente, 409. Envios simultâneos do mesmo id são serializados por trava de transação.
- **Desfazer a compra** (`POST /{id}/desfazer`, motivo obrigatório): desfaz cada entrada pelo serviço de estoque; tudo ou nada. Recusa com 409 se já saíram peças ("há só N no estoque") e se já foi desfeita (o mesmo id de pedido devolve 200). Auditoria de compra e de desfazer.
- Rotas só ADMIN em `/api/dona/compras`: criar, ver (itens, fotos, total), listar (período, fornecedor, produto, situação; paginada) e `/produtos/{id}/custos` (custo atual e histórico).
- Textos novos no glossário (parte 12) e em `CodigoErro`, sem palavras proibidas (teste).

**Decisões que tomei**

- **Mesmo produto duas vezes na mesma compra é recusado** (inclusive uma vez por código e outra por produto): evita custos diferentes para a mesma peça na mesma compra; a dona soma numa linha só.
- **Custo vem como texto com até 2 casas** (regra do JSON de dinheiro do projeto): o contrato recusa 3 casas. O serviço ainda arredonda HALF_UP a 2 casas e só então multiplica pela quantidade, para o total de cada item bater com o custo mostrado.
- **Custo do produto = o da compra gravada agora** (a mais recente a ser lançada, não a de data maior). O custo anterior fica em `historico_custo`.
- **Custo ao desfazer**: só volta se esta compra era a mais recente entre as não desfeitas E o custo atual ainda é o dela (se a dona mudou o custo à mão depois, fica como está). Volta para o custo da compra anterior que continua valendo; sem outra, para o custo que o produto tinha antes da primeira compra. O histórico nunca é apagado.
- **Fornecedor é obrigatório**; data da compra não pode passar de amanhã (UTC); até 500 itens por compra.
- Compra desfeita não pode ser reativada: lança-se de novo com outro id.

**O que ficou de fora**

- Cadastro rápido de produto e importação por planilha (2.6); contas a pagar e pagamento ao fornecedor (Fase 7); custo médio; relatórios de compras (2.5); telas.
- Rascunho de compra (a compra é confirmada de uma vez, como decidido na 2.1).

**Dúvidas**

- A dona quer poder ver a compra por quem lançou/filtrar por usuário? Não incluí o filtro.

### Tarefa 2.4: backend, estoque mínimo e contagem do estoque

**Em revisão.** Segue o [ADR-0008](../decisoes/ADR-0008-estoque-e-movimentacoes.md) (itens 8, 9 e 12); a especificação não mudou.

**O que foi feito**

- **Estoque mínimo:** `GET /api/dona/estoque/abaixo-do-minimo` (só ADMIN) lista os produtos ativos com `estoque_minimo > 0` e saldo no mínimo ou abaixo, com foto, saldo, mínimo e quanto falta (quem mais falta primeiro); filtros categoria, fornecedor e busca; paginada. Novo item "N produtos estão acabando" em "O que precisa da sua atenção" (`AvisoDeEstoqueBaixo`, calculado na hora). O mínimo continua sendo editado pela rota de produtos (recusa valor menor que zero; coberto por teste).
- Migração **V020**: `inventarios`, `itens_inventario` (esperado congelado, contado, ajuste gerado) e `leituras_inventario` (cada leitura, imutável). Só do servidor, fora do pull. Gatilhos: contagem fechada ou cancelada não muda mais; o esperado nunca muda.
- **`ServicoDeInventario`** (só chama o `ServicoDeEstoque`; nunca altera o saldo): começar (congela o esperado com as peças travadas em ordem de id), ler por código ou produto (soma), corrigir (define o contado), fechar, cancelar. Fechar é uma transação: um AJUSTE por peça com diferença (`contado − esperado congelado`), motivo "Contagem do estoque", origem `INVENTARIO`, em ordem de id; se algum ajuste deixaria o saldo abaixo de zero, nada é gravado, a contagem continua aberta e a mensagem diz a peça. Fechar de novo devolve o já fechado.
- Rotas `/api/dona/inventarios` (só ADMIN): começar, ver, listar, peças da contagem (filtros todas, divergentes, não lidas, lidas; busca; paginação; foto), leituras, contagens, fechar, cancelar. Auditoria de começar, fechar e cancelar.
- Textos novos no glossário (parte 12) e em `CodigoErro`, sem palavras proibidas (teste).

**Decisões que tomei**

- **Uma peça só pode estar em uma contagem em andamento.** Comparar "escopos" não basta (categoria e fornecedor se cruzam), então a conferência é por peça. Começar contagens é serializado por trava de transação.
- **Leitura repetida SOMA** (o leitor lê uma peça por vez); a **correção define o valor** e substitui. As duas são idempotentes pelo UUID da leitura: mesmo id e mesmos dados não soma de novo; outros dados, 409.
- **Peça não lida:** sem confirmação fica como está, sem ajuste; com `zerarNaoLidos` o contado vira 0 e gera o ajuste.
- **Peça nova depois do começo** não entra na contagem (ler o código dela dá erro "não faz parte desta contagem").
- **Só produtos ativos** entram no aviso e na lista de estoque baixo; a contagem inclui todos os não excluídos (inativo ainda tem peças).
- O esperado é lido com `FOR SHARE`, então é o saldo de um instante entre dois lançamentos.

**O que ficou de fora**

- Telas (2.7), relatório de inventário e divergências (2.5), inventário por vendedor/maleta (Fase 3).
- Fechar ajusta pelo esperado congelado: se a contagem foi física mas a dona quiser "forçar" o saldo ao contado, usa o ajuste manual.

**Dúvidas**

- Nenhuma nova. A da 2.1 (peça não lida) foi resolvida como a recomendação: só vira zero se a dona confirmar.

### Tarefa 2.5: backend, relatórios de estoque e de compras

**O que foi feito**

- Dez relatórios em `/api/dona/relatorios/...`, **só ADMIN** (vendedor 403, sem login 401), no mesmo motor da 1.12 (`MotorDeRelatorio`): linhas paginadas, rodapé, detalhamento de cada total (`recorte`), tudo das mesmas condições. O motor ganhou só o campo `pecas` no total (soma de peças) e o total com valor e predicado; nada mudou para os relatórios de cadastro.
  - `estoque/posicao-atual`: saldo, custo e **valor a custo** (saldo × custo) por produto; rodapé com peças e valor a custo geral, com estoque, sem estoque e "com estoque e sem custo informado".
  - `estoque/movimentacoes`: tipo, produto (com foto), quantidade com sinal, saldo depois, origem, motivo, quem fez, se é desfazer ou foi desfeito; rodapé com entradas e saídas (peças) e um total por tipo.
  - `estoque/abaixo-do-minimo`: mesma regra do aviso da 2.4; mostra quanto falta.
  - `estoque/parados?dias=N` (padrão 30): com saldo e sem saída há N dias.
  - `estoque/inventarios`: por inventário e produto, esperado, contado, diferença e o ajuste gerado; rodapé com divergentes, não lidos, sobras e faltas.
  - `estoque/conciliacao`: a mesma conta da 2.2; deve vir vazio e mostra o que diverge.
  - `compras/por-compra`: uma linha por compra; `compras/por-compra/{id}/itens`: itens de uma compra (produto com foto, quantidade, custo, total); `compras/por-fornecedor`: total por fornecedor e geral, somado dos itens; `compras/historico-de-custo`: data, compra, fornecedor, custo, custo anterior e variação (em reais e em %).
- **Filtros** (§12): período (de/até), fornecedor, categoria, produto (busca por nome, referência ou código, ou `produtoId`), situação (do produto, da compra ou do inventário), tipo e origem do movimento, usuário. Cada relatório aceita só os que fazem sentido (listados no Swagger).
- **Exportação:** o PDF, o Excel e o CSV são gerados pelo Corona Gestão (padrão da 1.12), com o relatório inteiro pedido em páginas de 100 e os **mesmos filtros** da tela. O backend garante isso: a rota não tem filtro que a exportação não use. Cada relatório tem teste que compara a tela (páginas pequenas até o fim) com a exportação (páginas de 100): mesmas linhas e mesmo rodapé. O cabeçalho, as três moedas e a foto no PDF são da tela 2.8 (as linhas já trazem `fotoPrincipalHash`, e a falta de foto nunca trava nada).
- Sem migração nova. Dinheiro em NUMERIC(14,2), `HALF_UP`, nunca float; fuso: datas em UTC (de/até inclusive); a data da compra é de calendário e é comparada como está.
- Testes (`RelatoriosDeEstoqueEComprasTest`, 16): números conferidos à mão (valor a custo, totais por fornecedor, estorno fora do total, variação do custo), cada filtro, tela × exportação, rodapé = detalhamento = soma das linhas, soma dos itens = total da compra, permissão, nada de custo, compra ou estoque no pull do vendedor, 10 mil produtos e 50 mil movimentos paginados em poucos segundos (dentro de uma transação desfeita no fim) e mensagens sem termos técnicos.

**Relatório × linha da §12**

| Linha da §12 | Relatório | Situação |
| --- | --- | --- |
| Estoque: posição atual | `estoque/posicao-atual` | Feito |
| Estoque: movimentações por período | `estoque/movimentacoes` | Feito |
| Estoque: estoque mínimo | `estoque/abaixo-do-minimo` | Feito |
| Estoque: produtos parados | `estoque/parados` | Feito |
| Estoque: inventário e divergências | `estoque/inventarios` | Feito |
| Estoque: curva ABC | — | Fase 8 (precisa de vendas) |
| Estoque: mercadoria em maletas por vendedor | — | Fase 3 (a maleta ainda não existe) |
| Compras: por fornecedor e período | `compras/por-fornecedor`, `compras/por-compra` e itens | Feito |
| Compras: histórico de custo por produto | `compras/historico-de-custo` | Feito |
| (§13) conciliação do estoque | `estoque/conciliacao` | Feito |
| Filtros: semana, vendedor, praça, cliente | — | Não se aplicam a estoque e compras |

**Decisões que tomei**

- **Estornadas:** por padrão a lista de compras mostra só as registradas; `situacao=TODAS` mostra as estornadas marcadas, e elas **nunca** entram no total geral (o rodapé tem "registradas" e "estornadas, fora do total" separados). Em "por fornecedor" as estornadas têm colunas próprias.
- **Por fornecedor soma os itens**, então categoria e produto somam só os itens daquele produto ou categoria; em "por compra" esses filtros só escolhem as compras que têm o item (o total da linha é o da compra inteira).
- **Parado** = saldo > 0 e nenhuma saída (`SAIDA` ou `REMESSA` que não foi desfeita) nos últimos N dias; perda, ajuste e devolução não contam como saída, e produto cadastrado há menos de N dias ainda não é "parado".
- **Estoque mínimo** reaproveita a regra da 2.4 (mínimo definido e saldo no mínimo ou abaixo), então a lista e o aviso nunca discordam.
- **Situação do produto** é ATIVOS por padrão em posição, mínimo e parados; em movimentações e histórico de custo o padrão é TODOS (o histórico não some porque o produto foi desativado). No relatório de inventário "situação" é a do inventário.
- O histórico de custo mostra também os custos de compras depois estornadas, marcados.

**O que ficou de fora**

- Telas e geração dos arquivos PDF, Excel e CSV (2.8); curva ABC (Fase 8); mercadoria em maletas por vendedor (Fase 3).
- Nenhum índice novo: medido com 10 mil produtos e 50 mil movimentos, sem necessidade.

**Dúvidas**

- Nenhuma.
