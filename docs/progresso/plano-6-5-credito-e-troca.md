# Plano da tarefa 6.5: crédito do cliente e troca

Tarefa crítica (modo de plano). **Nenhum código antes do `[ORQUESTRADORA] APROVADO`.** Base: [fase-6-plano.md](fase-6-plano.md) (Regras da Fase 6, seção 6.5, Dúvidas R1 a R6 respondidas "Sim" e D4 a D6 pelo padrão seguro), [ADR-0014](../decisoes/ADR-0014-devolucao-troca-e-garantia.md) (Aceito; itens 1 a 5, 8, 9, 11 e 12), ADR-0012 (C1, crédito como forma de pagamento que não reduz o que o vendedor deve), especificação §7.2, §7.3 (diferença na troca, crédito do cliente, corrigir), §9 (Vendas; Devolução e troca), §13 e §17.7, e o que a 6.2, a 6.3 e a 6.4 deixaram em [fase-6.md](fase-6.md).

## O que já existe (não refazer)

- **Devolução (V036 e V037, `api/devolucoes`):** `devolucoes.destino` já aceita `TROCA` (só `REEMBOLSO` é gravado hoje); `ServicoDeDevolucoes.devolver(..., ModoDaDevolucao)` com as travas vendedor (`pg_advisory_xact_lock`) → maleta → venda → autorização, prazo em dias de Brasília, autorização, limite da semana, valor de aprovação e revisão; `cancelar` (estorno dos movimentos com o mesmo tipo e `estorna_id`, `ESTORNO_REEMBOLSO`); `ValorDaDevolucao` e `RegrasDaDevolucao` (conferência das peças e o preço pago por unidade).
- **Venda (V022, `api/vendas`):** `ServicoDeVendas.vender` (trava a maleta, maleta `CONFERIDA` e no computador certo, preço congelado, desconto no limite, pago ≤ total; pago < total = venda a prazo) e `estornar` (só a venda inteira, vendedor no mesmo dia, um `REEMBOLSO` por `PAGAMENTO`, na mesma forma). O push (5.5) chama os dois com a hora corrigida.
- **Maleta (V021 e V025):** `TROCA_ENTRADA` (positivo) e `TROCA_SAIDA` (negativo) já existem no `CHECK` de `movimentos_remessa`, sem uso; o estorno (com `estorna_id`) pode ter qualquer sinal; a V024 nunca deixa o saldo negativo. A V036 amarrou `devolucao_id` só ao tipo `DEVOLUCAO_CLIENTE` (esta tarefa amplia).
- `pagamentos_cliente` (V022 e V036): `PAGAMENTO | REEMBOLSO | ESTORNO_REEMBOLSO`, forma `DINHEIRO | PIX | CARTAO | CHEQUE`, `devolucao_id` e `estorna_id`.
- `RegistroDeTabelas` (regra `FALSE` para as tabelas da Fase 6 até a 6.7), `VendedorAtivo`, `Validacoes`, `UuidV7`, `OrigemDoDispositivo`, `ServicoDeAuditoria`, `RelogioDeTeste`, `ApoioDasDevolucoes` (massa dos testes da 6.3 e da 6.4).

## O modelo do dinheiro (o ponto que decide o resto)

Um caminho só para o dinheiro (ADR-0014, itens 8 e 9), sem mexer em nenhuma consulta de "a receber":

1. **Venda A (a de origem):** a devolução da troca grava `REEMBOLSO` **forma `CREDITO`** (negativo, `devolucao_id` e `credito_id`), no lugar do reembolso em dinheiro. O crédito do cliente nasce com o mesmo valor (origem `TROCA`).
2. **Venda B (a nova):** o crédito entra como `PAGAMENTO` **forma `CREDITO`** (`credito_id`), no valor de `min(valor de A, total de B)`; o uso fica em `usos_credito`. O resto de B o cliente paga (à vista, ou a prazo se pagar menos) ou, se B for mais barata, a sobra fica como crédito em aberto (D4).
3. Assim a C1 do ADR-0012 fecha **por venda** sem caso especial: A = 100 paga em dinheiro, B = 150: em A, `100 − 100 devolvido = 100 pago − 100 em crédito = 0`; em B, `150 − 0 = 100 em crédito + 50 em dinheiro`. B = 60: em B, `60 = 60 em crédito`, e o crédito fica com 40 em aberto. O "a receber" de hoje (total − devolvido − Σ pagamentos) e o "pago" que decide "venda a prazo" continuam certos sem mudança.
4. O crédito usado **não é dinheiro**: na devolução, `valor_reembolsado` continua sendo só o dinheiro (zero na troca) e uma coluna nova, `valor_em_credito`, guarda o que voltou em crédito. O limite da semana e o valor de aprovação (6.4) olham só o dinheiro.

