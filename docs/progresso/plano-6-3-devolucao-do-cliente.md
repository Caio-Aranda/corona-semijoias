# Plano da tarefa 6.3: devolução do cliente (à vista)

Tarefa crítica (modo de plano). **Nenhum código antes do `[ORQUESTRADORA] APROVADO`.** Base: [fase-6-plano.md](fase-6-plano.md) (Regras da Fase 6, seção 6.3, Dúvidas R1 a R6 respondidas "Sim"), [ADR-0014](../decisoes/ADR-0014-devolucao-troca-e-garantia.md) (Aceito), especificação §7.3, §8 (leitor na devolução) e §9 (Devolução e troca), e o que a 6.2 deixou em [fase-6.md](fase-6.md) (V035, `prazo_arrependimento_dias` 7 e `prazo_defeito_dias` 30 em `configuracoes`).

## O que já existe (não refazer)

- Venda (V022, `ServicoDeVendas`): venda imutável, `itens_venda` com uma linha por peça (`UNIQUE (venda_id, produto_id)`), `pagamentos_cliente` `PAGAMENTO | REEMBOLSO` com sinal no `CHECK`, estorno da venda inteira que trava **a maleta e depois a venda**.
- Maleta (V021, V024, V025): saldo = `qtd_enviada` + movimentos; `DEVOLUCAO_CLIENTE` já está no `CHECK` de tipo e é positivo; estorno de movimento com `estorna_id` pode ter qualquer sinal; o gatilho da V024 recusa saldo negativo.
- Garantia (V035, `Configuracoes`): os dois prazos; os limites de reembolso ficam para a 6.4.
- Pull (5.4): `RegistroDeTabelas` com a regra do vendedor por tabela; o app atual **recusa o lote inteiro** se chegar uma tabela que ele não conhece (`aplicador-do-pull.ts`), e ignora tabela desconhecida na conferência por resumo.
- `Dinheiro.dividirEmParcelas`, `CodigosDosProdutos.produtoDoCodigo`, `VendedorAtivo.exigir`, `RelogioDeTeste`.

## Migração `V036__devolucao_do_cliente.sql` (o próximo número livre na `main` na hora do código)

1. **`devolucoes`** (sincronizável, não imutável porque a situação muda): `venda_id`, `cliente_id`, `vendedor_id`, `remessa_id` (os quatro iguais aos da venda, conferidos por gatilho), `motivo` (`ARREPENDIMENTO | DEFEITO | TAMANHO | OUTRO`), `observacao` (até 400), `destino` (`REEMBOLSO | TROCA`; nesta tarefa só `REEMBOLSO`), `dentro_garantia`, `situacao` (`ACEITA | EM_REVISAO | MANTIDA | NAO_ACEITA | CANCELADA`), `autorizacao_id` (sem chave estrangeira; a 6.4 cria a tabela e a chave), `valor_devolvido` e `valor_reembolsado` `NUMERIC(14,2)` (`0 <= reembolsado <= devolvido`), `forma_reembolso` (`DINHEIRO | PIX | CARTAO | CHEQUE`, obrigatória quando há reembolso), `cotacao_id` (do dia da devolução, só para mostrar Dólar e Guarani), `devolvida_em`, `hora_ajustada`, `registrada_por`, e o cancelamento (`cancelamento_id` único, `cancelada_em`, `cancelada_por`, `motivo_cancelamento`, com `CHECK` de "tudo ou nada" ligado a `situacao = 'CANCELADA'`).
   - Gatilho `devolucoes_conteudo_imutavel`: nada muda além da situação e dos dados do cancelamento; transições permitidas: `ACEITA → CANCELADA`, `EM_REVISAO → CANCELADA | MANTIDA | NAO_ACEITA`; `deleted_at` nunca.
   - Gatilho `corona_pertenca_nao_muda` (exigido pelo `RegrasDoVendedorTest`): `CREATE OR REPLACE` da função da V032 com o ramo `devolucoes` (`vendedor_id` e `venda_id` não mudam). A V032 não é editada.
   - Gatilho de inserção: a venda precisa estar `CONCLUIDA` (segunda trava do "venda estornada não recebe devolução").
