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

## Tarefa 7.7: contas a pagar, caixa manual e fechar semana

Issue #151, PR 168 (tarefa crítica: plano aprovado no PR, as decisões 1 a 11 confirmadas pela orquestradora, sem ajustes). O que falta do financeiro do ADR-0012 no servidor (itens 2, 3.8, 11, 12, 13, 14, 15, 16 e 17; condições C4, C5 e C10): as contas a pagar das compras, o pagamento a fornecedor, o caixa manual com categorias, o aviso de caixa negativo e fechar a semana. Tudo é backend, mais uma mudança pequena no Corona Gestão; o Corona Vendas não foi tocado.

**O que entrou**

- **Migração `V046__contas_a_pagar_caixa_manual_e_semana.sql`:** `contas_pagar` (só do servidor; conteúdo imutável, atalhos `valor_pago`, `valor_cancelado` e `status` conferidos com a soma das baixas, soma das parcelas = total da compra conferida no `COMMIT`), `baixas_pagar` (imutável; `PAGAMENTO` em `DINHEIRO | PIX | BOLETO`, o estorno como baixa negativa, `CANCELAMENTO` da compra desfeita; cada pagamento tem exatamente um lançamento de caixa, conferido no `COMMIT`), `fechamentos_semana` (imutável), a categoria do sistema "Pagamento a fornecedor", as origens `MANUAL` e `PAGAMENTO_FORNECEDOR` em `lancamentos_caixa` (com `motivo` e `conteudo`), o gatilho que recusa lançamento com data em semana fechada, `proteger_semana` (a semana só fecha com o registro e nunca muda nem reabre), a segunda trava da semana em `proteger_acerto()` e na maleta (`FOR SHARE`) e `categorias_caixa` com tipo imutável e categoria do sistema intocável. Nenhuma migração publicada foi editada.
- **`ServicoDeContasAPagar`** (único que grava em `contas_pagar` e `baixas_pagar`): gerar (1 a 24 parcelas mensais, `Dinheiro.dividirEmParcelas`, uma geração por compra), pagar (parcial ou inteiro, nunca acima do saldo, só dinheiro, Transferência (PIX) e boleto, data opcional e nunca no futuro), estornar o pagamento (motivo, um só por pagamento) e cancelar o que está em aberto da compra desfeita (chamado pelo `ServicoDeCompras.desfazer`, na mesma transação).
- **`ServicoDeCaixa`** ganha `lancarManual`, `estornarManual`, `lancarPagamentoAFornecedor` e `estornarPagamentoAFornecedor` e continua o único escritor de `lancamentos_caixa`; **`ServicoDeLancamentosManuais`** (validação, idempotência e auditoria), **`ServicoDeCategoriasDoCaixa`**, **`FonteDeAvisosDoCaixa`** (aviso "O caixa está negativo" enquanto o saldo for menor que zero; o caixa nunca bloqueia) e o extrato com fornecedor e motivo.
- **`ServicoDeSemanas.fechar`:** só a dona, só semana que já terminou e sem acerto em andamento; o formulário de semana não aceita mais "Fechada"; semana fechada não se edita nem se exclui; iniciar acerto, rascunho e envio de maleta e lançamento de caixa leem a semana com `FOR SHARE` como última trava.
- **Rotas** (todas `/api/dona/...`, só ADMIN): `POST/GET /compras/{id}/contas-a-pagar`, `GET /contas-a-pagar` (fornecedor, situação com "Vencida" calculada, vencimento de/até), `GET /contas-a-pagar/{id}`, `POST /contas-a-pagar/{id}/pagamentos`, `POST /contas-a-pagar/pagamentos/{baixaId}/estorno`, `GET/POST/PUT /caixa/categorias` (mais `inativar` e `reativar`), `POST /caixa/lancamentos` e `/{id}/estorno`, `GET /caixa/saldo`, `POST /semanas/{id}/fechar`. Todo comando tem UUID do aplicativo: 201, 200 no reenvio igual, 409 com o mesmo id e conteúdo diferente.
- **Clientes da API:** `npm run api:clientes` regerou só o cliente da dona; o diff do `packages/api-client-vendedor` ficou **vazio** (a barreira do vendedor).
- **Corona Gestão:** a opção "Fechada" saiu do formulário de semana (a situação continua na lista); o botão **Fechar semana** e as telas são da 7.10.
- **Documentos:** especificação v1.21 (§7.4, §7.5, §9), ADR-0012 ("Como ficou" nos itens 3.8, 11 e 12), glossário (mensagens novas) e nota de deploy em `entrega-corona-vendas.md` (sem migração local nova; a ordem não importa para o Corona Vendas).

**Decisões que tomei (as do plano aprovado, 1 a 11; o que mais chama atenção)**

- Estornar o pagamento de uma compra **já desfeita** cancela, na hora, o que voltou a ficar em aberto (assim a parcela termina `CANCELADA` e o `CHECK` da situação continua valendo).
- O formulário de semana com "Fechada" recebe 400 com o campo `situacao` (padrão das validações da API), não 422.
- Uma semana que já estava `FECHADA` antes da V046 continua fechada e imutável, mas não tem linha em `fechamentos_semana` (não há produção ainda).
- O estorno de estorno de lançamento manual tem código próprio (`LANCAMENTO_DO_CAIXA_E_ESTORNO`, 409).

**Testes** (PostgreSQL do Testcontainers): banco (17), gerar contas (7), pagar e estornar (8), compra desfeita (4), categorias (7), caixa manual (7), fechar semana (7), concorrência com threads reais e tempo limite de 10 s (4), permissões (1, todas as rotas), arquitetura (6), pull do vendedor sem as tabelas novas; Corona Gestão, formulário de semana sem "Fechada". Os testes antigos que fechavam semana por SQL ou pelo formulário ganharam o auxiliar `SemanasDeTeste.fecharNoBanco` (a regra mudou: só se fecha com o registro).