## Migração `V038__credito_e_troca.sql` (o próximo número livre na `main` na hora do código)

1. **`trocas`** (sincronizável; só a situação e o cancelamento mudam): `venda_origem_id`, `devolucao_id` (único), `venda_nova_id` (único), `cliente_id`, `vendedor_id`, `diferenca NUMERIC(14,2)` (qualquer sinal: total de B − valor devolvido de A), `cotacao_id`, `trocada_em`, `hora_ajustada`, `registrada_por`, `situacao` (`ATIVA | CANCELADA`), `cancelamento_id` (único), `cancelada_em`, `cancelada_por`, `motivo_cancelamento`, com `CHECK` de "tudo ou nada" do cancelamento (como `devolucoes`).
   - Gatilho de conteúdo imutável e só `ATIVA → CANCELADA`; ramo novo na função de pertença (vendedor, cliente e as três chaves não mudam).
   - **Conferência no fim da transação** (`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`): a devolução é `destino = TROCA` da venda de origem; a venda nova tem `troca_id` = esta troca; as duas são do mesmo cliente e vendedor; `diferenca = total(B) − valor_devolvido(A)`.
2. **`vendas.troca_id`** (chave para `trocas`, `DEFERRABLE INITIALLY DEFERRED`, porque a venda B é gravada antes da linha da troca; índice único). `proteger_venda` recriada inteira com `troca_id` imutável; `recusar_estorno_com_devolucao` recriada inteira: venda com `troca_id` só vira `ESTORNADA` se a troca já estiver `CANCELADA` (o "Cancelar troca" grava a troca primeiro).
3. **`devolucoes`:** `valor_em_credito NUMERIC(14,2) NOT NULL DEFAULT 0` com `CHECK (valor_reembolsado + valor_em_credito <= valor_devolvido)` e `CHECK (destino <> 'TROCA' OR (valor_reembolsado = 0 AND valor_em_credito = valor_devolvido))`; `proteger_devolucao` recriada inteira (tudo o que a V037 protege, mais `valor_em_credito`, e a devolução de troca só vira `CANCELADA` com a troca já `CANCELADA`).
4. **`creditos_cliente`** (sincronizável; só `valor_utilizado`, a situação e o cancelamento mudam): `cliente_id`, `vendedor_id` (quem pode usar: quem gerou, ou para quem a dona transferiu), `origem` (`TROCA | DEVOLUCAO | TRANSFERENCIA`), `troca_id`, `devolucao_id`, `credito_origem_id`, `valor > 0`, `valor_utilizado` (`0 ≤ valor_utilizado ≤ valor`), `situacao` (`ABERTO | UTILIZADO | CANCELADO`), `cotacao_id`, `gerado_em`, `hora_ajustada`, `registrado_por`, `cancelado_em`, `cancelado_por`.
   - `CHECK`s: a origem tem as ligações certas (`TROCA` com troca e devolução; `DEVOLUCAO` com devolução e sem troca; `TRANSFERENCIA` com `credito_origem_id`); `UTILIZADO` se e só se `valor_utilizado = valor`; `CANCELADO` só com `valor_utilizado = 0` e com os dados do cancelamento.
   - Gatilho de inserção: cliente e vendedor iguais aos da devolução (ou da troca); na transferência, o vendedor é outro e o cliente é o mesmo do crédito de origem.
   - Gatilho de alteração (**segunda trava do saldo**): nada muda além de `valor_utilizado`, situação e cancelamento; `valor_utilizado` tem de ser igual à soma de `usos_credito` do crédito; `CANCELADO` não volta. Ramo novo na função de pertença (vendedor e cliente não mudam).
5. **`usos_credito`** (sincronizável, **imutável**): `credito_id`, `tipo` (`USO | ESTORNO | TRANSFERENCIA`), `venda_id` e `pagamento_id` (único; em `USO` e `ESTORNO`), `credito_destino_id` (em `TRANSFERENCIA`), `valor` (`USO` e `TRANSFERENCIA` positivos, `ESTORNO` negativo), `estorna_id` (único; só em `ESTORNO`), `usado_em`.
   - Gatilho de inserção: trava o crédito (`FOR UPDATE`), recusa crédito `CANCELADO`, recusa soma dos usos fora de `0..valor`; no `USO`, a venda é do mesmo cliente e do mesmo vendedor do crédito e o pagamento é `CREDITO` deste crédito com o mesmo valor.
