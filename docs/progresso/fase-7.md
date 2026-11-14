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

**Resultado final:** `npm run api:test` passa (1208 testes, nenhuma falha); Corona Vendas 607 testes; `npm run lint`, `typecheck` e `format:check` passam; `npm run seller:empacotar` e `npm run barreira:varrer` limpos; `npm run test:duas-pontas` passa (26 testes em 7 arquivos).

**Não fiz**

- Telas e gravar parcelamento e recebimento na outbox do Corona Vendas (7.10 e 7.11); a rota e a tela de diferenças das conciliações (7.8); devolução de venda com parcelas em aberto (7.3b); o nível 2 (7.5 e 7.6) e a comissão.
- `AutorizacaoDeDevolucaoTest.nenhumaRespostaDasRotasDaAutorizacaoTemCustoNemFornecedor` e `TrocaTest.prazoContaEmDiasDeBrasiliaComoNaDevolucao` já falham quando rodados sozinhos na `main` (dependem do que as outras classes deixam na configuração de prazos); passam na suíte inteira. Não foi mexido.

**Atualização (tarefa 6.6, PR 164):** a 7.3b foi feita pela 6.6: a devolução e a troca de venda a prazo abatem as parcelas (V042). `VENDA_A_PRAZO_SEM_DEVOLUCAO` já não é devolvido. Detalhe em [fase-6.md](fase-6.md#tarefa-66-devolução-e-troca-de-venda-a-prazo).

## Tarefa 7.4: acerto, parte 1 (iniciar, travas, contagem e desistir)

Issue #148, PR 165 (tarefa crítica: plano aprovado no PR, com cinco ajustes da orquestradora, todos atendidos). Parte 1 do ADR-0012 (itens 2, 3, 4, 13, 14, 15 e 16). **Nenhum dinheiro é gravado:** totais, comissão, pendências, recebimento e cotação nascem no fechamento (7.5). **Telas** (Corona Gestão e Corona Vendas) são da 7.9 e da 7.11.

**O que entrou**

- **Migração `V043__acerto_iniciar_e_contar.sql`:** `acertos` (sincronizável; só o status muda, de `EM_ACERTO` para `DESISTIDO`; `ACERTADO` chega na 7.5), `leituras_acerto` (sincronizável e imutável; soma por peça e estado nunca negativa), `remessas.acerto_id` e `remessas.status_antes_do_acerto`. O **banco** garante: um acerto `EM_ACERTO` por vendedor e um por semana fora `DESISTIDO` (índices únicos parciais); acerto nasce em andamento, com a semana aberta; conteúdo imutável; desistência completa (id, hora, quem e motivo); leitura só em acerto em andamento (`FOR SHARE`); maleta só entra no acerto vinda de enviada ou conferida, num acerto em andamento do mesmo vendedor, e só sai voltando ao status de antes; venda só entra em maleta em acerto com `vendida_em` anterior ao início (segunda trava da regra de corte). A função de pertença da V041 foi recriada inteira com os ramos novos.
- **`ServicoDeAcertos`** (único que liga e desliga `remessas.acerto_id` e escreve em `acertos` e `leituras_acerto`; `ArquiteturaDoAcertoTest`): `iniciar`, `desistir`, `registrarLeitura` e `trancarVendedor` (`FOR NO KEY UPDATE`, que não conflita com o `FOR KEY SHARE` da chave estrangeira e por isso não fecha espera em volta com a venda). Ordem: vendedor, acerto, maletas por `id`.
- **Rotas:** `POST/GET /api/dona/acertos`, `GET /api/dona/acertos/{id}`, `POST /api/dona/acertos/{id}/leituras`, `POST /api/dona/acertos/{id}/desistir`; `POST/GET /api/vendedor/acertos`, `GET /api/vendedor/acertos/{id}`, `POST /api/vendedor/acertos/{id}/leituras`. Comandos com UUID do cliente (201, 200 no reenvio igual, 409 com outro conteúdo). A ficha da dona traz maletas, contagem (com nome, código e foto), leituras (quem, quando, computador), `vendasQueChegaramDepoisDoInicio` e `enviosAguardando`; a do vendedor, sem esses dois e sem custo. Clientes da API regenerados.
- **Travas nas outras operações:** venda online e estorno pelo vendedor (`MALETA_EM_ACERTO`, antes `MALETA_NAO_ESTA_COM_VOCE` no estorno), devolução, troca, devolução à dona, cancelamento e conferência recusados; a **dona pode estornar** venda de maleta em acerto; envio de remessa recusado com `VENDEDOR_EM_ACERTO` (o rascunho é livre; o envio pega a trava do vendedor); a troca de computador leva a maleta em acerto; a lista de maletas do vendedor mostra a maleta em acerto. **Regra de corte:** a venda e o estorno vindos do envio, do computador da maleta, com a hora corrigida anterior ao início, entram (a venda online passou a gravar `vendida_em` com o relógio da aplicação, o mesmo do início).
- **Sync:** `acertos` e `leituras_acerto` no pull só do vendedor dono, sem os `*_por` e sem a ligação maleta-acerto.
- **Corona Vendas (só o motor e o banco local):** migração local 6, colunas, recarga dirigida das duas tabelas (somada à marca antiga que ainda não terminou), `banco-v6.sqlite`.
- **Documentos:** especificação v1.18 (§7.4, §9, §10, §22), ADR-0012 ("Como ficou" nos itens 3, 4 e 13), plano da Fase 7 e nota de deploy em `entrega-corona-vendas.md` (app antes da API).

**Decisões que tomei (padrão seguro; todas no plano aprovado)**

- Só a dona desiste (o vendedor pede, bipa e lê). A semana do acerto é a informada, a mais recente das maletas ou a de hoje. O status de antes da maleta fica gravado (o desistir volta exatamente a ele). Sem esperado × contado nesta tarefa, para não haver duas contas que possam divergir.
- `aguardando_envio` é o **último número informado**: o servidor recusa o que sabe; a garantia de fila vazia é do aplicativo (7.11).
- Pedir o acerto com o id de outro vendedor devolve 409 (id usado), nunca o conteúdo.
- Os testes antigos que forçavam `EM_ACERTO` por SQL passaram a usar `AcertosDeTeste` (o banco não deixa mais mudar o status sem ligar a um acerto); as contagens de tabelas e a versão do banco local dos testes antigos foram atualizadas (25 para 27 tabelas; versão 5 para 6).
- A leitura aceita peça de devolução "para a loja" do vendedor por consulta; o teste automático cobre a peça das maletas (com os dois estados) e a de fora; a peça só de devolução ficou coberta pelo desenho da consulta, sem teste próprio (montar essa devolução sem estar numa maleta do acerto exigia dados demais).

**Testes** (PostgreSQL do Testcontainers e SQLCipher de verdade): banco (19), iniciar (13), leituras (8), desistir (5), leitura (4), permissões com 403 cruzado, 401 e 404 (4), travas (10), regra de corte (4), concorrência com threads, cinco rodadas cada e tempo limite de 10 s (9: venda, devolução, troca e envio de remessa contra o início; dois iniciar; leitura contra desistir; leituras negativas; estorno da dona contra leitura), arquitetura (2) e pull (4); Corona Vendas: 8 testes novos.

**Resultado final:** `npm run api:test` passa (1331 testes, nenhuma falha); Corona Vendas 626 testes; Corona Gestão 665 testes; `npm run lint`, `typecheck` e `format:check` passam; `npm run seller:empacotar` e `npm run barreira:varrer` limpos. `npm run test:duas-pontas` roda no CI do PR (job `sync-duas-pontas`).

**Não fiz**

- Fechamento, esperado × contado, comissão, pendências, recebimentos, caixa e `cotacao_id` do acerto (7.5 e 7.6); telas no Corona Gestão e no Corona Vendas (7.9 e 7.11); tipo novo na outbox; `remessas.acerto_id` e `status_antes_do_acerto` no pull; rota de `AJUSTE` na maleta (a 7.5 decide com a sobra).
- A maleta de um acerto desistido deixa de aparecer na ficha dele (a ligação é desligada); o histórico das maletas fica na auditoria (`ACERTO_INICIADO` e `ACERTO_DESISTIDO`).

## Tarefa 7.5: acerto, parte 2 (fechamento, comissão por meta e pendências)

Issue #149, PR 166 (tarefa crítica: plano aprovado no PR, com um ajuste da orquestradora, atendido: venda com desconto nos testes do `CalculoDaComissao`; as 16 decisões de padrão seguro confirmadas). Parte 2 do ADR-0012 (itens 3.6, 5, 6, 8, 13, 14, 15 e 16). **Recebimentos, caixa e contas do nível 2** são da 7.6 (`recebido_no_acerto` = 0 e `ficou_devendo` = líquido); **telas**, da 7.9, 7.10 e 7.11.

**O que entrou**

- **Migração `V044__acerto_fechamento_e_comissao.sql`:** três chaves em `configuracoes` (meta de 700.000 Gs, 30% e 40%, com faixas e `abaixo <= na meta` no `COMMIT`); colunas do fechamento em `acertos` com os `CHECK` das contas do item 8; `acerto_eventos`, `itens_acerto`, `comissoes` e `pendencias_acerto` (sincronizáveis; as três primeiras imutáveis); `proteger_acerto`, `conferir_acerto_da_remessa` e `recusar_mudanca_de_pertenca` recriadas inteiras; gatilho adiado que confere totais contra eventos, comissões e pendências e exige todas as maletas acertadas; a maleta só vai a `ACERTADA` vinda do acerto em andamento, com saldo zero, e depois não muda nem recebe movimento; evento só entra uma vez e com o valor da origem; estorno de comissão com o percentual da linha original e nunca além da comissão; devolução ligada a acerto não cancela; origem `ACERTO` no estoque central.
- **`ServicoDeFechamento` e `MontagemDoFechamento`** (a montagem só lê; a prévia e o fechamento a usam): travas na ordem vendedor, trava da devolução, acerto, maletas, pendências, produtos; idempotência por UUID e resumo das decisões; sobra, revisão pendente, decisões a mais ou a menos e líquido diferente dão 409 (o último com a prévia nova); gravação de eventos, comissões, itens, pendências, movimentos (`PERDA` e `DEVOLUCAO_DONA` na maleta; devolução e avaria no estoque numa chamada só), maletas e acerto; auditoria `ACERTO_FECHADO` e `PENDENCIA_DECIDIDA`. **Classes puras:** `CalculoDaComissao` (soma da meta em Guarani, percentual, comissão por venda e estorno pelo acumulado) e `EsperadoEContado` (sobra, falta na maleta mais antiga, linha "para a loja", defeito aprovado por quantidade); `ConversaoDeMoeda.guaraniParaReais` para a prévia.
- **Rotas:** `GET /api/dona/acertos/{id}/fechamento` (prévia; com o acerto fechado devolve o gravado), `POST .../fechamento/previa` (com as decisões), `POST .../fechar`; `GET/PUT /api/dona/configuracoes/comissao`; `GET /api/dona/pendencias-acerto` e `POST .../{id}/resolver` (`LOJA_ASSUME` ou `ENCONTRADA`, com motivo); `GET /api/vendedor/comissao/previa`. A ficha do acerto (dona e vendedor) traz o `fechamento` (totais, cotação, itens, eventos, comissões e pendências), sem quem fechou e sem custo. Clientes da API regenerados.
- **Pull e Corona Vendas:** as quatro tabelas só do vendedor dono e as colunas novas de `acertos` (sem `fechamento_id`, resumo das decisões nem `fechado_por`); migração local 7 com recarga dirigida (somada à marca antiga) e `banco-v7.sqlite`; "Acertada" nos rótulos da maleta. **Nota de deploy** em `entrega-corona-vendas.md`: app antes da API.
- **Estoque:** a conciliação "estoque central mais maletas" (no `ServicoDeEstoque` e no relatório) passou a contar a `PERDA` da maleta como saída, como a venda; a avaria do defeito aprovado e a peça "para a loja" entram com origem `ACERTO`.
- **Documentos:** especificação v1.19, ADR-0012 ("Como ficou" nos itens 3, 5, 6 e 8), plano da Fase 7, glossário (fechamento, pendência, ficou devendo, meta da comissão) e nota de deploy.

**Decisões que tomei (todas as do plano aprovado; o que mais chama atenção)**

- A sobra impede o fechamento e não tem rota de `AJUSTE`; revisão pendente (devolução e crédito sem saldo) também impede. O "não aceita" cobra o valor inteiro, com as três decisões da falta. Pendência aberta de acerto anterior pode ser cobrada no fechamento seguinte.
- O `liquidoMostrado` do fechar é obrigatório e só confere. A prévia sem decisões (`GET`) assume cobrar tudo no acerto e não aprovar defeito; a dona usa a prévia com decisões para ver o líquido das escolhas dela.
- `AcertosDeTeste.acertada` (usado por testes antigos de venda, devolução e troca) agora leva a maleta por PERDA do que sobrou e a deixa `ACERTADA` mantendo a ligação, desistindo o acerto de mentira; o banco não deixa mais ir de `ENVIADA` direto a `ACERTADA`.
- Cotações dos testes do acerto são de 2291 e saem no fim de cada classe junto com os acertos fechados (`CotacoesDaMassa`), porque os testes de cotações esperam o banco sem cotação que não seja delas.

**Testes** (PostgreSQL do Testcontainers e SQLCipher de verdade): banco do fechamento (20), cálculo da comissão (16, com venda com desconto), esperado x contado (10), fechamento (17), configuração, prévia e pendências (8), concorrência com threads, cinco rodadas e tempo limite de 10 s (7: mesmo acerto com o mesmo id e com ids diferentes, devolução "para a loja" contra o fechar, estorno contra o fechar, dois vendedores em ordens cruzadas, resolver contra o fechar e a falha forçada no meio), conciliações C2, C3, C8, C9 e estoque mais maletas, com a prova de que cada uma acusa o dado estragado (4), arquitetura (5) e pull (5); Corona Vendas: migração 7 e colunas.

**Resultado final:** `npm run api:test` passa (1425 testes, nenhuma falha; a primeira rodada achou uma espera em volta real entre dois fechamentos em ordens cruzadas, corrigida travando os produtos por id antes de gravar); Corona Vendas 630 testes; Corona Gestão 665 testes (uma falha isolada numa rodada com o banco ocupado, que passou na repetição); `npm run lint`, `typecheck` e `format:check` passam; `npm run seller:empacotar` e `npm run barreira:varrer` limpos; `npm run test:duas-pontas` passa (26 testes, 7 arquivos).

**Não fiz**

- Recebimentos, caixa, contas do nível 2 e crédito do vendedor no acerto a favor dele (7.6); telas (7.9, 7.10, 7.11); consulta formal e rota das conciliações (7.8); relatórios (7.12); tipo novo na outbox.
- Teste de integração do 409 `SEM_COTACAO_PARA_O_ACERTO` (a tabela de cotações é imutável e compartilhada entre os testes) e de `REVISAO_PENDENTE_NO_ACERTO` (montar uma devolução em revisão exigiria muita massa); as duas regras estão no código e cobertas por leitura do código e pelo gatilho do banco.


## Tarefa 7.6: recebimentos do vendedor, contas do nível 2 e caixa automático

Issue #150, PR 167 (tarefa crítica: plano aprovado no PR, com um ajuste da orquestradora, atendido: nos textos de tela, "Transferência (PIX)" para a forma PIX; as 12 decisões de padrão seguro, D1 a D12, confirmadas). O dinheiro do acerto do ADR-0012 (itens 1, 2, 3.6, 7, 8, 9, 11, 13, 14, 15 e 16): o pagamento do acerto, o que ficou devendo, o acerto a favor do vendedor, o recebimento avulso e o estorno, as contas do nível 2 e o caixa automático. Sem entrada e sem cheque.

**O que entrou**

- **Migração `V045__recebimentos_e_caixa.sql`:** `recebimentos_vendedor` (sincronizável, imutável; `momento` `ACERTO | AVULSO`, `aplicado_em` `ACERTO | CONTAS`; `CHEQUE`, `CARTAO` e, no fechamento, `BOLETO` recusados pelo `CHECK`), `lancamentos_caixa` e `categorias_caixa` (só do servidor; duas categorias do sistema), `contas_receber` com o devedor `VENDEDOR` (origens `ACERTO` e `ESTORNO_RECEBIMENTO`, sem venda) e `forma_prevista` (`BOLETO`), `baixas_receber.recebimento_vendedor_id`, e `acertos.pago_ao_vendedor`, `credito_usado` e `credito_do_acerto_id` (único). Funções e `CHECK` antigos recriados inteiros (nenhuma migração publicada editada): `conferir_conta_a_receber`, `proteger_conta_a_receber`, `conferir_baixa_receber`, `proteger_acerto` e `conferir_acerto_fechado`. O gatilho adiado do recebimento confere um lançamento de caixa de mesmo valor e as baixas inteiras; o do acerto confere recebido, contas, pago ao vendedor e crédito (usado uma vez só, e nenhum crédito anterior sem uso).
- **`ServicoDeCaixa`** (único que grava em `lancamentos_caixa`, sempre dentro da transação de quem gerou o dinheiro), **`ServicoDeRecebimentosDoVendedor`** (único que grava em `recebimentos_vendedor`: avulso, estorno e o recebimento do fechamento) e o nível 2 do **`ServicoDeContasAReceber`** (gerar as contas do acerto, baixar pelo recebimento, estornar baixas). Teste de arquitetura: um escritor por tabela.
- **Fechamento com `pagamento`** (`ServicoDeFechamento`): recebimentos em dinheiro e Transferência (PIX), boleto como conta, o restante em 1 a 24 parcelas mensais, contas antigas pagas junto (fora dos totais), acerto a favor do vendedor (crédito, abatido inteiro no próximo acerto, ou pagar na hora, com saída de caixa). A prévia mostra o crédito disponível, o que há a pagar, o que ficaria devendo, as parcelas e as contas abertas do vendedor. Fechar sem `pagamento` com líquido positivo é 409 "Escolha a data de pagamento do que ficou devendo".
- **Rotas:** `POST/GET /api/dona/recebimentos-vendedor`, `POST .../{id}/estorno`, `GET /api/dona/caixa/lancamentos` (extrato com saldo depois de cada lançamento), `GET /api/dona/contas-receber?nivel=` e `GET /api/vendedor/contas-receber?nivel=` (os dois níveis), `GET /api/vendedor/recebimentos`; a ficha do acerto mostra recebimentos, crédito usado, pago ao vendedor e as contas geradas. Clientes da API regenerados.
- **Pull e Corona Vendas:** `recebimentos_vendedor`, as contas dos dois níveis (regra `vendedor_id` ou venda do vendedor), as baixas e as colunas novas do acerto, só do vendedor dono e sem quem registrou, motivo nem resumo; `lancamentos_caixa` e `categorias_caixa` nunca vão (teste). Migração local 8 (refaz a cópia das contas com as mesmas linhas, aceitando conta sem venda; cria a tabela e as colunas), recarga dirigida somada à marca antiga e `banco-v8.sqlite`.
- **Documentos:** especificação v1.20, ADR-0012 ("Como ficou" nos itens 8, 9 e 11), glossário (Transferência (PIX) e os termos do dinheiro do vendedor) e nota de deploy (app antes da API).

**Decisões que tomei (todas as do plano aprovado, D1 a D12; o que mais chama atenção)**

- O crédito do vendedor é o `ficou_devendo` negativo do acerto, abatido inteiro e sozinho no próximo fechamento (`credito_usado`, `credito_do_acerto_id`); só existe um acerto em andamento por vendedor, então o crédito não tem disputa entre dois fechamentos e o único de `credito_do_acerto_id` fica como segunda trava.
- `pago_ao_vendedor` e `credito_usado` nascem `NOT NULL DEFAULT 0` (e zero fora de `ACERTADO`), e não nulos até o fechamento como o plano dizia: assim os testes do banco que fecham o acerto por SQL e as linhas antigas continuam válidos, e o `CHECK` da C2 não precisa de `COALESCE`.
- O estorno de recebimento copia `momento`, `acerto_id`, `aplicado_em` e `forma` do original (o gatilho confere); o do que pagou o acerto vira conta `ESTORNO_RECEBIMENTO`. O lançamento de caixa do estorno mantém o `tipo` do original e tem o sinal oposto.
- A prévia do vendedor das contas (`nivel`) devolve os dois níveis por padrão; `GET /api/dona/contas-receber` também (antes só o nível 1), com `nivel` para filtrar.
- A conta do boleto (`forma_prevista = BOLETO`) só aceita baixa de um recebimento `BOLETO` pelo saldo inteiro; as outras formas pagam a conta do boleto como qualquer outra.

**Testes** (PostgreSQL do Testcontainers e SQLCipher de verdade): banco (18), contas do nível 2 (7), recebimento avulso e estorno (9), fechamento com pagamento (13, com o exemplo do item 18), concorrência com threads reais, cinco rodadas e tempo limite de 10 s (4), leitura e permissões (7), pull (3), conciliações C2, C4 e C5 que acusam o dado estragado (6), arquitetura (4); Corona Vendas, migração local 8 e colunas (6, mais as listas das marcas das migrações antigas).

**Resultado final:** `npm run api:test` passa (1495 testes, nenhuma falha); Corona Vendas 637 testes; Corona Gestão 665 testes em 46 arquivos; `npm run test:duas-pontas -w @corona/testes-sync` passa (26 testes em 7 arquivos); `npm run lint`, `npm run typecheck` e `npm run format:check` limpos; `npm run seller:empacotar` e `npm run barreira:varrer` com varredura limpa.

**Não fiz**

- Lançamento manual de caixa, categorias com rota, aviso de caixa negativo e fechar semana (7.7); consulta formal e rota das conciliações (7.8); telas (7.9, 7.10 e 7.11); relatórios (7.12).
- O teste de dois fechamentos disputando o mesmo crédito: com um acerto em andamento por vendedor (índice único), os dois fechamentos seriam do mesmo acerto (já coberto) e o crédito só tem disputa possível pelo `UNIQUE`, provado no banco.
- Multa, juros e cartão (fora do escopo do plano).