**Resultado final:** `npm run api:test` passa (1563 testes, nenhuma falha); Corona Vendas 637 testes (1 ignorado, como antes); Corona Gestão 666 testes em 46 arquivos; `npm run test:duas-pontas -w @corona/testes-sync` passa (26 testes em 7 arquivos); `npm run lint`, `npm run typecheck` e `npm run format:check` limpos; `npm run seller:empacotar` e `npm run barreira:varrer` com varredura limpa; o diff de `packages/api-client-vendedor` ficou vazio.

**Não fiz**

- As telas de contas a pagar, caixa e o botão Fechar semana (7.10); as conciliações com rota (7.8); os relatórios (7.12).
- Refazer o parcelamento de uma compra (hoje só desfazendo a compra), cheque, cartão, entrada, multa e juros (fora do escopo).

## Tarefa 7.8: conciliações da Fase 7

Issue #152, PR 169 (tarefa crítica: plano aprovado no PR, decisões 1 a 8 confirmadas pela orquestradora, com o ajuste "em cada conciliação, um teste que estraga o dado e prova que a diferença aparece"). As nove conciliações do ADR-0012, item 17 (C1 a C10, sem a C6, que fica vaga), viram código de produção, com rota da dona e tela no Corona Gestão. Só leitura; o Corona Vendas não foi tocado.

**O que entrou**

- **`ConciliacoesDaFase7`** (pacote `api/conciliacoes`): uma consulta SQL por conciliação, todas num retrato só do banco (`@Transactional(readOnly = true, isolation = REPEATABLE_READ)`), em Real com os valores gravados. Cada conferência devolve quantos registros olhou e as diferenças (tipo e id do registro, descrição em português simples, esperado, encontrado e unidade: reais, peças, registros ou porcentagem). A única conta de dinheiro em Java é a da C3, só por `Dinheiro.aplicarPorcentagem`. Nunca corrige nada.
- **Rota** `GET /api/dona/conciliacoes` (só ADMIN; `?chave=C4` devolve uma; chave desconhecida, inclusive a C6, dá 400 com o campo `chave`). Não há rota de conciliação para o vendedor.
- **Tela** "Conferência do dinheiro" no Corona Gestão (grupo "Conferência" do menu): as nove, cada uma com "Conferidos: N" e "Tudo certo" ou a lista do que não bate com "Devia ser ...; está ...", em Real e peças em número; sem botão de corrigir; texto fixo "O que aparece aqui é erro do sistema, não da loja. Não corrija nada à mão: avise o suporte.".
- **`ServicoDeEstoque.SQL_DIFERENCAS_COM_MALETAS`:** a consulta de "estoque da loja + maletas" virou constante pública (sem mudar o comportamento), e a C7 usa a mesma.
- **Testes antigos:** `ConciliacaoDasContasTest`, `ConciliacaoDoAcertoTest`, `ConciliacaoDoDinheiroDoVendedorTest` e os apoios de contas e de trocas deixaram de copiar SQL e chamam a consulta de produção (`ConciliacoesNosTestes`).
- **Clientes da API:** `npm run api:clientes` regerou só o cliente da dona; o diff de `packages/api-client-vendedor` ficou **vazio**. Sem migração.
- **Documentos:** especificação v1.22 (§13), ADR-0012 ("Como ficou" no item 17), glossário.

**Decisões que tomei (as do plano aprovado; o que mais chama atenção)**

- A C1 vale para toda venda concluída, não só a prazo; a linha "no total" não existe (se cada venda bate, a soma bate, e o total não teria referência para filtrar).
- A C4 confere cada lançamento do caixa (recebimento, pagamento a fornecedor, pagamento ao vendedor, origem existente e sinal). O saldo do caixa é sempre a soma dos lançamentos, sem atalho gravado, então "saldo = soma" vale por construção e não virou linha.
- A C5 aceita `PARCIAL` também para a parcela abatida por devolução (V042): o teste das devoluções de venda a prazo achou isso na primeira rodada.
- A C9 cobra o estorno de venda e a devolução só quando aconteceram antes do último acerto fechado do vendedor (depois dele, o evento entra no acerto seguinte, e não é erro). A devolução usa `updated_at`, para a devolução que saiu de "em revisão" depois do acerto não acusar.
- A C7 conta "guardado = soma dos lançamentos" mais a consulta de estoque com maletas; a peça com defeito separada já está coberta pela avaria do acerto (origem `ACERTO`), sem regra nova.
- O estrago nos testes roda a mesma classe sobre a conexão do dono das tabelas (gatilhos de usuário desligados, transação desfeita), então nada estragado chega a ser gravado.

**Testes** (PostgreSQL do Testcontainers): `ConciliacoesDaFase7Test` (28: a massa completa zerada pela rota e uma a uma, e **um teste que estraga o dado de propósito em cada conciliação**: C1 pagamento sem baixa e venda desfeita com parcela; C2 falta a mais, recebimento com outro valor, conta do restante apagada; C3 comissão apagada, percentual e valor da linha trocados; C4 lançamento apagado, em dobro, sem origem, pagamento a fornecedor sem saída, saída com valor positivo e pagamento ao vendedor sem saída; C5 valor pago sem baixa nas contas a receber e a pagar e situação "paga" com saldo; C7 estoque a mais, movimento apagado e peça a mais na maleta; C8 perda apagada; C9 evento de venda, de estorno e de devolução apagados e o mesmo evento em dois acertos; C10 parcela com um centavo a mais), `SoLeituraDasConciliacoesTest` (3: nenhuma tabela muda, métodos só de leitura com retrato único, a classe não tem texto que escreve), `PermissoesDasConciliacoesTest` (4); Corona Gestão: tela com tudo certo, com diferenças, sem conexão, com erro da API, conferir de novo, ponte e validação.