6. **`pagamentos_cliente`:** `credito_id` (chave para `creditos_cliente`); forma ganha `CREDITO` (`CHECK` recriado); `CHECK ((forma = 'CREDITO') = (credito_id IS NOT NULL))`.
7. **`movimentos_remessa`:** o `CHECK` da V036 passa a `(tipo IN ('DEVOLUCAO_CLIENTE', 'TROCA_ENTRADA')) = (devolucao_id IS NOT NULL)`; `TROCA_SAIDA` exige `venda_id`. Antes, um `DO` que para se já houver movimento `TROCA_*` (nenhum caminho grava hoje), como a V036 e a V037 fizeram.
8. `REVOKE ALL ... FROM PUBLIC` nas três tabelas novas. Nenhuma migração publicada é editada: as funções da V022, da V036 e da V037 são recriadas aqui, inteiras.

## Backend

Dois pacotes novos, `api/creditos` e `api/trocas` (entram no filtro `sync` do `ci.yml` e em `docs/ci.md`; dúvida T12), e mudanças em `api/vendas` e `api/devolucoes`.

**Ordem das travas (vale para todos os caminhos desta tarefa):** vendedor (`pg_advisory_xact_lock`, só na devolução e na troca, como na 6.4) → maletas em ordem de `id` → vendas em ordem de `id` → devolução, troca e autorização → créditos em ordem de `id`. A venda comum e o estorno pegam maleta → venda → créditos; a transferência pega só os créditos. Nenhum caminho pega um crédito antes de uma maleta ou venda, então a ordem nunca se inverte. Fica comentada na classe de cada serviço.

- **`ServicoDeCreditos`** (`api/creditos`):
  - `gerar(...)` (interno, na transação de quem chama): crédito `TROCA` ou `DEVOLUCAO`, com o id do app (troca) ou do servidor (devolução), cotação do dia.
  - `usar(...)` (interno, chamado por `ServicoDeVendas` para cada pagamento `CREDITO`): trava o crédito; inexistente ou de outro vendedor = 404 `CREDITO_NAO_ENCONTRADO` (nunca diz que existe); outro cliente = 409 `CREDITO_DE_OUTRO_CLIENTE`; `CANCELADO` ou `UTILIZADO` = 409 `CREDITO_SEM_SALDO`; acima do saldo = 409 `CREDITO_ACIMA_DO_SALDO` (no valor exato passa); grava `USO`, soma em `valor_utilizado` e passa a `UTILIZADO` ao zerar.
  - `estornarUso(...)` (interno, no estorno da venda paga com crédito e no "Cancelar troca"): `ESTORNO` negativo com `estorna_id`; o crédito volta a `ABERTO`.
  - `cancelar(...)` (interno, no "Cancelar devolução" e no "Cancelar troca"): só com `valor_utilizado = 0`, senão 409 `CREDITO_JA_USADO`.
  - `transferir(creditoId, vendedorDestino, idDaOperacao, usuarioId)` (só ADMIN): o saldo inteiro vai para um crédito novo `TRANSFERENCIA` do outro vendedor; no original, um uso `TRANSFERENCIA` (fica `UTILIZADO`); idempotência pelo id (200 igual, 409 diferente); saldo zero = 409 `CREDITO_SEM_SALDO`; mesmo vendedor ou vendedor inativo = 400. Auditoria `CREDITO_TRANSFERIDO`.
- **`ServicoDeVendas`:**
  - `PagamentoDoPedido` ganha `creditoId` (opcional; obrigatório com `CREDITO`, proibido nas outras formas; o mesmo crédito não aparece duas vezes na venda); a idempotência compara também o crédito.
  - Cada pagamento `CREDITO` chama `ServicoDeCreditos.usar`, depois da trava da maleta, em ordem de `id` do crédito.
  - Uso interno pela troca: `vender(..., trocaId)` grava `troca_id` e o movimento `TROCA_SAIDA` em vez de `VENDA`.
  - `estornar`: venda com `troca_id` = 409 `VENDA_DE_TROCA` ("Use Cancelar troca"), a não ser chamado pelo "Cancelar troca"; os movimentos `VENDA` e `TROCA_SAIDA` são estornados (mesmo tipo, com `estorna_id`); cada `PAGAMENTO` `CREDITO` vira `REEMBOLSO` `CREDITO` com o estorno do uso (o crédito volta, dúvida T8); as outras formas, como hoje.