2. **`itens_devolucao`** (sincronizável, **imutável**): `devolucao_id`, `item_venda_id`, `quantidade > 0`, `valor >= 0`, `estado_peca` (`BOA | DEFEITO`), `destino_peca` (`MALETA | LOJA`), `CHECK (estado_peca = 'BOA' OR destino_peca = 'LOJA')` (defeito nunca volta ao saldo), `UNIQUE (devolucao_id, item_venda_id, estado_peca)`.
   - **Segunda trava no banco** (como a V024 no saldo da maleta): gatilho `AFTER INSERT` que trava a linha da venda (`FOR UPDATE`, a mesma que o serviço já travou) e confere, por linha da venda, que o item é da venda da devolução, que a soma das quantidades das devoluções **não canceladas** não passa da quantidade vendida e que a soma dos valores não passa do total da linha (`preco_unitario × quantidade − desconto`).
3. **`pagamentos_cliente`**: colunas `devolucao_id` (FK) e `estorna_id` (FK para a própria tabela, índice único); tipo novo `ESTORNO_REEMBOLSO` (positivo, com `estorna_id`), usado só por "Cancelar devolução"; `devolucao_id` só em `REEMBOLSO | ESTORNO_REEMBOLSO`. Os `CHECK` de tipo e de sinal são recriados (a V022 não é editada).
4. **`movimentos_remessa`**: coluna `devolucao_id` (FK); `DEVOLUCAO_CLIENTE` exige `devolucao_id`. O movimento continua com o `venda_id` da venda original.
5. **`vendas`**: gatilho novo `vendas_estorno_sem_devolucao` (segunda trava da R6): `CONCLUIDA → ESTORNADA` recusada se houver devolução não cancelada.
6. `REVOKE ALL ... FROM PUBLIC` nas tabelas novas, como a V022.

## Backend (pacote novo `api/devolucoes`)

- **`ValorDaDevolucao`** (cálculo puro, sem banco): valor de uma devolução de `q` unidades de uma linha com `n` vendidas e total `T`, sabendo quantas unidades e quanto valor já estão devolvidos (só as não canceladas). `prefixo(k)` = soma das `k` primeiras unidades de `Dinheiro.dividirEmParcelas(T, n)` (ADR-0014, item 3). Valor = `prefixo(ja + q) − valorJaDevolvido`, limitado entre `0` e `T − valorJaDevolvido`; quando `ja + q = n`, é exatamente `T − valorJaDevolvido` (a linha fecha no centavo, mesmo com cancelamentos no meio). Ver dúvida Q2 para o caso em que `dividirEmParcelas` recusa.
- **`ServicoDeDevolucoes.devolver(vendedorId, usuarioId, aparelho, pedido, momento, horaAjustada, modo)`**, `@Transactional`:
  1. Confere o pedido (id, venda, motivo, observação, forma, itens). Itens por **`produtoId`** + quantidade + estado, nunca pelo id da linha (sem internet o app só tem o id provisório da linha; a venda tem uma linha por peça). `DEFEITO` exige todas as peças `DEFEITO`; `OUTRO` exige observação (dúvida Q3).
  2. Lê a venda sem trava (vendedor e maleta não mudam); de outro vendedor ou inexistente: 404 `VENDA_NAO_ENCONTRADA` (dúvida Q1).
  3. **Ordem das travas igual à do estorno** (ADR-0008): a maleta da venda (`FOR UPDATE`), depois a venda (`FOR UPDATE`). Assim devolução e estorno da mesma venda nunca se travam em ordem oposta. (O plano da fase diz "trava a venda, depois as remessas"; inverter evitaria a troca de ordem com o estorno, que já existe.)
  4. Idempotência pelo id: mesmo id e mesmo conteúdo (venda, motivo, observação, forma, peças, quantidades e estados) = 200; conteúdo diferente = 409 `DEVOLUCAO_JA_REGISTRADA_DIFERENTE`.
  5. Recusas (o impossível; ADR-0014, item 5): venda estornada (409); venda **a prazo** (soma dos `PAGAMENTO` menor que o total) = 409 `VENDA_A_PRAZO_SEM_DEVOLUCAO` "Esta venda tem parcelas; a devolução dela chega em breve." (R5, até a 6.6); maleta da venda `EM_ACERTO` = 409; peça que não está na venda = 400; quantidade acima do que ainda pode ser devolvido = 409 `DEVOLUCAO_ACIMA_DO_VENDIDO`.
  6. Prazo (ADR-0014, item 4): `DEFEITO` usa `prazo_defeito_dias`, os outros `prazo_arrependimento_dias`; dentro se `dia_Brasília(momento) − dia_Brasília(vendida_em) <= prazo`. `momento` nulo = `relogio.instant()` (rota online). Fora do prazo: modo **ONLINE** = 409 `GARANTIA_VENCIDA` "Passou do prazo da garantia. Peça autorização à dona."; modo **ENVIO** (o que a 6.7 vai ligar no push) = aceita com `situacao = EM_REVISAO` e `dentro_garantia = false` (R2). Os limites de reembolso e a autorização ficam para a 6.4.
  7. Destino da peça (ADR-0014, item 7; R4): `BOA` e maleta `ENVIADA | CONFERIDA` = `MALETA`, movimento `DEVOLUCAO_CLIENTE` positivo na maleta original (com `devolucao_id`, `venda_id` e o aparelho; maleta ligada a outro computador = 409 `MALETA_EM_OUTRO_APARELHO`, como no estorno); `BOA` de maleta `ACERTADA` e toda `DEFEITO` = `LOJA`, sem movimento.
  8. Valor por linha com `ValorDaDevolucao`; `valor_devolvido` = soma; reembolso integral (venda à vista) = `REEMBOLSO` negativo de `valor_devolvido`, na forma escolhida, com `devolucao_id` (nada se o valor for zero). Auditoria `DEVOLUCAO_REGISTRADA` (com o valor que a tela mostrou, se veio, só como alerta).