**Resultado final:** `npm run api:test` passa (1598 testes, nenhuma falha; na primeira rodada um teste de renovação de token falhou por janela de tempo, passou sozinho e na rodada seguinte, e não tem relação com a tarefa); Corona Gestão 673 testes em 47 arquivos; Corona Vendas 637 testes (1 ignorado, como antes), sem mudança de código nele (por isso o `barreira:varrer` não foi necessário e o diff de `packages/api-client-vendedor` ficou vazio); `npm run test:duas-pontas -w @corona/testes-sync` passa (26 testes em 7 arquivos); `npm run lint`, `npm run typecheck` e `npm run format:check` limpos.

**Não fiz**

- A rodada diária com aviso em "O que precisa da sua atenção" (Fase 8), o cheque (a C6 continua vaga), a entrada, o botão de corrigir (não existe de propósito) e os relatórios (7.12).
- Guardar o resultado de cada rodada (Fase 8): por isso não há migração.

## Tarefa 7.9: telas do acerto

Issue #153, PR 170 (plano aprovado no PR, decisões 1 a 8 confirmadas pela orquestradora, com o ajuste "no PDF do acerto, mostre o percentual e a soma em Guarani que decidiram a meta, como na ficha"). **Só telas do Corona Gestão** (e a ponte): as rotas são da 7.4 a 7.8 e os clientes da API da dona já estavam regenerados. Nada de backend, migração, Corona Vendas, `packages/shared`, `packages/ui` nem `packages/api-client-vendedor` (diff vazio).

**O que entrou**

- **Ponte** (`shared/acertos.ts`, `shared/validacao-acertos.ts`, `main/api.ts`): `acertos.listar/iniciar/ver/ler/desistir/previa/previaComDecisoes/fechar`, `pendencias.listar/resolver` e `comissao.ler/alterar`, cada uma na rota que já existia. A ponte só repassa chaves conhecidas (total, comissão e percentual no pedido são recusados), só aceita Dinheiro e Transferência (PIX) como forma de pagamento (cheque, cartão e entrada não existem), parcelas de 1 a 24, datas AAAA-MM-DD, valores em texto com ponto, motivo obrigatório e um UUID por comando.
- **Menu** "Acerto" com **Acertos**, **Pendências** e **Comissão e meta**; o botão **Fazer acerto** da tela inicial deixou de ser "em breve" (o teste do "Em breve" passou a usar **Cadastrar produto**).
- **Acertos:** lista com filtro de vendedor e situação (Em acerto, Acertado, Desistido); **Começar acerto** (id e semana opcional; aviso quando o vendedor não tem maleta com ele); **Contagem** com o leitor da maleta (Peça boa ou Com defeito, quantidade, **Desfazer leitura**, avisos de venda nova e de envios aguardando, últimas leituras com quem leu e de qual computador); **Desistir do acerto** com motivo.
- **Conferência do acerto** (prévia): peças com foto, sobra e revisão que impedem fechar (com o que fazer), faltas (Cobrar agora, Cobrar depois, A loja assume com motivo), defeitos a aprovar, o que não foi aceito, pendências antigas, comissão por venda, meta com a soma em Guarani e o percentual, totais e a frase da cotação. Os números são sempre os da última prévia do servidor; enquanto a nova não chega aparece "Calculando..." e **Fechar acerto** fica desligado; só vale a resposta da última chamada.
- **Pagamento:** Dinheiro, Transferência (PIX), Boleto (valor e vencimento), o que ficou devendo (parcelas e data da primeira), contas antigas do vendedor pagas junto e o acerto a favor do vendedor (crédito ou pagar agora). Cada recebimento leva um UUID próprio.
- **Fechar acerto:** pergunta com o resumo; manda o id do fechamento, as decisões, o pagamento e o `liquidoMostrado` (o líquido da última prévia, só para o servidor conferir). O id vale até uma decisão ou um valor mudar. 201 ou 200 mostram a ficha; sem internet diz "O acerto não foi fechado" e reenvia o mesmo id; 409 "números mudaram" (e sobra ou revisão que apareceu) volta à prévia nova com outro id; erros de pagamento, data e semana fechada com o que fazer.
- **Ficha do acerto fechado** (só leitura): peças, pendências com a decisão, comissão com o percentual gravado e a soma da meta, totais, recebimentos (o desfeito marcado), contas geradas, Dólar e Guarani **pela cotação gravada no acerto** (nunca a do dia); **Imprimir acerto** gera o PDF (A4 em pé, mesmo gerador à mão da nota) com os mesmos textos e valores, inclusive o percentual e a soma em Guarani da meta.
- **Pendências:** só "Em aberto" tem **Resolver** (A loja assume ou A peça apareceu, esta só para falta), com motivo e UUID do comando. **Comissão e meta:** grava a meta em Guarani e os dois percentuais, recusando antes de mandar o que o servidor também recusa.
- **Documentos:** glossário (parte 16).

**Decisões do plano (as 8 confirmadas) e o que mais chama atenção**