- **`ServicoDeDevolucoes`:**
  - `devolver` (destino `REEMBOLSO`) de venda paga com crédito: o que foi pago em crédito (líquido do que já voltou em crédito) volta **primeiro** como crédito novo, origem `DEVOLUCAO` (`REEMBOLSO` `CREDITO` + `valor_em_credito`); só o resto em dinheiro na forma escolhida (dúvida T9). O limite da semana, o valor de aprovação e o "reembolso maior que o pago" passam a olhar o dinheiro (para a venda sem crédito, igual a hoje).
  - Uso interno pela troca: `devolver(..., destino TROCA, idDoCredito)`: `valor_reembolsado = 0`, `valor_em_credito = valor_devolvido`, movimento `TROCA_ENTRADA` em vez de `DEVOLUCAO_CLIENTE`, sem limite da semana nem valor de aprovação (nenhum dinheiro sai, dúvida T2); prazo, autorização, maleta em acerto e computador iguais à 6.3 e à 6.4.
  - `cancelar`: devolução de troca = 409 `DEVOLUCAO_DE_TROCA` ("Use Cancelar troca"), a não ser chamado pelo "Cancelar troca"; com crédito gerado: `ESTORNO_REEMBOLSO` `CREDITO` e o crédito cancelado (409 `CREDITO_JA_USADO` se já foi usado).
- **`ServicoDeTrocas`** (`api/trocas`), `@Transactional`, tudo ou nada:
  - `trocar(vendedorId, usuarioId, aparelho, pedido, momento, horaAjustada, modo)`. O app manda: `id` da troca, `creditoId`, a devolução (`id`, venda A, motivo, observação, peças pelo produto com quantidade e estado, `autorizacaoId` opcional), a venda nova (`id`, maleta, peças com desconto, pagamentos do resto com ids, `totalConferido`) e o `pagamentoDoCreditoId`. **Nunca valor de A, diferença nem valor do crédito.**
  - Passos: confere o pedido; trava o vendedor, as duas maletas (em ordem de `id`), a venda A; idempotência pela troca (mesmo conteúdo 200; diferente 409 `TROCA_JA_REGISTRADA_DIFERENTE`; id de devolução, venda, crédito ou pagamento já usado em outra coisa = 409); devolução de A pelo `ServicoDeDevolucoes` (destino `TROCA`); venda B pelo `ServicoDeVendas` (`troca_id`, `TROCA_SAIDA`), com o pagamento `CREDITO` do valor `min(A, B)` calculado aqui e os pagamentos do resto; crédito `TROCA` de A gerado e usado; linha de `trocas` com a diferença. B mais barata: o resto tem de vir vazio (400) e a sobra fica em aberto.
  - Qualquer recusa no meio (B sem saldo, desconto acima do limite, maleta de B não conferida, A fora do prazo na rota, venda A a prazo, maleta de A em acerto) desfaz tudo: nada fica gravado.
  - Auditoria `TROCA_REGISTRADA` (venda A, venda B, valor de A, total de B, diferença, crédito em aberto), além das da devolução e da venda.
  - `cancelar(trocaId, vendedorId, usuarioId, aparelho, idDoCancelamento, motivo, momento)`: vendedor só a dele e no dia da troca (Brasília), senão 409 `CANCELAR_TROCA_SO_DA_DONA`; a dona a qualquer hora. Travas maletas → vendas A e B → devolução → troca → crédito. Recusa: crédito da troca usado fora da própria troca (409 `CREDITO_DA_TROCA_JA_USADO`), peça A já vendida de novo, maleta de A ou de B que não está mais com o vendedor. Ordem: troca `CANCELADA`; estorno da venda B (B volta à maleta, o uso do crédito é estornado, o resto pago volta como `REEMBOLSO` na forma em que foi pago); cancelar a devolução A (A sai da maleta de novo, `ESTORNO_REEMBOLSO` `CREDITO`); o crédito fica `CANCELADO`. Reenvio com o mesmo id = 200; outro id numa troca cancelada = 409 `TROCA_JA_CANCELADA`. Auditoria `TROCA_CANCELADA`.
- **Consultas:** `ConsultasDeTrocas` (a troca com as peças que voltaram e as que saíram, nome e foto, valores em Real, Dólar e Guarani pela cotação do dia dela, diferença, crédito em aberto; lista do vendedor só dele; lista da dona com filtro de vendedor) e `ConsultasDeCreditos` (crédito com saldo, origem, usos e a cotação do dia dele; lista do vendedor só os créditos dele, com filtro de cliente; lista da dona com filtro de cliente, vendedor e situação, padrão "em aberto"). Nada de custo. A venda (`ConsultasDeVendas`) mostra o pagamento `CREDITO` e, se for de troca, o `trocaId`.
- **Rotas** (`@ExigePerfil`; o vendedor vem do token):
  - Vendedor: `POST /api/vendedor/trocas` (201/200/409), `GET /api/vendedor/trocas`, `GET /api/vendedor/trocas/{id}`, `POST /api/vendedor/trocas/{id}/cancelar`; `GET /api/vendedor/creditos`, `GET /api/vendedor/creditos/{id}`; `POST /api/vendedor/vendas` aceita `CREDITO` com `creditoId`.
  - Dona (ADMIN): `GET /api/dona/trocas`, `GET /api/dona/trocas/{id}`, `POST /api/dona/trocas/{id}/cancelar`; `GET /api/dona/creditos`, `GET /api/dona/creditos/{id}`, `POST /api/dona/creditos/{id}/transferir`. As telas são da 6.8 e da 6.9.