- **`ServicoDeDevolucoes.cancelar(devolucaoId, vendedorId, usuarioId, aparelho, idDoCancelamento, motivo, momento)`**: mesma ordem de travas; vendedor só a dele (404 na de outro) e só no **dia da devolução** em Brasília, depois 409 `CANCELAR_DEVOLUCAO_SO_DA_DONA`; dona (`vendedorId` nulo) a qualquer hora. Só de `ACEITA | EM_REVISAO`. Grava: estorno de cada movimento `DEVOLUCAO_CLIENTE` (negativo, `estorna_id`; se a peça já foi vendida de novo, o gatilho da V024 recusa e a API responde 409 `PECA_DA_DEVOLUCAO_JA_VENDIDA`), `ESTORNO_REEMBOLSO` positivo para cada reembolso, e `situacao = CANCELADA`. Maleta da devolução fora de `ENVIADA | CONFERIDA` com peça `MALETA` = 409 (nenhuma maleta fechada recebe movimento). Reenvio com o mesmo id = 200. Auditoria `DEVOLUCAO_CANCELADA`.
- **`ConsultasDeDevolucoes`**: a devolução com peças (nome e foto), valores em Real e em Dólar e Guarani pela cotação do dia dela; lista do vendedor (filtros de dia e venda); **"vendas com a peça lida"** (§8): pelo código (`produtoDoCodigo`, sem diferenciar maiúsculas e sem espaços nas pontas) ou pelo produto, as vendas `CONCLUIDA` **do vendedor** com essa peça, da mais nova para a mais antiga (no máximo 20), com cliente, dia, se é a prazo (bloqueada), até que dia vale cada prazo e, por linha, vendido, já devolvido e o que ainda pode ser devolvido. Nada de custo.
- **Rotas** (`@ExigePerfil`; o vendedor vem do token):
  - `POST /api/vendedor/devolucoes` (201/200/409), `GET /api/vendedor/devolucoes`, `GET /api/vendedor/devolucoes/{id}`, `GET /api/vendedor/devolucoes/vendas-com-a-peca?codigo=` (ou `produtoId=`), `POST /api/vendedor/devolucoes/{id}/cancelar`.
  - `POST /api/dona/devolucoes/{id}/cancelar` (ADMIN; "depois do dia, só a dona"). As telas e listas da dona são da 6.9.