- A tela **nunca calcula dinheiro**: não soma, não multiplica, não arredonda, não converte para decidir nada. As únicas contas na tela são formatar, comparar duas coisas que a API mandou (soma da meta contra a meta, para dizer "Bateu a meta", igual à regra do servidor) e olhar o sinal de um valor (para mostrar "A loja deve ... ao vendedor"). Por isso a tela **não mostra "quanto faltou para a meta"**: a prévia do acerto não devolve esse número e a tela não o subtrai (a rota do vendedor devolve, e a 7.11 mostra).
- A prévia é um mapa sem tipo no OpenAPI: o leitor em `previa.ts` confere a forma; campo que falta ou valor que não é dinheiro vira "Não deu para ler o acerto. Tente de novo.", nunca número inventado.
- As decisões nascem da prévia sem decisões e cobrem exatamente o que o servidor exige ao fechar (toda peça com falta, todo defeito contado, todo valor não aceito); falta de motivo em "A loja assume" não vai ao servidor e desliga **Fechar acerto**. Texto digitado espera 0,4 s antes de pedir a prévia nova.
- A cotação da prévia (que só traz a data) usa a cotação do dia quando a data é a mesma; senão só Real, com o aviso "Sem cotação".

**Testes** (Corona Gestão, 769 no total): `validacao-acertos.test.ts` (10), `api.test.ts` (rota de cada operação e corpo do fechar), `acertos.test.tsx` (19: menu, lista, começar, contagem, desistir), `previa.test.tsx` (16), `fechamento.test.tsx` (21: quando dá para fechar, pagamento, idempotência, 409 e erros), `ficha.test.tsx` (9), `pdf-acerto.test.ts` (3), `pendencias.test.tsx` (8), `comissao.test.tsx` (6), `textos-acertos.test.ts` (2: sem termo técnico nem palavra do bloco proibido, "PIX" só como "Transferência (PIX)").

**Resultado final:** `npm run lint`, `npm run typecheck` e `npm run format:check` limpos; `npm run owner:build` passa; testes do Corona Gestão: 769 passam em 56 arquivos (o teste de colar foto de `produtos.test.tsx` é lento e às vezes passa de 1 s quando a máquina está cheia, sem relação com esta tarefa; passa sozinho e com `--maxWorkers=4`). `npm run api:test` e `barreira:varrer` não foram necessários (nenhum arquivo do backend nem do Corona Vendas mudou).

**Não fiz**

- As telas de caixa, contas a pagar e a receber e recebimento avulso (7.10), a tirada do campo "Comissão (%)" do cadastro do vendedor (7.10), as telas do vendedor (7.11) e os relatórios financeiros (7.12), nem Excel e CSV do acerto.
- "Quanto falta para a meta" na conferência (a API da dona não devolve; ver acima). Sugestão: a prévia pode devolver `faltaParaMetaGuarani`, como a do vendedor já faz.
- Tipar a prévia no OpenAPI (hoje é um mapa sem tipo; fica a sugestão).

## Tarefa 7.10: telas do financeiro

Issue #154, PR 171 (tarefa não crítica). **Só telas do Corona Gestão** (e a ponte): as rotas são das tarefas 7.6 a 7.8 e o cliente da API da dona já estava regenerado. Nada de backend, migração, Corona Vendas, `packages/shared`, `packages/ui` nem `packages/api-client-vendedor` (diff vazio).

**O que entrou**

- **Ponte** (`shared/financeiro.ts`, `shared/validacao-financeiro.ts`, `main/api.ts`): `caixa.extrato/saldo/lancar/estornar`, `caixa.categorias/criarCategoria/renomearCategoria/inativarCategoria/reativarCategoria`, `contasAPagar.listar/ver/pagar/estornar`, `contasAReceber.listar`, `recebimentos.listar/receber/estornar` e `semanas.fechar`, cada uma na rota que já existia. A ponte só repassa chaves conhecidas, só aceita Dinheiro, Transferência (PIX) e Boleto (cheque, cartão e entrada não existem), valor em texto com ponto maior que zero, datas AAAA-MM-DD, motivo obrigatório e um UUID por comando.
- **Menu Financeiro:** Caixa, Categorias do caixa, Contas a pagar, Contas a receber e Recebimentos do vendedor.
- **Caixa:** saldo (com o aviso de caixa negativo), resumo e extrato do período (De e Até), **Lançar no caixa** (Entrou ou Saiu, categoria da loja ativa e do mesmo tipo, forma, data, descrição, valor) e **Estornar lançamento** (só o que foi lançado à mão, com motivo).
- **Categorias do caixa:** Nova categoria (tipo fixo depois de criada), Renomear, Inativar e Reativar; as categorias do sistema não têm ações.
- **Contas a pagar:** lista com filtros (fornecedor, situação, vencimento) e página; **Registrar pagamento** (forma, valor, data opcional); **Ver pagamentos** da parcela com **Estornar pagamento** (só o pagamento que ainda vale, com motivo).
- **Contas a receber:** consulta dos dois lados (do cliente ao vendedor e do vendedor à loja), com filtros de vendedor, situação e de quem é a conta.
- **Recebimentos do vendedor:** lista (Desfeito e Estorno marcados), **Registrar recebimento** avulso (vendedor, conta a baixar opcional, forma, valor) e **Estornar recebimento** (com motivo e, para o que pagou um acerto, o novo vencimento opcional).
- **Fechar semana:** botão **Fechar semana** na linha de cada semana aberta de Cadastros, Semanas, com pergunta clara e sem volta; a recusa do servidor aparece com o que fazer.
- **Comissão fora do cadastro do vendedor:** o campo "Comissão (%)" saiu do formulário do vendedor, da ponte e da coluna do relatório de vendedores; a comissão agora é só a da tela Comissão e meta (7.9).
- **Documentos:** glossário (parte 17).