- **Conciliações e consultas de peça:** as três consultas que hoje contam `VENDA`, `ESTORNO_VENDA` e `DEVOLUCAO_CLIENTE` passam a contar também `TROCA_SAIDA` e `TROCA_ENTRADA` (`ServicoDeEstoque.diferencasComMaletas`, `ConsultasDeEstoqueECompras` e a coluna "vendida" de `ConsultasDaMaletaDoVendedor`).
- **Envio (push):** a `VENDA` formato 1 não leva `creditoId`; o `OperacoesDoVendedor` passa nulo e a venda com `CREDITO` pelo envio é recusada pela regra do serviço (a 6.7 traz o formato com o crédito e os tipos `TROCA` e `CANCELAR_TROCA`). O resumo canônico da venda não muda.
- **`RegistroDeTabelas`:** `trocas`, `creditos_cliente` e `usos_credito` com a regra `FALSE` (como a 6.3 e a 6.4) até a 6.7; `vendas.troca_id`, `pagamentos_cliente.credito_id` e `devolucoes.valor_em_credito` **fora** das listas de colunas do pull (o Corona Vendas recusa o lote com coluna desconhecida).
- **`CodigoErro`** (texto da loja, sem valor em Real no texto): `TROCA_NAO_ENCONTRADA`, `TROCA_JA_REGISTRADA_DIFERENTE`, `TROCA_JA_CANCELADA`, `TROCA_NAO_PODE_SER_CANCELADA`, `CANCELAR_TROCA_SO_DA_DONA`, `CREDITO_DA_TROCA_JA_USADO`, `VENDA_DE_TROCA`, `DEVOLUCAO_DE_TROCA`, `CREDITO_NAO_ENCONTRADO`, `CREDITO_DE_OUTRO_CLIENTE`, `CREDITO_SEM_SALDO`, `CREDITO_ACIMA_DO_SALDO`, `CREDITO_JA_USADO`, `CREDITO_JA_TRANSFERIDO_DIFERENTE`.
- Clientes da API regerados (`npm run api:clientes`). Nenhuma tela nesta tarefa.

## Documentos

- `docs/glossario-interface.md` **antes do código** (parte 6, "Troca e crédito do cliente"): forma de pagamento **Crédito do cliente**; situações do crédito (**Em aberto**, **Usado**, **Cancelado**) e da troca (**Feita**, **Cancelada**); botões (**Registrar troca**, **Cancelar troca**, **Usar crédito**, **Transferir crédito**); "diferença a pagar" e "fica de crédito"; as 14 mensagens novas.
- Especificação **antes do código** (v1.11, §22): §7.3 (o dinheiro da troca pelo crédito, troca fora do limite da semana e do valor de aprovação, crédito primeiro na devolução de venda paga com crédito, estorno de venda paga com crédito devolve o crédito, venda e devolução de troca só pelo "Cancelar troca", transferência pela dona) e §9 (`trocas`, `vendas.troca_id`, `creditos_cliente` com `TRANSFERENCIA`, `usos_credito` com o tipo, `pagamentos_cliente.credito_id` e a forma `CREDITO`, `devolucoes.valor_em_credito`). ADR-0014: linha datada no item 9 (transferência e crédito primeiro na devolução).
- `docs/progresso/fase-6.md` (relatório da 6.5, com o que a 6.7, a 6.8, a 6.9 e a 6.10 herdam), a linha da 6.5 em `docs/PROGRESSO.md`, e `docs/ci.md` com os pacotes novos.

## Testes primeiro (PostgreSQL de verdade, Testcontainers; escritos e vistos falhando antes do código)

Arquivos: `backend/src/test/java/.../trocas/TrocaTest.java`, `.../creditos/CreditoDoClienteTest.java` e ajustes pontuais em `PullDoVendedorTest` (19 → 22 tabelas) e, se preciso, em `VendasDoVendedorTest` e `DevolucaoDoClienteTest` (pedido com o campo novo, opcional, sem mudar os casos).