- **`ServicoDeVendas.estornar`**: com a venda travada, devolução não cancelada = 409 `VENDA_COM_DEVOLUCAO` "Esta venda já tem devolução. Cancele a devolução antes de estornar." (R6).
- **A receber** (`ConsultasDeVendas`, `RelatoriosDoVendedor.aReceber`): passa a descontar o valor devolvido das devoluções não canceladas (`total − devolvido − pagamentos líquidos`); sem isso, a venda à vista devolvida apareceria como dívida do cliente. A venda ganha o campo `devolvido` na resposta.
- **Conciliação "estoque da loja + maletas"** (`ServicoDeEstoque`): `DEVOLUCAO_CLIENTE` (e o estorno dela) entra junto com `VENDA` e `ESTORNO_VENDA`. A peça para a loja continua contada como vendida até o acerto recebê-la (Fase 7); a conciliação com "peças para a loja" à parte é da 6.10 (dúvida Q5).
- **`RegistroDeTabelas`**: `devolucoes` e `itens_devolucao` entram como sincronizáveis com a regra declarada **"nada vai ao vendedor ainda"** (`FALSE`), até a 6.7 trocar pelo filtro do vendedor, expansão e oráculo. Motivo: o Corona Vendas de hoje recusa o lote inteiro com tabela desconhecida. As colunas novas de `pagamentos_cliente` e `movimentos_remessa` também ficam fora do pull até a 6.7 (dúvida Q4).
- **`CodigoErro`**: `DEVOLUCAO_NAO_ENCONTRADA`, `DEVOLUCAO_JA_REGISTRADA_DIFERENTE`, `DEVOLUCAO_ACIMA_DO_VENDIDO`, `GARANTIA_VENCIDA`, `VENDA_A_PRAZO_SEM_DEVOLUCAO`, `VENDA_COM_DEVOLUCAO`, `MALETA_EM_ACERTO`, `CANCELAR_DEVOLUCAO_SO_DA_DONA`, `DEVOLUCAO_JA_CANCELADA`, `DEVOLUCAO_NAO_PODE_SER_CANCELADA`, `PECA_DA_DEVOLUCAO_JA_VENDIDA`, todas com texto da loja.
- Clientes da API regerados (`npm run api:clientes`): `api-client-vendedor` e `api-client-dona`. Nenhuma tela nesta tarefa.

## Documentos

- `docs/glossario-interface.md` **antes do código**: "peça para a loja", as mensagens novas e os motivos (desistência, defeito, não serviu, outro) e estados (boa, com defeito) na linguagem da loja.
- Especificação §7.3 (e §22, versão 1.9), **antes do código**, só com o que a orquestradora aprovar das dúvidas Q1 a Q3 (itens pela peça, defeito exige peça com defeito, "outro" exige observação).
- `docs/progresso/fase-6.md` (relatório da 6.3) e a linha da 6.3 em `docs/PROGRESSO.md`.

## Testes primeiro (PostgreSQL de verdade, Testcontainers; escritos e vistos falhando antes do código)

Arquivos: `backend/src/test/java/.../devolucoes/DevolucaoDoClienteTest.java` (integração, rotas e serviço) e `ValorDaDevolucaoTest.java` (cálculo puro).