**Decisões e o que mais chama atenção**

- A tela **nunca calcula dinheiro**: só formata o que a API mandou. Dólar e Guarani só aparecem nas listas de contas e de recebimentos e no saldo, **pela cotação do dia** (com a frase de rodapé); no extrato do caixa o valor de cada lançamento aparece **só em Real**, porque a API não devolve a cotação do dia de cada lançamento e a tela não inventa uma.
- Todo comando leva um UUID gerado quando a janela ou o formulário abre e **reaproveitado** em toda nova tentativa (sem internet, o mesmo botão manda o mesmo id); depois de feito, o próximo comando nasce com id novo.
- "Caixa negativo": o aviso usa o `negativo` e o saldo que a API manda (a tela só tira o sinal para escrever "negativo em R$ X").
- O teste da conferência do dinheiro passou a procurar os títulos só dentro da área principal da tela (o menu agora também tem um item "Caixa"). O teste de colar foto de `produtos.test.tsx`, que passava de 1 s com a máquina cheia, ganhou espera de 5 s só nele (nenhuma verificação foi trocada).

**Testes** (Corona Gestão, 827 no total): `validacao-financeiro.test.ts`, `api.test.ts` (rota de cada operação do financeiro), `caixa.test.tsx` (caixa e categorias), `contas-a-pagar.test.tsx`, `recebimentos.test.tsx` (contas a receber e recebimentos), `fechar-semana.test.tsx`, `textos-financeiro.test.ts` (sem termo técnico, formas, PIX só como "Transferência (PIX)") e os testes do cadastro do vendedor trocados (sem o campo de comissão).

**Resultado final:** `npm run lint`, `npm run typecheck` e `npm run format:check` limpos; `npm run owner:build` passa; testes do Corona Gestão: 827 passam em 62 arquivos. `npm run api:test` e `barreira:varrer` não foram necessários (nenhum arquivo do backend nem do Corona Vendas mudou).

**Não fiz**

- **Gerar contas a pagar** de uma compra (a rota existe, mas a tela de compras é outra tarefa), **estornar recebimento de cliente** (a lista de contas não traz o id do pagamento do cliente) e **editar ou cancelar** parcela.
- Telas do vendedor (7.11) e relatórios financeiros (7.12).
- Cotação do dia de cada lançamento do caixa no extrato (a API não devolve; sugestão: devolver a cotação do lançamento).

## Tarefa 7.11: Corona Vendas, acerto, parcelas e recebíveis

Issue #155, PR 172 (tarefa não crítica). **Só Corona Vendas e testes**: nenhuma rota, migração nem arquivo do backend; o servidor já tinha tudo desde a 7.3, 7.5 e 7.6. Nada de `packages/shared`, `packages/ui` nem `packages/api-client-vendedor`.

**O que entrou**

- **Ponte** (`shared/operacoes.ts`, `shared/validacao.ts`): `parcelamento` opcional na venda e na troca (só `quantidade` de 1 a 24 e `primeiroVencimento`; qualquer outro campo, como valor de parcela ou entrada, é recusado), `recebimentos.receber` (só Dinheiro e PIX), `recebimentos.estornar`, `recebiveis.ver`, `acertos.listar`, `acertos.ver` e `comissao.previa`.
- **Motor local** (`main/sync/operacoes-da-fase-7.ts`, `operacoes-locais.ts`, `outbox.ts`): `VENDA` e `TROCA` levam o `parcelamento` no JSON exato (as vendas sem parcelamento continuam com os mesmos bytes); `RECEBIMENTO_CLIENTE` e `ESTORNO_RECEBIMENTO_CLIENTE` gravam o pagamento provisório (`posterior`, e o `ESTORNO_PAGAMENTO` negativo ligado ao recebimento) e a operação na mesma transação. O computador recusa só o que o servidor também recusaria, com os mesmos textos: sem saldo a prazo, vencimento antes de hoje ou depois de um ano, recebimento acima do que falta, venda já paga ou estornada, estorno de outro dia (só a dona), de pagamento da hora da venda, repetido ou de venda com devolução. **Nunca calcula parcela, baixa de parcela nem comissão**: as parcelas, as baixas e as contas só chegam do servidor pelo pull.
- **Consultas locais** (`main/local/consultas-do-acerto.ts`): recebíveis (vendas a prazo com as parcelas do servidor, o que já foi recebido e o que ainda pode ser estornado, e as contas que o vendedor deve à loja) e o acerto fechado (resumo, peças, comissão de cada venda, pendências, o que pagou, contas), lidos das cópias do computador, só do vendedor dono. `comissao.previa` é a única que vai à rede (a API calcula; sem internet a tela explica).
- **Telas** (`renderer/telas`): `EscolhaDeParcelas` (em Vender e em Trocar, só aparece quando sobra valor a prazo), `TelaAReceber` e `TelaMeuAcerto`, abertas por dois botões em **Meus números**; os cinco botões da tela inicial não mudam (§19.7). Textos só em `textos.ts`; glossário, parte 18.
- **Duas pontas** (`testes-sync`): o oráculo ganhou as nove tabelas da Fase 7 (`contas_receber`, `baixas_receber`, `recebimentos_vendedor`, `acertos`, `leituras_acerto`, `itens_acerto`, `acerto_eventos`, `comissoes`, `pendencias_acerto`) e há dois cenários novos: 21 (venda a prazo em 3 parcelas, dois recebimentos e um estorno sem internet; o servidor divide 33,33 + 33,33 + 33,34 e distribui os 50,00; o computador recebe as parcelas) e 22 (corte depois de gravar e 502 no envio do recebimento: um só no servidor).