1. **B mais cara:** A 100,00 (boa, maleta aberta) por B 150,00 com 50,00 em dinheiro: devolução `TROCA` com `valor_reembolsado` 0 e `valor_em_credito` 100,00; `REEMBOLSO CREDITO −100,00` na venda A; crédito `TROCA` de 100,00 `UTILIZADO`; `PAGAMENTO CREDITO 100,00` e `DINHEIRO 50,00` na venda B; diferença 50,00; `TROCA_ENTRADA +1` de A e `TROCA_SAIDA −1` de B; saldo das duas peças na maleta. Com só 20,00 pagos, B fica com 30,00 a receber (a prazo).
2. **B mais barata:** A 100,00 por B 60,00: uso de 60,00, crédito com 40,00 `ABERTO`, diferença −40,00, nenhum dinheiro; pagamento do resto mandado junto = 400.
3. **Igual:** diferença 0,00 e crédito `UTILIZADO`.
4. **Várias peças:** duas linhas de A (uma com desconto que não divide exato) por três peças de B; os valores de A pelo `ValorDaDevolucao`, centavo a centavo.
5. **A com defeito e B da maleta:** A vai para a loja (sem movimento), B sai como `TROCA_SAIDA`. **A de maleta acertada** e B da maleta nova (duas maletas, as travas em ordem de `id`).
6. **Tudo ou nada:** B sem saldo, desconto de B acima do limite, maleta de B não conferida, A fora do prazo na rota, venda A a prazo, maleta de A em acerto: 409 e **nenhuma linha nova** em nenhuma das tabelas (contagem antes e depois).
7. **Prazo e autorização:** troca no último dia do prazo passa e no dia seguinte 409 `GARANTIA_VENCIDA` (virada em Brasília contra UTC); com autorização aprovada passa e a autorização fica `USADA`; no modo de envio, fora do prazo = devolução `EM_REVISAO` e a troca gravada.
8. **Sem dinheiro, sem limite:** com limite semanal de 10,00 e valor de aprovação de 10,00, a troca de 100,00 entra `ACEITA`; a devolução em dinheiro da mesma semana continua contando só o dinheiro.
9. **Crédito usado em parte, de novo e até zerar:** crédito de 40,00: venda com 15,00 (fica 25,00), outra com 25,00 (`UTILIZADO`), a terceira = 409 `CREDITO_SEM_SALDO`; 25,01 num crédito de 25,00 = 409 `CREDITO_ACIMA_DO_SALDO`; crédito de outro cliente 409; de outro vendedor 404; `CREDITO` sem `creditoId` 400; o mesmo crédito duas vezes na venda 400; idempotência da venda com crédito.
10. **Dois usos ao mesmo tempo:** a transferência da dona e uma venda com o mesmo crédito em duas threads (`CountDownLatch`): só uma passa; a soma nunca passa do valor.
11. **Estorno de venda paga com crédito:** o crédito volta (uso `ESTORNO`, `ABERTO`, saldo restaurado), `REEMBOLSO CREDITO` na venda; venda de troca = 409 `VENDA_DE_TROCA`.
12. **Devolução de venda paga com crédito:** B = 100,00 paga com 60,00 em crédito e 40,00 em dinheiro: devolver tudo = crédito novo `DEVOLUCAO` de 60,00 e 40,00 em dinheiro; devolver metade = 50,00 em crédito; cancelar a devolução cancela o crédito novo; com o crédito novo já usado = 409 `CREDITO_JA_USADO`.
13. **Cancelar troca:** tudo como era (saldo das peças A e B, pagamentos líquidos de A e B, crédito `CANCELADO`, venda B `ESTORNADA`, devolução `CANCELADA`, troca `CANCELADA`); reenvio 200; outro id 409; vendedor no dia seguinte = 409 `CANCELAR_TROCA_SO_DA_DONA` e a dona consegue; sobra já usada noutra venda = 409 `CREDITO_DA_TROCA_JA_USADO`; peça A já vendida de novo 409; cancelar a devolução A direto = 409 `DEVOLUCAO_DE_TROCA`.
14. **Idempotência da troca:** 201, 200 com o mesmo conteúdo, 409 com outra peça B; id de devolução ou de venda já usado = 409.
15. **Comissão e eventos de acerto (consulta, sem a Fase 7):** a troca aparece como uma devolução (−A) e uma venda (+B) nas mesmas consultas que a 7.5 vai ler; vendido − devolvido do vendedor = B − A; nenhum evento de troca à parte.
16. **Conciliações:** C1 por venda e no total (`total − devolvido = Σ pagamentos líquidos, com o crédito`); Σ créditos não cancelados − Σ usos líquidos = Σ saldos em aberto, e `valor_utilizado` = Σ usos em cada crédito; saldo da maleta; "estoque da loja + maletas" sem diferença (com `TROCA_*`); maleta do vendedor com B como vendida e A de volta.
17. **Transferência:** crédito de 40,00 do vendedor A para B: B usa, A não vê e não usa (404); o original fica `UTILIZADO`; repetir 200, outro destino com o mesmo id 409; saldo zero 409; para o mesmo vendedor 400.
18. **Permissões:** vendedor B não troca venda de A (404), não vê troca nem crédito de A (404 e fora da lista); vendedor nas rotas da dona 403; sem login 401.
19. **Nada de custo:** nenhuma resposta das rotas novas tem chave com "custo" (nem "fornecedor").
20. **Imutabilidade e segundas travas (banco):** `UPDATE` de conteúdo e `DELETE` em `trocas`, `creditos_cliente` e `usos_credito` recusados; `INSERT` direto de uso acima do valor recusado; `valor_utilizado` diferente da soma dos usos recusado; `CANCELADO → ABERTO` recusado; pagamento `CREDITO` sem crédito recusado; troca com diferença errada ou devolução que não é `TROCA` recusada no fim da transação; venda de troca estornada sem a troca cancelada recusada.
21. **Pedido malformado:** sem peças A, sem peças B, peça B repetida, valores com três casas, motivo fora da lista: 400.
22. **Registro de tabelas e pull:** as três tabelas novas no registro com regra declarada; o pull não entrega linha delas nem as colunas novas; `PullDoVendedorTest` com 22 tabelas; `VerificadorDeConvencoes` e `RegrasDoVendedorTest` passam; a venda com `CREDITO` pelo envio (formato 1) é recusada.