1. **Total e parcial**: devolução de toda a venda e de parte dela; a venda continua `CONCLUIDA` e igual (total, itens, pagamentos originais); `REEMBOLSO` negativo ligado à devolução; a receber da venda = 0.
2. **Centavos**: três devoluções de 1 unidade de uma linha de 3 com total 10,00 (3,33 + 3,33 + 3,34 = 10,00); a mesma com cancelamento no meio e total 10,01 (a soma fecha em 10,01); linha com desconto; linha de 12 unidades por 0,18 (onde `dividirEmParcelas` recusa) sem erro 500.
3. **Quantidade acima da vendida**, de uma vez e somando devoluções anteriores: 409, nada gravado; **segunda trava**: `INSERT` direto em `itens_devolucao` acima do vendido é recusado pelo banco.
4. **Concorrência**: duas devoluções da mesma linha ao mesmo tempo (duas threads, `CountDownLatch`), cada uma com tudo: uma 201, a outra 409; e devolução contra estorno da mesma venda ao mesmo tempo: só uma passa, sem travamento.
5. **Prazo**: venda às 23:30 de Brasília (02:30 UTC do dia seguinte); dia 7 passa, dia 8 é 409 `GARANTIA_VENCIDA`; a conta em UTC daria outro resultado (prova de que é Brasília); `DEFEITO` no dia 30 passa e no 31 não; prazo mudado pela dona vale na hora; modo ENVIO fora do prazo = `EM_REVISAO` com `dentro_garantia = false`.
6. **Destino da peça**: boa de maleta conferida volta ao saldo e pode ser vendida de novo pelo preço congelado; boa de maleta acertada vira `LOJA`, sem movimento e sem mudar saldo nenhum; `DEFEITO` sempre `LOJA`; maleta `EM_ACERTO` = 409; maleta em outro computador = 409.
7. **Cancelar**: tudo volta como era (saldo, soma dos pagamentos, a receber, situação `CANCELADA`, o devolvido da linha libera para nova devolução, e a venda volta a poder ser estornada); reenvio do cancelamento = 200; cancelar de novo com outro id = 409; vendedor no dia seguinte = 409 e a dona consegue; peça já vendida de novo = 409 sem gravar nada.
8. **Estorno com devolução**: rota do estorno = 409 `VENDA_COM_DEVOLUCAO`; `UPDATE` direto para `ESTORNADA` recusado pelo banco; venda estornada não recebe devolução (409 e gatilho).
9. **Idempotência**: 201, reenvio igual 200 (nada duplicado: movimentos, reembolso, itens), mesmo id com outra quantidade 409, mesmo id em outra venda 409.
10. **Venda a prazo**: 409 com o aviso claro; venda paga em partes que somam o total conta como à vista.
11. **Permissões**: vendedor B devolve venda de A = 404 e não vê nem cancela a devolução de A (404); vendedor na rota da dona = 403; sem login = 401; "vendas com a peça" nunca mostra venda de outro vendedor.
12. **Nada de custo**: nenhuma resposta das rotas novas tem chave com "custo" (nem "fornecedor").
13. **Conciliação**: "estoque da loja + maletas" sem diferença na peça depois de devolução `MALETA`, `LOJA` e cancelamento; saldo da maleta = enviada − vendas + devoluções boas (§7.1).
14. **Imutabilidade**: `UPDATE` e `DELETE` em `itens_devolucao` recusados; `UPDATE` de valor, venda ou vendedor em `devolucoes` recusado; transição inválida (`CANCELADA → ACEITA`) recusada.
15. **Busca pela peça lida**: código com maiúsculas e espaços acha; mostra vendido, devolvido e o que falta; venda a prazo marcada; venda estornada fora.
16. **Pedido malformado**: sem motivo, motivo fora da lista, `DEFEITO` com peça boa, `OUTRO` sem observação, forma fora da lista, peça repetida com o mesmo estado, quantidade zero: 400 com mensagem em português.
17. **Registro de tabelas**: as duas tabelas estão no registro com regra declarada, o pull não entrega nenhuma linha delas e as colunas novas de pagamentos e movimentos não saem no pull (até a 6.7); os testes de convenção (`VerificadorDeConvencoes`, `RegrasDoVendedorTest`) passam.

## Ordem dos commits (cada passo com push e Situação atualizada)

