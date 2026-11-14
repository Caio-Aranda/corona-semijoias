# Fase 7: acerto e financeiro (detalhe das tarefas)

O plano está em [fase-7-plano.md](fase-7-plano.md); o desenho, no [ADR-0012](../decisoes/ADR-0012-acerto-e-financeiro.md).

## Tarefa 7.1b: decisões do responsável de 13/11

Só documentos, sem código (issue #144, PR 159). O responsável aceitou o ADR-0012 em 13/11/2026 com quatro mudanças, aplicadas antes de qualquer código da Fase 7.

**O que entrou**

- **ADR-0012:** status "Aceito" (13/11/2026, pelo responsável), com a seção "Mudanças do aceite" no topo e o histórico no fim.
  - Sem entrada: saem `entrada_abatida`, o momento `ENTRADA` do recebimento e o abatimento no fechamento; `a_pagar_no_acerto = liquido` (itens 2, 3, 8 e 9, conciliação C2).
  - Sem cheque: saem as tabelas `cheques` e `eventos_cheque`, o lançamento `CHEQUE_COMPENSADO`, a conta a receber de cheque devolvido, a trava e a auditoria de cheque e a conciliação C6 (itens 1, 2, 7, 10, 11, 13 a 17). Repasse do vendedor: `DINHEIRO`, `PIX` (na tela, "Transferência") e `BOLETO`; fornecedor: dinheiro, transferência e boleto.
  - Comissão por meta (item 5 reescrito): soma do vendido líquido das vendas do acerto em Guarani, cada venda pela cotação gravada nela; 700.000 Gs ou mais → 40%, abaixo → 30%, sobre o total; meta e percentuais em `configuracoes`; `vendedores.comissao_percentual` fica sem uso; comissão por venda com o percentual na linha; estorno pelo percentual da linha original; prévia com quanto falta para a meta.
  - Exemplo numérico (item 18) refeito sem entrada e sem cheque, com a meta e 30%.
- **Plano da Fase 7:** tarefas 7.5 (comissão por meta), 7.6 (sem entrada e sem cheque), 7.7 (formas do fornecedor), 7.8 (nove conciliações), 7.10 e 7.11 (meta nas telas); testes obrigatórios trocados (meta no limite, cotações diferentes, percentual da linha original, boleto, forma recusada; saíram os de cheque e de entrada); dúvidas 2, 4, 7, 11, 14 e 20 marcadas como respondidas; última migração V040.
- **Especificação v1.14:** §2 (aba Totais: sem entrada e sem cheque no sistema novo), §6 premissa 8, §7.4 (comissão por meta e formas do repasse), §7.5, §9 (`cheques` sai; `acertos`, `comissoes`, `vendedores` e `configuracoes` anotados), §12, §15, §17 (itens 6, 12 e 13) e §22.
- **Índice de decisões e `PROGRESSO.md`:** ADR-0012 como Aceito e a linha desta tarefa.

**Testes:** não há código. `npm run format:check` passa.

**Decisões que tomei**

- **O que conta para a meta:** a venda estornada, ou a parte devolvida, sai da soma quando o estorno ou a devolução está no mesmo acerto (pela cotação da própria venda); estorno e devolução de venda de acerto já fechado não entram na soma, só geram o estorno de comissão pelo percentual da linha original. A issue fala em "vendido líquido das vendas ligadas ao acerto" e não diz o que fazer com o estorno.
- **Venda sem cotação gravada** (`vendas.cotacao_id` aceita vazio): usa a cotação que valia no dia dela (ADR-0006).
- **O acerto guarda o percentual decidido e a soma em Guarani** (`percentual_comissao`, `vendido_para_meta_guarani`), para a dona e o vendedor conferirem; é a única exceção a "não se grava valor convertido", e nenhuma conciliação em Real a usa.
- **Meta e percentuais valem pelo instante do fechamento**; mudar depois não altera acerto fechado. As três chaves de `configuracoes` nascem na migração da 7.5 (nomes e faixas ficam para o plano dela).
- **"Quanto falta para a meta" em Real:** convertido pela cotação mais recente, só para mostrar.
- **Numeração preservada:** os itens 9 e 10 do ADR e a conciliação C6 mantêm o número (o 10 e a C6 dizem que foram retirados), para as referências "item 17", "item 18" e "C1 a C10" continuarem valendo. São nove conciliações.
- **Pagamento a fornecedor** grava os mesmos três valores do repasse (`DINHEIRO`, `PIX`, `BOLETO`).
- **§15 da especificação** (linha da Fase 7) também perdeu "cheques", embora a issue não cite a seção, para não ficar contraditória. A §13 não mudou: não cita entrada nem cheque (as fórmulas corretas estão no ADR-0012, item 17).
- **Cabeçalho da especificação** dizia versão 1.12 com o histórico já na v1.13; passou a 1.14.
- **Prazo de 3 a 4 dias para o acerto** (dúvida 20): só informativo, sem bloqueio; quem fecha a semana continua no padrão seguro.
- **Glossário e ADR-0014** (fora da lista da issue, acertados na revisão para não ficarem contra a decisão): no `docs/glossario-interface.md` saíram as situações de cheque, a forma `PIX` aparece como **Transferência** também no acerto e no pagamento a fornecedor, "cheque" só vale para pagamento antigo e "entrada" não existe (a dúvida da parte 10 foi fechada); o ADR-0014 cita o ADR-0012 como Aceito.

**Não fiz (fica para as próximas tarefas)**

- Os termos novos do glossário (acerto, pendência, ficou devendo, meta da comissão) entram nas tarefas das telas (7.9 a 7.12).
- `docs/perguntas-para-a-dona.md` e os outros ADRs não foram mexidos.
- Nenhuma migração, chave de configuração ou tela: tudo isso é das tarefas 7.5, 7.6, 7.10 e 7.11.

## Tarefa 7.2: limite de consignação

Issue #145, PR 160. **Boa parte já estava na `main`:** o PR 86 (11/11/2026) criou a migração `V026__limite_de_consignacao_obrigatorio.sql` e mudou o cadastro do vendedor, a montagem da maleta e as mensagens. Esta tarefa conferiu a seção 7.2 do plano item por item e fechou só o que faltava. **Nenhuma migração nova.**

**Conferência (o que o plano pede × o que prova)**

| Item do plano | Já existia (PR 86 e Fase 3) | Entrou nesta tarefa |
| --- | --- | --- |
| Migração do "Sem limite" | `V026`: coluna `limite_consignacao_ilimitado` (na API, `semLimite`) e `CHECK vendedores_limite_ou_sem_limite` (recusa os dois juntos). Testes: `VendedoresDaDonaTest.obancoRecusaSemLimiteJuntoComUmValorDeLimite`, `MigracaoDosVendedoresTest` (limite 0 de antes virou limite não definido) | Nada (ver "Decisões que tomei") |
| A dona vê na lista quem está "Sem limite" | Só na ficha, na montagem da maleta e na lista de maletas | Coluna **Limite de consignação** na lista de vendedores: valor em reais, **Sem limite** ou **Limite não definido**. Teste: `vendedores.test.tsx`, "mostra o limite de cada vendedor" |
| Cadastro: campo obrigatório com "Sem limite" | API: `VendedoresDaDonaTest.limiteDeConsignacaoEObrigatorioOuSemLimiteMarcadoDePropositoNuncaOsDoisENuncaZeroComoSemLimite` (criar e editar). Tela: `vendedores.test.tsx`, "o limite é obrigatório" e "Sem limite desabilita o valor" | Nada |
| Conta do envio pelo preço congelado | `RemessasDaDonaTest.limiteExcedidoRecusaComLimiteEmAbertoEQuantoFalta`, `noLimiteExatoPassaEUmCentavoAMaisNao`, `dossEnviosSimultaneosDoMesmoVendedorNaoFuramOLimite` (nenhum mudava o preço depois do envio) | `RemessasDaDonaTest.limiteContaOPrecoCongeladoDaMaletaENaoOPrecoDeHojeNemOCusto` |
| Envio acima do limite com reposição é recusado | `RemessasDaDonaTest.reposicaoEntraNaMesmaConta` (só o 409) | `RemessasDaDonaTest.reposicaoQueCabeNoLimitePassaEAQuePassaDoLimiteERecusadaSemBaixarOEstoque` (código do erro, valores na mensagem, estoque intacto, a maleta continua em montagem) |
| "Sem limite" nunca bloqueia | `RemessasDaDonaTest.semLimiteMarcadoDePropositoNaoBloqueia`, `DevolucaoDaMaletaTest.maletasEmAbertoDeVendedorSemLimiteEExigeVendedor` | `RemessasDaDonaTest.semLimiteNuncaBloqueiaNemComVariasMaletasEReposicao` |
| Sem valor e sem "Sem limite" é recusado | API (acima) e envio: `RemessasDaDonaTest.vendedorSemLimiteDefinidoNaoRecebeMaletaNemRascunho`; zero vale zero: `limiteZeroValeZeroENaoSignificaSemLimite` | Nada |
| 403 cruzado | Vendedor nas rotas da dona: `VendedoresDaDonaTest.vendedorRecebe403EmTodasAsRotas`, `RemessasDaDonaTest.vendedorRecebe403ESemLoginRecebe401`, `DevolucaoDaMaletaTest.vendedorRecebe403ESemLoginRecebe401NasRotasNovas` (inclui `em-aberto`). Dona nas rotas do vendedor: `RotasDoVendedorTest.vendedorVeASuaMaletaSemCustoEOutroVendedorNao` | Nada |
| Especificação §7.1, §9 e §17 | §7.1 já dizia "campo obrigatório ... ou Sem limite" | **v1.15:** §7.1 (obrigatório, zero vale zero, preço congelado, reposição entra, limite não definido não recebe maleta), §9 (`limite_consignacao_ilimitado`), §17 (item 15) e §22 |
| ADR-0010: dúvidas 1, 2 e 6 | Item 7 corrigido em 11/11, mas "provisório"; só a 6 dita como respondida | Status, item 7 e as dúvidas 1, 2 e 6 marcadas como resolvidas (decisões do responsável de 11/11/2026) |

**O que entrou**

- **Backend (só testes):** três testes novos e um ajudante (`reposicao`) em `RemessasDaDonaTest`. Nenhuma linha de código de produção mudou: os três passaram de primeira, a regra já estava certa.
- **Corona Gestão:** `limiteDaLista` em `telas/vendedores/dados.ts`, a coluna em `TelaVendedores.tsx` e os textos em `textos-vendedores.ts`. A lista já recebia `limiteConsignacao` e `semLimite` da API; nada mudou no contrato.
- **Documentos:** especificação v1.15, ADR-0010, glossário (termo "limite de consignação"), plano da Fase 7 (nota "Como ficou" na seção 7.2) e `PROGRESSO.md`.

**Testes:** `npm run api:test` passa (1102 testes, nenhuma falha). Corona Gestão: 665 testes em 46 arquivos. `npm run lint`, `npm run typecheck` e `npm run format:check` passam. O Corona Vendas não foi tocado (a barreira não precisou rodar).

**Decisões que tomei**

- **Vale o desenho da V026, não o do plano.** O plano pedia `sem_limite` com `CHECK (sem_limite OR limite_consignacao IS NOT NULL)` e os vendedores antigos virando "Sem limite". A V026, publicada antes, fez diferente: a coluna chama `limite_consignacao_ilimitado`, o `CHECK` recusa os dois juntos, e quem estava com limite 0 ficou com o **limite não definido**, que não recebe maleta até a dona escolher. Mantive: migração publicada não se edita, e trocar "não definido" por "Sem limite" numa migração nova liberaria maleta ilimitada para cadastro antigo sem a dona ter escolhido (o contrário do padrão seguro e do item 7 do ADR-0010). Por isso **o banco não recusa** a linha com os dois vazios: ela é o estado dos cadastros antigos. A API recusa criar ou editar assim, e o envio e o rascunho são recusados. O plano ganhou a nota "Como ficou".
- **Lista de vendedores só em Real.** A coluna mostra o valor em reais; Dólar e Guarani continuam na ficha (`CampoDeDinheiro`), para a lista não ficar larga.
- **"Limite não definido" na lista:** o mesmo termo que a lista de maletas e a reposição já usam.
- **§17, item 15:** a seção não tinha item do limite; registrei as três perguntas já como decididas, para o plano ("atualizar a §17") ficar atendido sem reabrir pendência.
- **O teste antigo `reposicaoEntraNaMesmaConta` ficou**, ao lado do novo, mais completo.

**Não fiz**

- **Relatório "Vendedores: limite e situação":** continua mostrando o limite vazio tanto para "Sem limite" quanto para "Limite não definido". Distinguir os dois pede um campo novo na resposta do relatório (OpenAPI e cliente gerado); fica como sugestão de tarefa.
- A descrição da rota "Cadastrar vendedor" no OpenAPI ainda diz "Limite e comissão começam em zero" (texto de antes da V026); mudar regera o cliente, ficou de fora.
- Nenhuma migração, nenhuma mudança no Corona Vendas, nenhuma regra nova.

## Tarefa 7.3: contas a receber do cliente

Issue #146, PR 162 (tarefa crítica: plano aprovado no PR, ajustes da orquestradora atendidos). Nível 1 do ADR-0012 (itens 2 e 7): a venda a prazo ganha parcelas; o cliente paga depois; o servidor distribui, confere e concilia. **Telas e gravar parcelamento e recebimento na outbox do Corona Vendas ficam para a 7.10 e a 7.11.** A comissão e o acerto não foram tocados (7.4 a 7.6).

**O que entrou**

- **Migração `V041__contas_a_receber_do_cliente.sql`:** `contas_receber` (parcela, vencimento, valor e os atalhos `valor_pago`, `valor_cancelado`, `status`; `VENCIDA` nunca é gravada), `baixas_receber` (imutável; `PAGAMENTO` com o estorno negativo, `CANCELAMENTO` do estorno da venda) e `pagamentos_cliente.posterior` mais o tipo `ESTORNO_PAGAMENTO`. O **banco** garante: parcela nasce em aberto, de venda concluída e do cliente dela; conteúdo imutável; atalhos só mudam para a soma das baixas; baixa cabe na parcela e no recebimento; estorno do mesmo valor e uma vez só; recebimento posterior só em dinheiro ou Pix. A venda a prazo já gravada ganha uma parcela no dia da venda (Brasília).
- **`ServicoDeContasAReceber`** (único que faz `UPDATE` em `contas_receber`; `ArquiteturaDasContasTest`): `gerarParcelas` (1 a 24, mensais a partir da primeira, `Dinheiro.dividirEmParcelas`; sem parcelamento, uma parcela um mês depois), `receber`, `estornarRecebimento` e `cancelarEmAberto`. Ordem de travas: estorno da venda remessa, venda, créditos, parcelas por id; recebimento e estorno do recebimento venda, parcelas. O recebimento não olha a maleta.
- **Rotas:** `POST /api/vendedor/vendas/{id}/recebimentos`, `POST /api/vendedor/recebimentos-de-cliente/{id}/estorno` (vendedor só no mesmo dia), `POST /api/dona/recebimentos-de-cliente/{id}/estorno`, `GET /api/vendedor/contas-receber`, `GET /api/dona/contas-receber`; a ficha da venda traz as parcelas e marca os pagamentos posteriores. `parcelamento` opcional na venda e na venda nova da troca. Cliente da API regenerado.
- **Leitores de pagamento corrigidos** (o recebimento estornado voltaria a dever): devolução, autorização, lista de vendas com a peça (`aPrazo`), conferência de dinheiro devolvido e de contas da venda; um teste por leitor. O relatório de recebimentos já somava tudo. O estorno da venda reembolsa só os pagamentos não estornados. `grep "'PAGAMENTO'"` conferido no fim.
- **Sync:** `VENDA` e `TROCA` formato 1 ampliados com `parcelamento` opcional (o resumo antigo não muda; teste com resumo fixo), tipos novos `RECEBIMENTO_CLIENTE/1` e `ESTORNO_RECEBIMENTO_CLIENTE/1`, `contas_receber` e `baixas_receber` no pull (pela venda do vendedor), `posterior` no pull de `pagamentos_cliente`.
- **Corona Vendas (só o motor e o banco local):** migração local 4, colunas, recarga dirigida das três tabelas (somada à da 3 quando ela ainda não terminou), `banco-v4.sqlite`.
- **Documentos:** especificação v1.16 (§7.2, §9, §10, §22), ADR-0012 (item 7, "Como ficou"), glossário (parte 13), nota de deploy em `entrega-corona-vendas.md`.

**Decisões que tomei (padrão seguro; todas no plano aprovado)**

- Devolução e troca de venda a prazo com parcelas em aberto continuam recusadas (`VENDA_A_PRAZO_SEM_DEVOLUCAO`); a 7.3b (issue nova, criada pela orquestradora) trata disso. Venda a prazo já quitada continua podendo ser devolvida.
- Recebimento posterior só em `DINHEIRO` e `PIX`, registrado pelo vendedor; a dona lê e estorna sem limite de dia.
- Estorno da venda cancela só o que está em aberto: parcela paga continua paga e o dinheiro volta pelo reembolso.
- **Achado da concorrência:** estorno de recebimento numa venda com devolução não cancelada seria um segundo reembolso do mesmo dinheiro; passou a ser recusado com `VENDA_COM_DEVOLUCAO` (a devolução e o estorno do recebimento nunca passam juntos).
- O `RECUSADO` do envio (recebimento acima do que falta, venda estornada) já aparece para a dona em "Envios com problema"; nenhuma tabela de revisão nova.

**Testes** (PostgreSQL do Testcontainers e SQLCipher de verdade): banco (18), parcelas (12), recebimento (12), estorno do recebimento e leitores (13), estorno da venda a prazo (8), leitura (7), permissões (4), concorrência com threads e tempo limite de 10 s (7), arquitetura (4), conciliação C1 e C5 (4), envio (11) e pull (4), mais dois testes de troca; Corona Vendas: 8 testes novos e 4 da Fase 6 ajustados para o banco na versão 4 e as 25 tabelas.

**Não fiz**

- Telas e gravar parcelamento e recebimento na outbox do Corona Vendas (7.10 e 7.11); a rota e a tela de diferenças das conciliações (7.8); devolução de venda com parcelas em aberto (7.3b); o nível 2 (7.5 e 7.6) e a comissão.
- `AutorizacaoDeDevolucaoTest.nenhumaRespostaDasRotasDaAutorizacaoTemCustoNemFornecedor` e `TrocaTest.prazoContaEmDiasDeBrasiliaComoNaDevolucao` já falham quando rodados sozinhos na `main` (dependem do que as outras classes deixam na configuração de prazos); passam na suíte inteira. Não foi mexido.