## Ordem dos commits (cada passo com push e Situação atualizada)

1. `docs`: glossário, especificação v1.11 e a linha datada do ADR-0014 (só o aprovado).
2. `test(trocas)`, `test(creditos)`: os testes acima, vermelhos.
3. `feat(db)`: V038.
4. `feat(creditos)`: crédito, uso na venda, estorno da venda com crédito, devolução de venda paga com crédito, transferência, consultas e rotas.
5. `feat(trocas)`: troca e "Cancelar troca", consultas e rotas; recusas na venda e na devolução de troca.
6. `fix(estoque)`: `TROCA_*` nas três consultas de peça e conciliação.
7. `chore(api)`: registro de tabelas e clientes regerados; `ci`: pacotes novos no filtro `sync` e em `docs/ci.md`.
8. `docs`: `fase-6.md` e `PROGRESSO.md`. Localmente: `npm run api:test`, `lint`, `typecheck`, `format:check` e `barreira:varrer` (o cliente gerado do vendedor vai no pacote do Corona Vendas).

## Riscos

- **Composição dos serviços:** a troca chama a devolução e a venda na mesma transação. Para não duplicar regra, os dois ganham um caminho interno (destino `TROCA`, `troca_id`, tipo do movimento); as rotas e o envio de hoje continuam no caminho de sempre, e os testes da 6.3, da 6.4 e da Fase 4 rodam sem mudança de caso.
- **Duas maletas na mesma operação:** a troca é a única que trava duas maletas; vem depois da trava do vendedor (duas trocas do mesmo vendedor nunca correm juntas) e em ordem de `id`. Nenhum outro caminho trava duas maletas.
- **Chave adiada (`DEFERRABLE`):** a venda B nasce antes da linha da troca; o erro de uma conferência adiada só aparece no `COMMIT` e o serviço precisa traduzir para 409 (os testes do item 20 provam que o banco recusa e que o serviço nunca chega lá pelo caminho normal).
- **Recriar `proteger_venda`, `recusar_estorno_com_devolucao` e `proteger_devolucao`:** o `CREATE OR REPLACE` precisa manter tudo o que a V022, a V036 e a V037 conferiam; os testes de imutabilidade delas rodam de novo e provam.
- **Pull de hoje:** os pagamentos `CREDITO` e os movimentos `TROCA_*` chegam ao Corona Vendas pelas tabelas que ele já conhece (as colunas não mudam). O saldo da maleta no computador fica certo; até a 6.8, o app mostra "CREDITO" sem o nome da tela e não conta `TROCA_SAIDA` como vendida nos números locais (`consultas-locais.ts`). Nenhuma tela grava troca até a 6.8 (vai no relatório como herança).
- **Relatório de recebimentos do vendedor:** a forma `CREDITO` aparece como mais uma linha (entra e sai; fica negativa quando sobra crédito em aberto). O total continua certo; separar crédito de dinheiro é da 6.10.
- **Uso acima do saldo pelo envio:** sem internet o saldo do crédito no computador do vendedor é exato (um computador por vendedor), mas a dona pode transferir o crédito enquanto ele está sem internet. Nesta tarefa, acima do saldo é sempre 409 (a venda com crédito não chega pelo envio ainda); a 6.7 decide a revisão do uso acima do saldo (ADR-0014, item 9) com o formato novo da venda.
- **Taxa de devolução (6.4):** a troca entra como devolução (A) e como venda (B) na conta, sem mudança no código (dúvida T2).
- Número da migração: se outra tarefa ocupar a V038 antes, uso o próximo livre.

## O que não fazer