1. `docs`: glossário e especificação (só o aprovado).
2. `test(devolucoes)`: os testes acima, vermelhos.
3. `feat(db)`: V036.
4. `feat(devolucoes)`: cálculo, serviço, consultas e rotas.
5. `feat(vendas)`: estorno recusado, a receber, conciliação, registro de tabelas.
6. `chore(api)`: clientes regerados.
7. `docs`: `fase-6.md` e `PROGRESSO.md`. Localmente: `npm run api:test`, `lint`, `typecheck`, `format:check` e `barreira:varrer` (o cliente gerado do vendedor vai no pacote do Corona Vendas).

## Riscos

- **O Corona Vendas de hoje ainda não entende devolução.** Se uma devolução for gravada antes da 6.7 e da 6.8, o app recebe pelo pull o `REEMBOLSO` e o movimento `DEVOLUCAO_CLIENTE` (o saldo da maleta fica certo) e mostraria a venda com valor a receber. Nenhuma tela grava devolução antes da 6.8 (vendedor) e da 6.9 (dona); a 6.7 precisa levar `devolucoes` ao app e descontar o devolvido no "a receber" local. Fica anotado no relatório.
- **Ordem das travas**: invertida em relação ao texto do plano para casar com o estorno; o teste 4 prova que não trava.
- **Cancelar depois de revender a peça**: recusado (409); a dona resolve no acerto.
- **Peça para a loja e o acerto**: o acerto (Fase 7) recebe a peça; até lá ela conta como vendida na conciliação. Cancelar uma devolução cuja peça já foi recebida no acerto não existe ainda (Fase 7 precisa recusar).
- **Hora da venda**: `vendas.vendida_em` usa o `now()` do banco na rota online, não o relógio de teste; os testes de prazo criam a venda pelo serviço com a hora explícita.
- **CI**: o filtro do job de duas pontas não cita o pacote novo `devolucoes`; esta tarefa o aciona pela migração. A 6.7 deve acrescentar o pacote ao filtro (`docs/ci.md`).
- Número da migração: se outra tarefa ocupar a V036 antes, uso o próximo livre.

## O que não fazer

- Nada de autorização, revisão da dona, limite semanal, valor de aprovação, taxa de devolução (6.4); troca e crédito (6.5); venda a prazo e parcelas (6.6); outbox, push e filtro real do pull (6.7); telas (6.8 e 6.9); relatórios de devolução (6.10); comissão e `acerto_eventos` (Fase 7).
- Nunca `UPDATE` de conteúdo nem `DELETE`; a venda original não muda; nenhum valor vindo do app vale; nenhuma maleta fechada recebe movimento; nenhuma migração publicada editada.

## Dúvidas (com o padrão que sigo se não houver resposta)

| Nº | Pergunta | Padrão |
| --- | --- | --- |
| Q1 | Venda ou devolução de outro vendedor: 404 (como o estorno, sem dizer que existe) ou 403? O plano fala em "403 cruzado". | **404** nas rotas do vendedor; 403 para vendedor nas rotas da dona |
| Q2 | Quando `dividirEmParcelas` recusa (linha com muitas peças e valor de poucos centavos, como 0,18 em 12), qual regra vale? | O acumulado `arredondar(T × k ÷ n)` só nesse caso; sempre limitado e fechando no total |
| Q3 | Motivo `DEFEITO` exige todas as peças com defeito? `OUTRO` exige observação? | **Sim** e **sim** (o prazo maior só vale para peça com defeito) |
| Q4 | As tabelas novas entram no registro com "nada vai ao vendedor" até a 6.7? | **Sim** (o app de hoje recusa tabela desconhecida) |
| Q5 | Peça para a loja conta como vendida na conciliação até o acerto? | **Sim**; a conciliação com peças para a loja é da 6.10 |
| Q6 | Cancelar só de `ACEITA` ou `EM_REVISAO`? | **Sim** (depois da decisão da dona, não) |

## Custo de CI

Um rodada no fim (o rascunho não gasta): `backend` (~4 min), `sync-duas-pontas` (~4 min, pela migração), `frontend` (~3 min, pelos clientes regerados), `migracoes`, `mudancas` e `ci` (~1 min cada). Pela 6.2, cerca de **12 minutos de execução (por volta de 16 cobrados)**; o dobro se uma correção pedir outra rodada.