**Decisões e o que mais chama atenção**

- A tela **não calcula dinheiro**: o "Falta receber" de uma venda que ainda não chegou à loja é a prévia do computador e leva o aviso "Estimativa"; o valor de cada parcela, a comissão, o líquido e a meta são os que o servidor gravou. A comparação com zero (meta batida, líquido negativo) só olha o sinal do que o servidor mandou.
- **Pedir o acerto e bipar as sobras não entraram**: o prompt desta tarefa não os incluiu e as rotas são online (nada novo na outbox); a dona começa o acerto pela tela da 7.9.
- Os botões ficam em Meus números (e não na tela inicial) para não mudar a especificação (§19.7).
- O vencimento padrão (um mês depois de hoje, em Brasília) e a regra de "até um ano" são as do servidor; o computador também recusa o vencimento antes de hoje (o servidor só recusa no envio online).
- Devolução e troca de venda a prazo já abatiam as parcelas desde a 6.6 e a 6.8 (linha "Abatido do que o cliente deve"): conferido e mantido; a troca passou a aceitar parcelas na diferença.

**Testes** (Corona Vendas, SQLCipher de verdade no motor e nas consultas): `validacao-da-fase-7.test.ts`, `operacoes-da-fase-7.test.ts` (21), `consultas-do-acerto.test.ts` (15), `parcelas-e-acerto.test.tsx` (31, inclui as palavras proibidas do glossário e a ausência de custo, caixa e cheque), um teste a mais em `devolucao-e-troca.test.tsx` (troca com parcelas) e os dois cenários das duas pontas.

**Não fiz**

