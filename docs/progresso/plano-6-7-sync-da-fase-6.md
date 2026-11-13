# Plano da tarefa 6.7: push e pull das tabelas da Fase 6

Tarefa crítica de sincronização (modo de plano). **Nenhum código antes do `[ORQUESTRADORA] APROVADO`.** Base: [fase-6-plano.md](fase-6-plano.md) (Regras da Fase 6, seção 6.7, Dúvidas R1 a R6 respondidas "Sim"), [ADR-0014](../decisoes/ADR-0014-devolucao-troca-e-garantia.md) (Aceito; itens 5, 9, 12 e 14), ADR-0013 (itens 2, 5, 11, 13, 14, 19, 20 e as linhas datadas das tarefas 5.4, 5.5, 5.6, 5.13 e da issue #127), o que a 6.3, a 6.4 e a 6.5 deixaram em [fase-6.md](fase-6.md) e as seções 5.4, 5.5 e 5.6 de [fase-5.md](fase-5.md). Da especificação, só a §10 (testes obrigatórios de sync: "devolução fora do prazo offline").

## O que já existe (não refazer)

- **Push (5.5, `api/sync`):** `ServicoDeEnvio` (um envio por computador, uma transação por operação com a linha do `sync_log`, resultados `ACEITO | JA_RECEBIDO | CONFLITO | RECUSADO | NAO_PROCESSADO`, parada no erro passageiro e nos 20 s, `apagarDados` do computador trocado), `OperacoesDoVendedor` (leitura, forma canônica, `aplicar` pelos serviços, `dependencias`, hora corrigida e limitada, `aparelhoDaMaleta` com a corrente de trocas), `FormatosDeOperacao` (formato 1 de `VENDA`, `ESTORNO_VENDA`, `CLIENTE_NOVO`, `CLIENTE_ALTERADO`, `CONFERENCIA`; 90 dias para o antigo), `EnviosComProblema` (Tentar de novo, Descartar) e `ServicoDeQuarentena`, que já leem e aplicam pelo mesmo `OperacoesDoVendedor`.
- **Pull (5.4):** `RegistroDeTabelas` com a `RegraDoVendedor` de cada tabela, `FiltroPorVendedor`, `ServicoDePull` (expansão na mesma fotografia, limite seguro, conferência por resumo), gatilhos de pertença (V032, com os ramos que a V036, a V037 e a V038 acrescentaram). Oráculo de dois vendedores (`CenarioDeSync.oraculo`, `OraculoDeDoisVendedoresTest`), `PullDoVendedorTest` (22 tabelas), `RegrasDoVendedorTest`, `EstresseDoPullComFiltroTest`.
- **Serviços da Fase 6, já prontos para o envio:** `ServicoDeDevolucoes.devolver(..., momento, horaAjustada, ModoDaDevolucao.ENVIO)` (fora do prazo, acima do limite da semana, do valor de aprovação, autorização inválida e reembolso maior que o pago viram `EM_REVISAO`; nunca recusa) e `cancelar(..., momento)`; `ServicoDeAutorizacoes.pedir(..., ENVIO)` (o desnecessário é gravado como fora do prazo, P13); `ServicoDeTrocas.trocar(..., ENVIO)` e `cancelar(..., momento)`; `ServicoDeVendas.vender` com `PagamentoDoPedido.creditoId`; `ServicoDeCreditos.conferirUso` (hoje 409 acima do saldo).
- **Hoje no `RegistroDeTabelas`:** `devolucoes`, `itens_devolucao`, `autorizacoes_excecao`, `itens_autorizacao`, `trocas`, `creditos_cliente` e `usos_credito` com a regra `FALSE`; fora das listas de colunas: `pagamentos_cliente.devolucao_id`, `estorna_id`, `credito_id`, `movimentos_remessa.devolucao_id`, `vendas.troca_id` e `devolucoes.valor_em_credito`. A `VENDA` formato 1 descarta o `creditoId` (`OperacoesDoVendedor.aplicar`).
- **Motor do app (5.6, `apps/seller-app/src/main/sync/`):** `aplicador-do-pull.ts` (lote numa transação com o cursor, `version` maior troca, coluna ou tabela fora da lista recusa o lote inteiro), `colunas.ts` (cópia da lista do servidor), `ciclo.ts` (push → pull → conferência por resumo; `recargaPorEpoca`; `recarregarTabela` com `?tabela=`, que apaga as cópias do servidor antes), `provisorias` (desfazer e limpar por tabela), migrações locais 1 e 2 e os bancos de exemplo `banco-v1` e `banco-v2` (5.13).
- **`testes-sync/`:** oráculo próprio em SQL (`src/oraculo.ts`, 15 tabelas) e `idsDeOutrosVendedores`.

## As decisões que mudam o resto

1. **O envio só traduz; a regra é do serviço.** Cada tipo novo vira uma chamada ao mesmo método da rota online, com `ModoDaDevolucao.ENVIO`, a hora corrigida e `horaAjustada`. Nenhuma regra de prazo, valor, maleta ou crédito mora no `sync`.
2. **Nenhuma expansão nova.** Em todas as tabelas novas a pertença vem de uma coluna que nunca muda e que já existe quando a linha nasce (`vendedor_id` copiado da venda; os itens e usos pela linha mãe gravada na mesma transação; a transferência de crédito cria um crédito **novo** do outro vendedor, o original fica com o antigo). Nenhuma linha "passa a ser" de V sem ser alterada, então a regra 1 do item 5 não pede expansão; o oráculo prova (dúvida S12).
3. **Nada que aponte para o outro vendedor.** `creditos_cliente.credito_origem_id` (no crédito transferido, aponta para o crédito do vendedor antigo) e `usos_credito.credito_destino_id` (no uso de transferência, aponta para o crédito do vendedor novo) **saem** das listas de colunas (dúvida S4). O oráculo passa a varrer toda coluna com UUID da cópia de A contra os ids privados de B.
4. **Crédito acima do saldo pelo envio vira revisão, não recusa** (ADR-0014, item 9; dúvida S1): a venda entra, o pagamento em crédito fica limitado ao saldo que havia (pode ser zero), o resto fica **a receber do cliente** na venda, e nasce uma linha `creditos_sem_saldo` `EM_REVISAO` com aviso à dona. Crédito inexistente, de outro vendedor ou de outro cliente continua recusado (é o impossível do item 5).
5. **A `VENDA` continua no formato 1, ampliado** com `creditoId` opcional no pagamento (dúvida S3): o resumo canônico só ganha o crédito quando ele vem, então toda venda já enviada continua dando o mesmo resumo (e o reenvio dela, `JA_RECEBIDO`).
6. **App instalado sem a migração local 3 não pode receber as tabelas novas** (recusaria o lote inteiro, e o pull pararia): a versão mínima do Corona Vendas sobe no deploy que levar a 6.7; o app velho continua enviando (5.13) e recebe 426 no pull até atualizar (dúvida S5).
7. **Depois da migração local 3, recarga dirigida sem apagar** (dúvida S6): as tabelas novas e as três com coluna nova (`vendas`, `pagamentos_cliente`, `movimentos_remessa`) vêm de novo pelo `?tabela=`, substituindo também a versão igual, sem apagar nada antes (a maleta nunca mostra saldo errado no meio, nem sem internet).

## Migração `V039__sync_da_fase_6.sql` (o próximo número livre na `main` na hora do código)

Só existe por causa da decisão 4 (se a dúvida S1 for "recusar", a tarefa não tem migração).

1. **`creditos_sem_saldo`** (sincronizável, com as peças do grupo como a V036; só a situação e a revisão mudam): `id` = o id do pagamento em crédito que o computador mandou; `venda_id` e `credito_id` (chaves); `valor_pedido > 0`; `valor_usado` (`0 ≤ valor_usado < valor_pedido`); `situacao` (`EM_REVISAO | MANTIDA | NAO_ACEITA`); `registrada_em`; `revisada_em`, `revisada_por`, `observacao_revisao` (obrigatória no "não aceita"), com `CHECK` de "tudo ou nada" da revisão.
   - Gatilho de inserção: a venda e o crédito são do mesmo vendedor e do mesmo cliente; com `valor_usado > 0`, existe o pagamento `CREDITO` com o mesmo `id`, deste crédito e com esse valor; com zero, não existe pagamento com esse `id`.
   - Gatilho de alteração: conteúdo imutável; só `EM_REVISAO → MANTIDA | NAO_ACEITA`. Ramo novo na função de pertença (`venda_id` e `credito_id` não mudam). `REVOKE ALL ... FROM PUBLIC`.
   - Índices: `venda_id`; `registrada_em` onde `situacao = 'EM_REVISAO'`.
2. Índices do filtro que faltarem (conferir na V036 a V038; hoje falta só confirmar `itens_autorizacao (autorizacao_id)`). Nenhuma migração publicada é editada; nenhuma função antiga é recriada.

## Backend

**Pull (`RegistroDeTabelas`).** Trocar o `FALSE` pela regra do vendedor:

| Tabela | Regra | Colunas (mudanças) |
| --- | --- | --- |
| `devolucoes` | `t.vendedor_id = ?` (inclui a devolução que a dona registrar de venda dele) | + `valor_em_credito`, `cancelamento_id` |
| `itens_devolucao` | pela devolução (`EXISTS ... d.vendedor_id = ?`) | igual |
| `autorizacoes_excecao` | `t.vendedor_id = ?` | igual (sem `decidida_por`) |
| `itens_autorizacao` | pelo pedido | igual |
| `trocas` | `t.vendedor_id = ?` | + `cancelamento_id` |
| `creditos_cliente` | `t.vendedor_id = ?` (só quem pode usar) | − `credito_origem_id` |
| `usos_credito` | pelo crédito (`EXISTS ... c.vendedor_id = ?`) | − `credito_destino_id` |
| `creditos_sem_saldo` (nova) | pela venda (`DA_VENDA`) | `venda_id`, `credito_id`, `valor_pedido`, `valor_usado`, `situacao`, `registrada_em`, `revisada_em`, `observacao_revisao` |
| `vendas` | igual | + `troca_id` |
| `pagamentos_cliente` | igual | + `devolucao_id`, `estorna_id`, `credito_id` |
| `movimentos_remessa` | igual | + `devolucao_id` |

Nunca vão: `registrada_por`, `revisada_por`, `decidida_por`, `cancelada_por`, `registrado_por`, `cancelado_por` (como hoje). Os comentários do `FALSE` saem. `ServicoDePull`, `FiltroPorVendedor` e `CursorDeSync` **não mudam**.

**Push (`OperacoesDoVendedor`, `FormatosDeOperacao`, `ServicoDeEnvio`).** Formato 1 de cada tipo novo (o `operacao_id` é o id do registro criado, como hoje):

| Tipo | Dados | Aplicação | Hora declarada e limite inferior | Depende de |
| --- | --- | --- | --- | --- |
| `DEVOLUCAO` | o pedido da rota (`id`, `vendaId`, `motivo`, `observacao`, `formaReembolso`, `itens` [produto, quantidade, estado], `valorConferido`, `autorizacaoId`) + `devolvidaEm` | `ServicoDeDevolucoes.devolver(..., ENVIO)` | `devolvidaEm`; maior entre o último contato e a hora da venda | venda, autorização |
| `PEDIDO_AUTORIZACAO` | `id`, `vendaId`, `motivo`, `observacao`, `itens` + `pedidaEm` | `ServicoDeAutorizacoes.pedir(..., ENVIO)` | `pedidaEm`; idem | venda |
| `TROCA` | o pedido da rota (`id`, `creditoId`, `pagamentoDoCreditoId`, `devolucao`, `venda` com `pagamentos` e `creditoId` em cada um) + `trocadaEm` | `ServicoDeTrocas.trocar(..., ENVIO)` | `trocadaEm`; maior entre o último contato, a venda A e o envio da maleta de B | venda A, maleta B, autorização, créditos do resto |
| `CANCELAR_DEVOLUCAO` | `id` (do cancelamento), `devolucaoId`, `motivo`, `canceladaEm` | `ServicoDeDevolucoes.cancelar(..., momento)` | `canceladaEm`; maior entre o último contato e a devolução | devolução |
| `CANCELAR_TROCA` | `id`, `trocaId`, `motivo`, `canceladaEm` | `ServicoDeTrocas.cancelar(..., momento)` | `canceladaEm`; maior entre o último contato e a troca | troca |
| `VENDA` (formato 1 ampliado) | + `creditoId` opcional em cada pagamento | `ServicoDeVendas.vender(..., modo envio)` | como hoje | + os créditos usados |

- **Forma canônica** de cada tipo novo como as de hoje (campos em ordem fixa, listas ordenadas por id do produto ou do pagamento, dinheiro em centavos exatos, nulo como `∅`). Na `VENDA`, o pagamento só ganha `:creditoId` quando o crédito vem (decisão 5).
- **Hora:** `corrigir(...)` de hoje com os limites da tabela; `horaAjustada` acima de 5 minutos para devolução, pedido e troca (as três têm `hora_ajustada`); o cancelamento só recebe o momento. O prazo e o "mesmo dia" do cancelamento saem da hora corrigida (ADR-0013, item 11).
- **Computador da regra:** devolução, pedido e cancelamento de devolução usam `aparelhoDaMaleta(maleta da venda)`; troca e cancelamento de troca, a maleta de B (a de A é conferida pelo serviço com o mesmo computador; dúvida S11).
- **Dependência pelos dados:** `dependencias()` devolve os ids da tabela acima. O crédito de uma troca é criado com o id do app, mas a operação que o cria tem o id da troca; `dependeDeRecusado` procura também a `TROCA` do mesmo vendedor cujo `dados->>'creditoId'` é aquele crédito (dúvida S9).
- **Conflito:** `DE_CONFLITO` ganha `DEVOLUCAO_JA_REGISTRADA_DIFERENTE`, `AUTORIZACAO_JA_REGISTRADA_DIFERENTE` e `TROCA_JA_REGISTRADA_DIFERENTE`; o resto dos 409 é `RECUSADO` (maleta em acerto, cancelar fora do dia, crédito da troca já usado, peça já vendida de novo; dúvidas S7 e S8).
- `FormatosDeOperacao`: os cinco tipos com o formato 1, publicado na data do merge (constante nova); a `VENDA` não ganha formato.
- Se `OperacoesDoVendedor` passar de cerca de 600 linhas, a leitura e a forma canônica dos tipos da Fase 6 vão para uma classe do mesmo pacote, chamada por ele (sem regra).

**Crédito sem saldo pelo envio (decisão 4; `api/vendas` e `api/creditos`).**

- `ServicoDeVendas.vender` ganha o modo (rota ou envio), como a devolução; a troca repassa o dela à venda B. Na rota, nada muda (409 `CREDITO_ACIMA_DO_SALDO` e `CREDITO_SEM_SALDO`).
- No envio, para cada pagamento `CREDITO` (crédito travado na ordem de hoje): inexistente ou de outro vendedor = 404, outro cliente = 409 (recusas); `CANCELADO`, `UTILIZADO` ou acima do saldo = o pagamento grava `min(pedido, saldo)` (nenhuma linha quando o saldo é zero) e nasce `creditos_sem_saldo` com o pedido e o usado. O resto fica a receber pela conta de hoje (total − devolvido − pagos), sem nenhum valor do app; auditoria `CREDITO_SEM_SALDO_NA_VENDA`.
- `ServicoDeCreditos`: `usarAteOSaldo(...)` ao lado do `conferirUso`; `manter(id, usuario, observacao)` e `naoAceitar(id, usuario, observacao)` (trava venda → linha; nada muda além da revisão). Rotas da dona (ADMIN): `GET /api/dona/creditos-sem-saldo` (padrão: para revisar), `POST .../{id}/manter`, `POST .../{id}/nao-aceitar` (dúvida S2). Sem tela.
- `FonteDeAvisosDosCreditos`: aviso `CREDITOS_SEM_SALDO_PARA_REVISAR` em "O que precisa da sua atenção", calculado na hora.
- Códigos de erro novos só para a revisão (`CREDITO_SEM_SALDO_NAO_ESTA_PARA_REVISAO`, `REVISAO_PEDE_OBSERVACAO` se não existir um igual), com o texto do glossário.

Clientes da API regerados (`npm run api:clientes`). Nenhum pacote novo (o filtro `sync` do `ci.yml` já cobre `sync`, `vendas`, `devolucoes`, `trocas` e `creditos`).

## Corona Vendas (só o motor e o banco local; telas e gravação das operações novas são da 6.8)

- **Migração local 3** (`banco/migracoes.ts`, nunca editar a 1 e a 2): as oito tabelas novas com as colunas da lista (dinheiro em texto, datas em texto ISO, `CONTROLE` de sempre) e os índices de leitura (por venda, por devolução, por crédito, por cliente); `ALTER TABLE ... ADD COLUMN` em `vendas` (`troca_id`), `pagamentos_cliente` (`devolucao_id`, `estorna_id`, `credito_id`) e `movimentos_remessa` (`devolucao_id`); e a marca `recargaDirigida` em `estado_sync` com as onze tabelas, **só se o banco já tinha cursor** (banco novo faz a carga inicial e não precisa).
- `TABELAS_SINCRONIZAVEIS` e `COLUNAS_DO_PULL` iguais às do `RegistroDeTabelas` (23 tabelas). Banco de exemplo `exemplos-do-banco/banco-v3.sqlite` (5.13: exemplo publicado nunca se gera de novo).
- `aplicador-do-pull.ts`: opção `substituirIgual` (aplica com `version` maior **ou igual**, só em cópia do servidor), usada só pela recarga dirigida; o pull normal continua "igual é ignorada".
- `ciclo.ts`: depois do pull, se houver a marca, recarrega cada tabela dela pelo `?tabela=` com `substituirIgual` e **sem apagar antes**; a tabela sai da marca na mesma transação da última página dela; queda no meio recomeça aquela tabela no próximo ciclo (inofensivo pela regra da versão). A conferência por resumo roda depois, com as tabelas novas.
- `provisorias`: o desfazer e a limpeza já são por tabela; só se confere (teste) que valem para as tabelas novas.
- Nada muda em `outbox.ts`, `motor.ts`, `consultas-locais.ts` e telas.

**`testes-sync/`:** `src/oraculo.ts` com as tabelas novas (regras em SQL escritas à parte) e as linhas privadas novas em `idsDeOutrosVendedores`. Nenhum cenário novo (a 6.11 traz devolução e troca nas duas pontas).

## Documentos

- **Antes do código:** glossário (parte 6): "A dona vai olhar esta devolução.", "O crédito do cliente já tinha acabado. A dona vai olhar esta venda." (vendedor, para a 6.8), o aviso da dona ("N vendas pagas com crédito que já tinha acabado") e os botões **Manter** e **Não aceitar** já existentes; especificação v1.12 (§7.3: crédito acima do saldo pelo envio; §9: `creditos_sem_saldo`; §10: os tipos novos da outbox); ADR-0013 (linha datada da 6.7: tipos e formatos, `VENDA` ampliada, versão mínima, recarga dirigida, dependência do crédito) e ADR-0014 (linha datada no item 9: o que "vira revisão" quer dizer na venda; e no item 14: as duas colunas fora do pull). Só o aprovado.
- **No fim:** `docs/progresso/fase-6.md` (relatório e o que a 6.8, a 6.9, a 6.11 e a 7.5 herdam), a linha da 6.7 em `docs/PROGRESSO.md`, e a nota de deploy sobre a versão mínima no roteiro de atualização.

## Testes primeiro (PostgreSQL de verdade, Testcontainers; SQLCipher de verdade no app; escritos e vistos falhando antes do código)

Arquivos novos: `backend/.../sync/PushDaFase6Test.java`, `.../sync/PullDaFase6Test.java`, `.../creditos/CreditoSemSaldoTest.java`; ajustes em `CenarioDeSync`, `OraculoDeDoisVendedoresTest`, `PullDoVendedorTest` (22 → 23 tabelas), `RegrasDoVendedorTest`, `FormatosDeOperacaoTest`, `EstresseDoPullComFiltroTest`; no app, `migracoes`/`banco-local`, `exemplos-por-versao`, `colunas`, `aplicador-do-pull`, `ciclo` e `operacoes-locais` (desfazer); `testes-sync/src/oraculo.ts`.

**Push**

1. **Cada tipo pelo mesmo serviço:** `DEVOLUCAO`, `PEDIDO_AUTORIZACAO`, `TROCA` (B mais cara com dinheiro; B mais barata com sobra), `CANCELAR_DEVOLUCAO` e `CANCELAR_TROCA` dão `ACEITO` e gravam exatamente o que a rota grava para o mesmo pedido (devolução, itens, movimentos, reembolso, crédito, usos, troca, venda B), com a hora corrigida.
2. **Reenvio sem duplicar:** o mesmo lote duas vezes dá `JA_RECEBIDO` em tudo e nenhuma contagem muda (devoluções, itens, movimentos, pagamentos, créditos, usos, trocas, vendas); JSON equivalente (campos em outra ordem, "10.0") dá `JA_RECEBIDO`; mesmo id com outra peça dá `CONFLITO` e nada muda; resposta perdida depois de gravar e reenvio dão `JA_RECEBIDO`.
3. **Dependência — devolução de venda ainda não enviada:** lote [`VENDA`, `DEVOLUCAO` dela] dá dois `ACEITO`; [`VENDA` recusada por saldo, `DEVOLUCAO`] dá `DEPENDE_DE_ENVIO_RECUSADO`; `VENDA` com erro passageiro para o lote antes da devolução; `CANCELAR_DEVOLUCAO`/`CANCELAR_TROCA` depois da devolução/troca recusada e `DEVOLUCAO` com a autorização de um pedido recusado dão `DEPENDE_DE_ENVIO_RECUSADO`.
4. **Dependência — crédito de troca ainda não enviada:** [`TROCA` com sobra (crédito C), `VENDA` paga com C] dá dois `ACEITO`; [`TROCA` recusada, `VENDA` com C] dá `DEPENDE_DE_ENVIO_RECUSADO`; [`TROCA`, outra `TROCA` com o resto pago com C] dá dois `ACEITO`.
5. **Fora do prazo pelo servidor vira revisão, nunca recusa:** relógio 3 dias atrasado, devolução que o computador achava no 6º dia é corrigida para o 9º: `ACEITO`, `EM_REVISAO`, `revisao_fora_do_prazo` e `hora_ajustada`; o mesmo na `TROCA` (devolução A em revisão, troca gravada); relógio 2 dias adiantado corrigido para dentro do prazo: `ACEITA` (nenhuma revisão falsa); virada às 23:30 de Brasília contra UTC; pedido desnecessário pelo envio gravado como fora do prazo; acima do limite da semana e do valor de aprovação pelo envio: `EM_REVISAO`; autorização vencida no dia corrigido: revisão sem consumir. Nenhum `RECUSADO` neste teste.
6. **Maleta em acerto:** `DEVOLUCAO` e `TROCA` de venda de maleta `EM_ACERTO`, e `TROCA` com B de maleta `EM_ACERTO`, dão `RECUSADO` `MALETA_EM_ACERTO`, nada gravado, JSON no `sync_log` e em "envios com problema"; com a maleta já `ACERTADA`, "Tentar de novo" dá `ACEITO` com a peça para a loja (sem movimento).
7. **Crédito usado duas vezes no mesmo lote:** crédito de 40,00; [`VENDA` 1 com 40,00 em crédito, `VENDA` 2 com 40,00 no mesmo crédito] dá dois `ACEITO`; a venda 2 fica sem pagamento em crédito, com 40,00 a receber e `creditos_sem_saldo` `EM_REVISAO` (pedido 40,00, usado 0,00) e o aviso; Σ usos = valor e o crédito `UTILIZADO`. Com saldo de 25,00: pagamento de 25,00, 15,00 a receber. Crédito transferido pela dona e crédito cancelado antes do envio: o mesmo. Reenvio `JA_RECEBIDO` sem segunda revisão. Crédito inexistente, de outro vendedor ou de outro cliente: `RECUSADO`. Na rota online, continua 409.
8. **`VENDA` ampliada sem quebrar o passado:** venda com crédito pelo envio dá `ACEITO` com o uso; o resumo canônico de uma venda **sem** crédito é igual a um valor fixo calculado com o código de hoje (o reenvio de vendas antigas continua `JA_RECEBIDO`).
9. **Hora e "mesmo dia":** devolução declarada antes da venda fica na hora da venda; troca limitada pela venda A e pelo envio da maleta B; cancelamento limitado pela devolução/troca; hora no futuro vira a chegada; cancelar no dia pela hora corrigida passa, e no dia seguinte (pela correção) dá `RECUSADO` com o código "só a dona".
10. **Permissões e barreira pelo envio:** devolução, pedido, troca e cancelamentos sobre venda, devolução ou troca de outro vendedor dão `RECUSADO` (404 do serviço, nada revelado); nenhuma resposta do envio tem marcador proibido ou custo.
11. **Computador trocado, quarentena e "Tentar de novo":** o computador trocado envia uma `DEVOLUCAO` de maleta que passou para o novo: `ACEITO` pela corrente; computador bloqueado guarda `DEVOLUCAO` e `TROCA` sem aplicar e a dona aprova pelo mesmo serviço; `DEVOLUCAO` formato 2 dá `PEDIDO_INVALIDO`.
12. **Troca que não fecha no fim da transação pelo envio** (gatilho de teste da 6.5): `RECUSADO` sem 500, nada gravado, linha do `sync_log`.
13. `FormatosDeOperacaoTest`: os cinco tipos conhecidos e aceitos agora; a `VENDA` sem formato novo.

**Pull e oráculo**

14. **Cada tabela nova:** chega ao dono e nunca ao outro; itens pela devolução e pelo pedido; usos pelo crédito; colunas exatamente as da lista (sem `*_por`, sem `credito_origem_id` nem `credito_destino_id`); as colunas novas de `vendas`, `pagamentos_cliente` e `movimentos_remessa` chegam; devolução registrada pela dona de venda do vendedor chega a ele; `EM_REVISAO` e as decisões da dona (aprovada com `valida_ate`, negada, mantida, não aceita, vencida) chegam como versão nova; conferência por resumo conta as tabelas novas; `PullDoVendedorTest` com 23 tabelas.
15. **Crédito transferido:** o crédito novo vai só ao vendedor novo; o original e os usos dele ficam só com o antigo; nenhum dos dois recebe id do outro.
16. **Convenções:** nenhuma sincronizável com `FALSE`; `RegrasDoVendedorTest` e o verificador de convenções passam; mudar `vendedor_id`, `devolucao_id`, `autorizacao_id`, `credito_id` ou `venda_id` das tabelas novas é recusado pelo banco.
17. **Oráculo de dois vendedores com a Fase 6:** `CenarioDeSync` ganha devolver (boa e com defeito, maleta aberta e acertada), pedir, aprovar e negar autorização, trocar (mais cara e mais barata), usar crédito, transferir crédito para o outro, cancelar devolução e troca, manter e não aceitar, e a venda com crédito sem saldo. No fim, a cópia de cada um igual ao oráculo (id, versão, lápide) nas 23 tabelas e **nenhum id de outro vendedor**: toda coluna com UUID de toda linha da cópia de A fora dos ids das linhas privadas de B (e o contrário). Prova de que pega o furo: com `credito_origem_id` de volta na lista, a varredura falha.
18. `EstresseDoPullComFiltroTest`: um escritor a mais faz devoluções e trocas (continua 20 s no PR).

**Crédito sem saldo (revisão)**

19. `CreditoSemSaldoTest`: o banco recusa conteúdo alterado, `DELETE`, transição proibida, linha com venda ou crédito de outro vendedor ou cliente, `valor_usado ≥ valor_pedido` e pagamento que não confere; Manter e Não aceitar (só ADMIN; 403 e 401; observação obrigatória no "não aceita"; repetição 200; 409 fora da revisão); o aviso aparece no singular e no plural e some ao decidir; conciliação por venda (total − devolvido = pagos + a receber).

**Corona Vendas**

20. **Migração local 3:** banco v2 com outbox e provisórias migra sem perder nada; tabelas e colunas novas; `banco-v3.sqlite` e o teste de exemplos por versão (v1 → v3, v2 → v3); a marca da recarga só nasce em banco que já tinha cursor.
21. `colunas.test.ts`: lista igual ao esquema e ao servidor nas 23 tabelas; nenhuma coluna de custo, fornecedor ou estoque; sem as duas colunas de crédito do outro.
22. `aplicador-do-pull.test.ts`: lote com as tabelas novas; `credito_origem_id` ou `revisada_por` recusa o lote; `substituirIgual` troca a versão igual só na recarga dirigida; provisória intocada.
23. `ciclo.test.ts` (servidor falso com as tabelas novas): banco migrado faz a recarga dirigida das onze tabelas **sem apagar nada antes** (vendas e movimentos antigos continuam lá durante; falha injetada no meio da 2ª tabela deixa tudo como estava e o ciclo seguinte retoma); a marca sai tabela por tabela; banco novo não faz; a conferência das tabelas novas bate sem aviso de defeito.
24. `operacoes-locais.test.ts`: `RECUSADO` e `CONFLITO` de uma operação com provisórias em `devolucoes`, `itens_devolucao`, `pagamentos_cliente` e `movimentos_remessa` desfazem tudo (linhas de teste; os gravadores são da 6.8).
25. `testes-sync/`: oráculo e ids de outros vendedores com as tabelas novas; duas pontas e estresse curto verdes com o banco local v3.

## Ordem dos commits (cada passo com push e Situação atualizada)

1. `docs`: glossário, especificação v1.12, linhas datadas do ADR-0013 e do ADR-0014 (só o aprovado).
2. `test(sync)`, `test(creditos)`: os testes 1 a 19, vermelhos.
3. `feat(db)`: V039.
4. `feat(sync)`: pull (regras, colunas, oráculo verde).
5. `feat(sync)`: push (tipos novos, forma canônica, hora, dependência, conflito, formatos).
6. `feat(creditos)`: crédito sem saldo pelo envio, revisão e aviso.
7. `test(seller-app)` vermelho e depois `feat(seller-app)`: migração local 3, colunas, aplicador, recarga dirigida, `banco-v3.sqlite`.
8. `test(testes-sync)`: oráculo com as tabelas novas.
9. `chore(api)`: clientes regerados.
10. `docs`: `fase-6.md`, `PROGRESSO.md`, nota de deploy. Localmente: `npm run api:test`, testes do Corona Vendas, `test:duas-pontas`, `lint`, `typecheck`, `format:check` e `barreira:varrer`.

## Riscos

- **App instalado com o banco v2:** recebe tabela desconhecida, recusa o lote e o pull para para sempre. Proteção: versão mínima no deploy (S5); o envio continua (5.13), então nada se perde.
- **Resumo canônico da `VENDA`:** se mudar para as vendas sem crédito, o reenvio de uma venda antiga vira `CONFLITO`. O teste 8 fixa o valor.
- **Recarga dirigida:** apagar antes de puxar deixaria a maleta com saldo maior (vendas sumidas) e, sem internet, a maleta vazia; por isso não apaga e a versão igual só é substituída nela.
- **Troca pelo envio:** as conferências adiadas já são feitas pelo serviço antes do fim; se algo escapar para o `COMMIT`, o `DataIntegrityViolationException` vira `RECUSADO` (teste 12), nunca 500 nem `NAO_PROCESSADO` em laço.
- **Crédito sem saldo vira venda com valor a receber:** até a 6.6, a devolução dela dá 409 "venda a prazo"; a dona vê pelo aviso.
- **Crédito de devolução** tem id do servidor: o computador só o vê depois do envio (sem internet, o vendedor só usa crédito de troca, cujo id é dele).
- **Trava do vendedor** (`pg_advisory_xact_lock`) no envio: a devolução do envio espera a rota online do mesmo vendedor; o limite de 20 s do lote cobre.
- **Issue #130 (pull que às vezes não entrega um cliente):** esta tarefa **não** mexe em `ServicoDePull`, `CursorDeSync`, limite seguro nem na regra dos clientes, mas usa o mesmo caminho do pull para as tabelas novas: se a causa for o limite seguro (hipótese a), as tabelas novas herdam o defeito e a correção da #130 vale para elas. Arquivos em comum prováveis: `CenarioDeSync`, `OraculoDeDoisVendedoresTest`, `EstresseDoPullComFiltroTest`, `testes-sync/src/oraculo.ts`, `ciclo.ts` e `aplicador-do-pull.ts`; quem mesclar depois faz o rebase. Uma falha "cliente falta no computador" numa rodada desta tarefa vai, com a semente, para a #130, sem correção aqui. O oráculo maior aumenta a chance de cruzar com ela no CI.
- **Tamanho do `OperacoesDoVendedor`:** dividir sem mover regra (decisão no Backend).
- Número da migração: se outra tarefa ocupar a V039 antes, uso o próximo livre.

## O que não fazer

- Telas, gravação local das operações novas, números locais com devolução e troca, nome "Crédito" na tela (6.8); cenários novos de duas pontas (6.11); devolução de venda a prazo (6.6); regra de corte do acerto e cobrança da pendência do vendedor (7.5); tela da dona para o crédito sem saldo (6.9 ou depois); correção da #130.
- Nenhuma regra só no caminho do sync; nenhum valor de devolução, troca ou crédito vindo do app vale; nunca mudar o resumo canônico dos tipos existentes; nenhuma migração publicada editada; nenhuma expansão sem necessidade; nada em `ServicoDePull` e `CursorDeSync`; nenhum id ou dado de outro vendedor em resposta do vendedor.

## Dúvidas (com o padrão que sigo se não houver resposta)

| Nº | Pergunta | Padrão |
| --- | --- | --- |
| S1 | Crédito acima do saldo pelo envio: a venda entra, o crédito paga só o saldo que havia, o resto fica a receber do cliente e a dona revisa (`creditos_sem_saldo`)? A outra saída seria `RECUSADO` para "envios com problema", mas a peça já saiu da maleta e "Tentar de novo" nunca daria certo. | **Sim** (aceita e revisa) |
| S2 | As rotas da dona para essa revisão (Manter = o cliente continua devendo; Não aceitar = pendência do vendedor, cobrada na 7.5) entram nesta tarefa, sem tela? | **Sim** (pequenas, iguais às da 6.4) |
| S3 | `VENDA` com crédito: formato 1 ampliado com `creditoId` opcional (resumo só muda quando há crédito), em vez de formato 2 (que começaria a contar os 90 dias do formato 1)? | **Formato 1 ampliado** |
| S4 | `creditos_cliente.credito_origem_id` e `usos_credito.credito_destino_id` (apontam para crédito de outro vendedor) ficam fora do pull? | **Sim** |
| S5 | Subir a versão mínima do Corona Vendas no deploy que levar a 6.7 (o app velho envia e recebe 426 no pull até atualizar)? | **Sim** (nada em produção hoje; fica a nota de deploy) |
| S6 | Depois da migração local 3, recarga dirigida (sem apagar) das tabelas novas e de `vendas`, `pagamentos_cliente` e `movimentos_remessa`, só em banco que já tinha cursor? | **Sim** |
| S7 | Devolução ou troca de maleta `EM_ACERTO` pelo envio: `RECUSADO` `MALETA_EM_ACERTO` (a regra de corte do ADR-0012, item 4, é da 7.5; a dona usa "Tentar de novo" depois do acerto, e a peça vai para a loja)? | **Sim** |
| S8 | Cancelar devolução ou troca pelo envio que, pela hora corrigida, caiu no dia seguinte: `RECUSADO` (a dona cancela)? É correção de lançamento, não algo do balcão. | **Sim** |
| S9 | A dependência do crédito criado por troca ainda não enviada é achada pelo `creditoId` no JSON do `sync_log` (90 dias; depois disso, fica só a recusa original)? | **Sim** |
| S10 | `cancelamento_id` de `devolucoes` e `trocas` vai ao vendedor (o id do cancelamento dele, ou um id da dona)? | **Sim** (a 6.8 liga o cancelamento ao que enviou) |
| S11 | Na troca pelo envio, o computador conferido é o da maleta de B (pela corrente de trocas), e a maleta de A é conferida com o mesmo computador? | **Sim** (um computador ativo por vendedor) |
| S12 | Confirma que nenhuma expansão nova é necessária (decisão 2)? | **Sim** (o oráculo prova; se falhar, a expansão entra) |

## Custo de CI

Uma rodada no fim (o rascunho não gasta): `backend` (~6 min, três arquivos de teste novos e o oráculo maior), `sync-duas-pontas` (~9 min, pela migração, pelo `sync` e pelo app), `frontend` (~4 min, Corona Vendas, `testes-sync/` e clientes regerados), `migracoes`, `mudancas` e `ci` (~1 min cada); `dependencias` não roda (o `ci.yml` não muda). Cerca de **22 minutos de execução (por volta de 26 cobrados)**; o dobro se uma correção pedir outra rodada.