- Nada de outbox, push e filtro do pull das tabelas novas (6.7: tipos `TROCA`, `CANCELAR_TROCA`, formato da venda com crédito); telas (6.8 e 6.9); troca e devolução registradas pela dona (6.9); troca com B a prazo pago em parcelas e devolução de venda a prazo (6.6); sobra da troca devolvida em dinheiro (só se a dona responder D4); relatórios de trocas e créditos (6.10); comissão e acerto (Fase 7).
- Nunca `UPDATE` de conteúdo nem `DELETE`; nenhum valor de A, diferença ou valor de crédito vindo do app vale; nenhuma migração publicada editada; nenhum crédito de outro vendedor em resposta do vendedor; o limite da semana e o valor de aprovação continuam fora do bloco `parametros`.

## Dúvidas (com o padrão que sigo se não houver resposta)

| Nº | Pergunta | Padrão |
| --- | --- | --- |
| T1 | O valor de A vira `REEMBOLSO` forma `CREDITO` na venda A e `PAGAMENTO` forma `CREDITO` na venda B (um caminho só para o dinheiro, a C1 fechando por venda)? | **Sim** |
| T2 | A troca fica fora do limite da semana e do valor de aprovação (nenhum dinheiro sai), mas passa pelo prazo da garantia e pela autorização? E a taxa de devolução continua contando a troca como devolução + venda? | **Sim** e **sim** (nada muda na 6.4) |
| T3 | O resto de B pode ser pago à vista, a prazo (pago menor que o total) ou com outro crédito do mesmo cliente e do mesmo vendedor? | **Sim** (o mesmo caminho da venda comum) |
| T4 | Uma troca devolve peças de **uma** venda só (várias peças A e várias B, mas uma venda de origem)? | **Sim** (`trocas` tem uma venda de origem) |
| T5 | B sai da maleta conferida do vendedor, que pode ser outra que não a de A? Ordem das travas: vendedor → maletas por `id` → vendas por `id` → devolução, troca e autorização → créditos por `id`? | **Sim** e **sim** |
| T6 | Quem usa o crédito: só o vendedor dono dele (quem gerou, ou para quem a dona transferiu), mesmo com internet, e só na venda do mesmo cliente? Acima do saldo, 409 nesta tarefa (a revisão pelo envio é da 6.7)? | **Sim** e **sim** |
| T7 | "A dona transfere": o saldo inteiro vai para um crédito novo do outro vendedor (registro novo nos dois lados)? "A dona usa": como ainda não há venda pela dona, fica para a troca e a devolução pela dona (6.9)? | **Sim** e **sim** |
| T8 | Estorno de venda paga com crédito devolve o crédito (o uso é estornado)? A venda e a devolução de uma troca só se desfazem pelo "Cancelar troca" (409 nas rotas de estorno e de cancelar devolução)? | **Sim** e **sim** |
| T9 | Na devolução de venda paga (em parte) com crédito, o que foi pago em crédito volta **primeiro** como crédito novo (origem `DEVOLUCAO`) e só o resto em dinheiro? | **Sim** (nenhum dinheiro sai além do que entrou em dinheiro; coerente com D4) |
| T10 | "Cancelar troca": vendedor no dia da troca, depois só a dona; recusado se a sobra já foi usada, se A já foi vendida de novo ou se uma das maletas não está mais com o vendedor? | **Sim** |
| T11 | `trocas` guarda a venda nova e a venda guarda `troca_id` (as duas, conferidas pelo banco no fim da transação)? | **Sim** (como a §9 descreve) |
| T12 | Pacotes novos `api/trocas` e `api/creditos`, acrescentados ao filtro `sync` do `ci.yml` e a `docs/ci.md` (mexer no workflow também liga o job `dependencias` nesta rodada)? | **Sim** |
| T13 | Situações: troca `ATIVA` ou `CANCELADA`; crédito `ABERTO`, `UTILIZADO` ou `CANCELADO` (na tela: Em aberto, Usado, Cancelado)? | **Sim** |
| T14 | Os ids da troca, da devolução, da venda nova, do crédito e do pagamento em crédito vêm do app (o computador precisa deles sem internet); os do reembolso em crédito, dos usos e dos itens nascem no servidor (como na 6.3)? | **Sim** |

## Custo de CI

Uma rodada no fim (o rascunho não gasta): `backend` (~5 min, dois arquivos de teste grandes a mais), `sync-duas-pontas` (~4 min, pela migração e pelos pacotes `vendas` e `devolucoes`), `frontend` (~3 min, pelos clientes regerados e pelo workflow), `dependencias` (~2 min, porque o `ci.yml` muda), `migracoes`, `mudancas` e `ci` (~1 min cada). Cerca de **17 minutos de execução (por volta de 21 cobrados)**; o dobro se uma correção pedir outra rodada.