- Pedir o acerto e bipar as sobras no Corona Vendas (acima); estornar recebimento de outro dia (é só da dona); relatórios financeiros (7.12).
- Nota de deploy: [entrega-corona-vendas.md](../entrega-corona-vendas.md#nota-de-deploy-da-tarefa-711-parcelas-e-recebíveis-no-corona-vendas).

## Tarefa 7.12: relatórios financeiros

**Feito (PR 173).** Backend: `ConsultasFinanceiras` e `RelatoriosFinanceirosController` em `/api/dona/relatorios/financeiro/` (só ADMIN, só leitura, mesmo motor dos relatórios de cadastro, estoque, maleta e devolução: linhas, rodapé e detalhamento saem das mesmas condições; soma em `NUMERIC` no banco, nunca float):

| Rota | Relatório (tela) | Cotação de Dólar e Guarani |
| --- | --- | --- |
| `/acertos` | Acertos por vendedor e semana | gravada no fechamento (`acertos.cotacao_id`) |
| `/pendencias` | Faltas e pendências | a do acerto |
| `/esperado-contado` | Esperado e contado | a do acerto |
| `/comissoes` | Comissões por vendedor e semana | a do acerto |
| `/contas-a-receber` | Parcelas a receber (cliente e vendedor; Vencida é calculada pela data de hoje em Brasília) | dia em que a parcela nasceu |
| `/contas-a-pagar` | Parcelas a pagar | dia em que a parcela nasceu |
| `/inadimplencia` | Quem está devendo (só vencidas, faixas de atraso no rodapé) | dia em que a parcela nasceu |
| `/fluxo-de-caixa` | Fluxo do caixa (entradas, saídas, saldo; estorno marcado) | dia do lançamento |
| `/recebimentos-por-forma` | Recebimentos por forma de pagamento (dinheiro, transferência, boleto) | dia do recebimento |

Gestão: nove telas no grupo **Relatórios financeiros** (definições em `nucleo/relatorios-financeiros.ts`, textos em `textos-relatorios-financeiros.ts`, glossário atualizado), com filtros, rodapé, detalhamento e PDF, Excel e CSV pelo mesmo caminho dos demais; as colunas de dinheiro levam Real, Dólar e Guarani de cada linha. Cliente da dona regenerado; `apps/seller-app` e `api-client-vendedor` sem nenhuma mudança (o vendedor nunca recebe estes relatórios).

**Testes.** `RelatoriosFinanceirosTest` (13) sobre a massa da Fase 7, com os números calculados à mão nos comentários: acima da meta (vendido 150, devolvido 50, comissão 40, faltas 50, valor do acerto 110), abaixo da meta (comissão 15, valor 35), falta cobrada e falta que a loja assume, esperado 5 e contado 3 boas e 1 com defeito, recebimentos do vendedor X (dinheiro 10, transferência 8, boleto 10, total 28 = entradas do caixa), caixa do Y (saída de 35), parcelas a pagar (340, pago 160, cancelado 30, falta 150), parcelas a receber (pago 70, cancelado 100), inadimplência avançando o relógio, 401 sem login e 403 para o vendedor nas nove rotas, filtro inválido com erro em português, e as nove conciliações da massa continuam zeradas. Na Gestão: `relatorios-financeiros.test.tsx` (menu, três moedas da linha e do rodapé, filtro que vai ao servidor, arquivo CSV, validação da ponte).

**Não fiz / desvios.** DRE, lucro por produto e por vendedor (Fase 8, como no plano). Comissão "a pagar": a comissão é paga por abatimento no fechamento (ADR-0012), então todo acerto fechado conta como "Paga no acerto"; a prévia do que ainda não foi fechado é calculada só no Corona Vendas, por vendedor. "Descontos concedidos" é uma coluna e um total do relatório de acertos (não há relatório separado). As duas telas de parcelas se chamam "Parcelas a receber" e "Parcelas a pagar" para não repetir as telas de trabalho do Financeiro. Sem migração.

**Resultado final.** `npm run api:test` completo: 1611 testes, 0 falhas. Gestão: 832 testes, `lint`, `typecheck`, `format:check` e `owner:build` limpos. Um teste antigo era instável (`ContasDoVendedorTest`, ordem das baixas desfeitas, que seguia o UUID): a consulta do estorno passou a ordenar por vencimento e parcela, e o teste ficou como estava. Sem mudança no sync nem no Corona Vendas (`test:duas-pontas` e `barreira:varrer` não se aplicam).

## Tarefa 7.13: fechamento da Fase 7

Issue #157, PR 174 (tarefa não crítica). **Só testes e documentos:** nenhuma regra de negócio mudou, nenhuma migração, nenhuma linha de código de produção.

**O que entrou**

- **Teste de ponta a ponta do ciclo** (`backend/src/test/java/br/com/coronasemijoias/api/conciliacoes/CicloDaFase7Test.java`), pelas rotas HTTP, numa história só, com os números feitos à mão (cotação de R$ 0,0009 por guarani: a meta de 700.000 Gs é R$ 630,00):
  - o vendedor A vende 2 peças de R$ 315,00 à vista e 1 a prazo (R$ 15,00 na hora, 3 parcelas de 100,00) e recebe a primeira parcela (`PAGA`, `ABERTA`, `ABERTA`);
  - a devolução da venda a prazo abate as duas parcelas que faltam (200,00) e volta o que foi pago (115,00): `valor_devolvido` 315,00, `valor_abatido` 200,00, `valor_reembolsado` 115,00;
  - a dona inicia o acerto e conta 4 peças boas (3 da maleta e a devolvida, que aparece como "para a loja"); a prévia fecha sem sobra nem falta: vendido líquido 630,00 = **exatamente 700.000 Gs, meta batida, 40%**, comissão 252,00, líquido 378,00;
  - um fechamento com o `liquidoMostrado` errado é recusado (409) e nada muda; o certo fecha (201) e o reenvio igual devolve 200;
  - o acerto grava recebido 300,00 (200,00 em dinheiro e 100,00 por Transferência) e ficou devendo 78,00 em duas contas de 39,00; a maleta fica `ACERTADA`; o vendedor lê a ficha dele sem custo;
  - o caixa recebeu os 300,00 sozinho; um pagamento de 100,00 a fornecedor e um lançamento manual de 20,00 deixam o saldo em +180,00 sobre o de antes;
  - o vendedor B vende R$ 629,99 (um pouco abaixo da meta): **30%**, comissão 189,00, líquido 440,99;
  - fecha uma semana que já terminou e prova que o caixa recusa lançamento com data nela (`DATA_EM_SEMANA_FECHADA`);
  - as nove conciliações (C1 a C10, sem a C6) rodam sobre tudo o que o ciclo criou e não acusam diferença.
- **Roteiro do teste de uso** em linguagem da loja: [roteiro-teste-fase-7.md](../roteiro-teste-fase-7.md) (vendedor, acerto da dona, financeiro e relatórios, na forma do roteiro da Fase 6).
- **Especificação v1.23:** a §15 passa a mostrar a Fase 7 como entregue no código, e a §22 ganha o histórico (sem mudar regra).
- **Documentos:** este fechamento e o `PROGRESSO.md`.

**Por que não há cenário Playwright (`npm run e2e`)**

O `testes-e2e` abre só o **Corona Gestão** (a dona), e o próprio README diz "Corona Vendas: só depois da Fase 4" (hoje nenhum cenário abre o Corona Vendas). O ciclo da Fase 7 começa na venda e no recebimento do vendedor e depende da API; o motor do Corona Vendas contra a API real já tem os testes de duas pontas (cenários 21 e 22 da 7.11). Por isso o ciclo foi coberto por um teste de integração do backend, que percorre as mesmas rotas que as duas telas usam, com PostgreSQL de verdade e os valores calculados à mão. As telas já têm os testes de componentes das tarefas 7.9 a 7.12. Um cenário Playwright da dona fazendo o acerto fica como sugestão (abaixo). Como nenhum cenário novo foi criado, `npm run e2e` não foi rodado.

**Achados:** nenhum defeito. O teste passou de primeira, com os números feitos à mão.

## Resumo final da Fase 7

A semana fecha de ponta a ponta, só com o que a dona decidiu (sem entrada, sem cheque, comissão por meta de 700.000 Gs com 30% ou 40%):

- **Venda a prazo:** o vendedor escolhe quantas parcelas e o primeiro vencimento; o servidor divide (de 1 a 24, mensais), distribui o recebimento posterior pelo vencimento, abate as parcelas na devolução e na troca e cancela o que está em aberto no estorno da venda.
- **Acerto:** a dona inicia (a maleta trava; a venda do envio só entra se for anterior ao início), conta com o leitor (boa ou com defeito), confere a prévia (sobra e devolução em revisão impedem fechar; falta: cobrar agora, depois ou a loja assume), escolhe o pagamento e fecha, numa transação só, com idempotência por UUID e conferência do valor mostrado. O servidor recalcula tudo e grava itens, eventos, comissões (com o percentual na linha), pendências, movimentos de estoque e a cotação do acerto. Desistir não grava dinheiro.
- **Dinheiro do vendedor:** recebimentos em dinheiro e Transferência (PIX), boleto como conta, o que ficou devendo em parcelas, contas antigas pagas junto, acerto a favor dele como crédito ou pagamento na hora, recebimento avulso e estorno.
- **Caixa e contas:** caixa automático, caixa manual com categorias e estorno, aviso de caixa negativo (nunca bloqueia), contas a pagar das compras (parcial, inteiro, estorno, compra desfeita) e fechar semana (só a dona, só depois do fim, sem acerto em andamento, nunca reabre).
- **Conferência:** nove conciliações de produção (C1 a C10, sem a C6), só leitura, com rota e tela, cada uma com um teste que estraga o dado de propósito.
- **Telas:** Corona Gestão com Acerto, Financeiro, Conferência do dinheiro e nove relatórios financeiros (Real, Dólar e Guarani; PDF, Excel e CSV); Corona Vendas com parcelas, A receber e Meu acerto e minha comissão (prévia com quanto falta para a meta).
- **Segurança:** o vendedor nunca recebe custo, caixa, contas a pagar nem dados de outro vendedor (barreira, 403 cruzado e testes de pull).

**Números** (resultado final de cada tarefa): `api:test` chegou a 1611 testes na 7.12; Corona Gestão, 832; Corona Vendas, 637 (6.6 e 7.6); duas pontas, 26 testes na 7.6, mais os cenários 21 e 22 da 7.11.

## O que ficou para a Fase 8 (e sugestões)

- **Fase 8 (plano):** rodada diária das conciliações com aviso em "O que precisa da sua atenção" e guardar o resultado de cada rodada; DRE, lucro por produto e por vendedor, curva ABC; comissão "a pagar" prevista no relatório (hoje a prévia só existe por vendedor, no Corona Vendas).
- **Corona Vendas:** o vendedor **pedir o acerto** e **bipar as sobras** pelo aplicativo (as rotas do vendedor existem desde a 7.4; falta a tela e, se for para funcionar sem internet, o tipo na outbox).
- **Telas do financeiro que a 7.10 deixou:** botão para **gerar as contas a pagar** de uma compra (a rota existe), **estornar recebimento de cliente** pela dona (a lista de contas não traz o id do pagamento), editar ou cancelar parcela e a cotação do dia de cada lançamento no extrato do caixa (a API não devolve).
- **Conferência do acerto na Gestão:** "quanto falta para a meta" (a prévia da dona não devolve `faltaParaMetaGuarani`; a do vendedor devolve) e **tipar a prévia no OpenAPI** (hoje é um mapa sem tipo).
- **Pequenos:** o relatório "Vendedores: limite e situação" distinguir "Sem limite" de "Limite não definido" (campo novo no relatório); o texto da rota "Cadastrar vendedor" no OpenAPI ainda diz "Limite e comissão começam em zero"; testes de integração do 409 `SEM_COTACAO_PARA_O_ACERTO` e `REVISAO_PENDENTE_NO_ACERTO` (hoje cobertos pelo código e pelo gatilho do banco); refazer o parcelamento de uma compra (hoje só desfazendo a compra); multa, juros e cartão (fora do escopo).
- **Um cenário Playwright da dona** abrindo o Corona Gestão e fazendo o acerto (começar, contar, fechar) sobre uma massa criada pela API: viável, não foi pedido nem feito.
- **Testes instáveis quando rodados sozinhos (7.3):** `AutorizacaoDeDevolucaoTest.nenhumaRespostaDasRotasDaAutorizacaoTemCustoNemFornecedor` e `TrocaTest.prazoContaEmDiasDeBrasiliaComoNaDevolucao` falham isolados e passam na suíte inteira (dependem do que as outras classes deixam em `configuracoes`); sugestão de tarefa de arrumação.

## Dúvidas em aberto

**Para a dona** (cada uma segue o padrão seguro do [plano](fase-7-plano.md#dúvidas-para-a-dona) até a resposta): 1 (fiado: perde o vendedor), 3 (comissão tirada no acerto), 5 (falta cobrada pelo preço da maleta), 6 (tudo o que sobra volta para a loja), 8 (o vendedor pode ficar devendo, em parcelas), 9 (dívida não entra no limite, com aviso), 10 (sem multa nem juros), 12 (boleto fora do sistema), 13 (caixa único, pode ficar negativo), 15 (compra em parcelas), 16 (acerto a favor desconta no próximo, ou paga na hora), 17 (parcelas mensais, sem mínimo), 18 (sem desconto livre no acerto), 19 (defeito aprovado vira avaria, não é cobrado) e a parte da 20 sobre quem fecha a semana.

**Do responsável:** aplicar o [roteiro do teste de uso](../roteiro-teste-fase-7.md) com a dona e um vendedor e registrar o que travar em `PROBLEMAS-DE-USO.md`; publicar a versão nova respeitando as notas de deploy de `entrega-corona-vendas.md` (o aplicativo antes da API, por causa das migrações locais 4 a 8); validar com a dona o desenho das telas novas.

## Diferenças entre a especificação e o que foi feito (sem mudar regra)

- **§7.4, passos 1 e 2** dizem que o vendedor sincroniza e **bipa as peças que sobraram**. Hoje quem conta é a **dona**, no Corona Gestão (decisão da 7.4: "só a dona desiste; o vendedor pede, bipa e lê", pela rota). A rota do vendedor para bipar existe e é testada; **a tela dele e o botão de pedir o acerto não existem** (a 7.11 não os incluiu). Efeito para o uso: a dona começa o acerto e conta sozinha. Não mudei a regra nem o texto da §7.4; fica registrado aqui e na §22 (v1.23) para o responsável decidir (fazer a tela do vendedor na Fase 8 ou ajustar o texto).
- **§9 e §15 (cadastro do vendedor):** `vendedores.comissao_percentual` continua no banco sem uso, e o campo "Comissão (%)" saiu da tela do vendedor (7.10). A linha da Fase 1 na §15 ainda cita "comissão" no cadastro: é texto histórico da fase, não foi reescrito.
