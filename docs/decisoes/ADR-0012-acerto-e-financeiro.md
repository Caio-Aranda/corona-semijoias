# ADR-0012: Acerto semanal e financeiro

- **Status:** Aceito em 13/11/2026, pelo responsável, com quatro mudanças (ver "Mudanças do aceite", logo abaixo). As dúvidas para a dona ainda sem resposta valem com os padrões seguros descritos em [fase-7-plano.md](../progresso/fase-7-plano.md)
- **Data:** 11/11/2026 (proposto); 13/11/2026 (aceito)
- **Tarefas que implementam:** 7.2 a 7.13 (Fase 7); as conciliações automáticas diárias, o DRE e o lucro por produto e por vendedor ficam para a Fase 8

## Mudanças do aceite (13/11/2026)

O responsável aceitou este ADR em 13/11/2026 com as mudanças abaixo, já aplicadas no texto (tarefa 7.1b). Valem antes de qualquer código da Fase 7.

1. **Não existe "entrada".** O vendedor não adianta dinheiro ao levar a maleta. Saíram o total `entrada_abatida`, o momento `ENTRADA` do recebimento e o abatimento no fechamento; `a_pagar_no_acerto = liquido`. Se um dia existir, entra como tarefa nova.
2. **Não existe cheque.** Saíram as tabelas `cheques` e `eventos_cheque`, o lançamento de caixa `CHEQUE_COMPENSADO`, a conta a receber de cheque devolvido e a conciliação de cheque (C6). As formas do repasse do vendedor à loja são `DINHEIRO`, `PIX` (na tela, "Transferência") e `BOLETO`; o pagamento a fornecedor usa as mesmas três. Os valores `CHEQUE` e `CARTAO` que já existem no banco para o pagamento do cliente continuam aceitos (tarefa 6.13), mas não aparecem em tela nova.
3. **Comissão por meta** (resposta da dona em 13/11/2026), no lugar do percentual por vendedor: 40% se o vendido da semana chegar a 700.000 Gs, 30% abaixo disso, sobre o total (item 5).
4. **Dúvidas respondidas pela dona** (2, 4, 7, 11, 14 e 20 do plano): registradas em [fase-7-plano.md](../progresso/fase-7-plano.md#dúvidas-para-a-dona).

Os itens 9 e 10 e a conciliação C6 mantêm o número, para as referências dos outros documentos continuarem valendo.

## Contexto

_O contexto e a tabela de furos abaixo descrevem a especificação como estava em 11/11/2026; onde o aceite mudou a solução, a linha diz._

A §7.4 define o acerto semanal; a §7.5, o financeiro; a §9 nomeia as tabelas (`acertos`, `pendencias_acerto`, `contas_receber`, `contas_pagar`, `lancamentos_caixa`, `comissoes`, `cheques`); a §12 lista os relatórios financeiros; a §13 pede conciliações; a §17 deixa pendentes a base da comissão (6), a entrada (12) e o cartão (13); no aceite de 13/11/2026, a entrada e os cheques saíram do sistema novo. Já estão decididos:

- moedas e cotação (ADR-0006): tudo gravado em Real; o acerto grava a cotação que usou (`cotacao_id`);
- estoque (ADR-0008): movimento imutável, saldo nunca negativo, travas em ordem estável por `id`;
- maleta (ADR-0010): estados `RASCUNHO → ENVIADA → CONFERIDA → EM_ACERTO → ACERTADA`, devolução à dona por `DEVOLUCAO_DONA` + `DEVOLUCAO`;
- venda (ADR-0011 e tarefa 4.2, migração V022): `vendas.total = subtotal − desconto`, estorno da venda inteira, `pagamentos_cliente` com `PAGAMENTO`/`REEMBOLSO` e formas `DINHEIRO`, `PIX`, `CARTAO`, `CHEQUE` (desde a tarefa 6.13, as telas do cliente só mostram Dinheiro e Transferência, gravada como `PIX`; `CARTAO` e `CHEQUE` continuam aceitos no banco);
- dinheiro (`Dinheiro`, ADR-0002): HALF_UP só ali; `aplicarPorcentagem`, `comissaoPorVenda`, `comissaoSobreTotal`, `dividirEmParcelas`, `estornosProporcionais`; conversão só em `ConversaoDeMoeda`.

**Decisões do responsável em 11/11/2026** (entram neste ADR e na tarefa 7.2):

1. O limite de consignação conta o **preço de venda congelado** (`preco_remessa`), não o custo.
2. A **reposição entra** no limite.
3. O limite é **campo obrigatório** no cadastro do vendedor, com a opção explícita **"Sem limite"** (nunca vazio por esquecimento).
4. **Saída sem estoque é sempre recusada**, no estoque central e na maleta, inclusive dentro do acerto. Não existe saldo negativo temporário.

Isto fecha as dúvidas 1, 2 e 6 do ADR-0010.

Este ADR fixa o modelo **antes da primeira migração** da Fase 7, porque migração publicada não se edita e porque dinheiro errado não se conserta depois sem estorno.

## Furos e contradições encontrados na especificação

Cada um tem a solução adotada aqui. Nenhum muda a especificação sozinho: a tarefa 7.2 atualiza a especificação onde for preciso (CLAUDE.md: "se uma regra mudar, atualize o documento primeiro").

| # | Onde | Problema | Solução adotada |
| --- | --- | --- | --- |
| F1 | §9 `acertos (… remessa …)` | O acerto tem **uma** remessa, mas a reposição cria outra remessa na mesma semana | O acerto cobre **todas** as maletas abertas do vendedor; `remessas.acerto_id` liga cada maleta a um acerto só (item 2) |
| F2 | §7.4.5, §17.12, aba Totais | "Entrada" aparece como **forma** de pagamento do repasse, mas a aba Totais a põe em "Novos Pedidos" e "desconto de entrada" no Acerto | **Aceite de 13/11/2026: no sistema novo não existe entrada** (item 9). A aba Totais da referência fica só como registro do sistema antigo |
| F3 | §13 "soma das vendas = contas a receber geradas" | Ignora venda à vista, estorno e devolução | Reescrita como C1 (item 17): total − devolvido = pago líquido + em aberto |
| F4 | §13 "total do acerto = vendas − descontos − reembolsos − comissão" | Esquece as faltas cobradas; "reembolsos" contaria duas vezes se somado à devolução | Reescrita como C2 (item 17). O acerto conta a **devolução** (valor das peças devolvidas), **nunca** o reembolso em dinheiro em separado |
| F5 | §4 "vendedor inicia o acerto" × "financeiro só da dona" | Quem faz o quê no acerto | O vendedor só **pede** o acerto e pode bipar a contagem; tudo que é dinheiro (fechamento, recebimentos, comissão, contas, caixa) é **só ADMIN** (item 16) |
| F6 | §9 `comissoes (vendedor, venda, acerto …)` × §6.8 "sobre o vendido no acerto" | A tabela sugere comissão **por venda**; a premissa admite **sobre o total** | Por venda (caso (a) do ADR-0002), porque é a única que permite estorno proporcional de venda de semana já fechada (item 5). **Aceite de 13/11/2026:** continua por venda, com o percentual decidido pela meta da semana |
| F7 | §7.4.7 "semana fechada não aceita alteração" × §7.3 devolução dentro da garantia | Devolução de venda de semana fechada mexeria num acerto fechado | Nada reabre: o efeito (valor e estorno de comissão) entra no **próximo** acerto (item 3) |
| F8 | §7.4.1 "sincroniza antes" × offline (Fase 5) | Venda feita sem internet pode chegar depois do início do acerto ou depois do fechamento | Regra de corte (item 4): antes do fechamento ela entra; depois, vira pendência para a dona, nunca some nem entra calada |
| F9 | §9 `contas_receber (… status, data_pagamento)` | Status editável contraria "pagamento é evento" e a imutabilidade | Parcela imutável + **baixas** imutáveis; `valor_pago`, `valor_cancelado` e `status` são atalho mantido só pelo serviço, como `estoque_atual` (item 7) |
| F10 | §7.5 cheques | Não diz se o cheque é só recebido ou também emitido, nem o que acontece quando volta | **Aceite de 13/11/2026: no sistema novo não existe cheque** (item 10); nenhuma tabela de cheque é criada |
| F11 | §9 `pendencias_acerto (… valor …)` | Não diz se a peça que falta vale preço de venda ou custo | `preco_remessa` (coerente com a decisão 1 do limite); a dona escolhe cobrar ou assumir a perda (item 6) |
| F12 | §7.4.6 "peças com defeito aprovadas pela dona" | Não diz o movimento de estoque | Aprovada: `DEVOLUCAO` + `AVARIA` na mesma transação (não volta a ser vendável); recusada: volta como boa (item 6) |
| F13 | §15 (ordem das fases) | A Fase 7 lê devoluções, trocas e créditos, que são da Fase 6 | As tarefas que leem esses registros dependem da Fase 6; `origem_tipo` é texto com `CHECK` ampliável por migração nova (plano, "Ordem") |
| F14 | §7.5 × V022 | `pagamentos_cliente` já aceita `CARTAO`, mas a §17.13 ainda pergunta; o repasse da §7.4.5 não tem cartão | **Respondido em 13/11/2026:** o cliente não paga com cartão e o acerto não aceita cartão. `CARTAO` continua aceito no banco para o pagamento do cliente (6.13), sem aparecer em tela nova |
| F15 | §7.5 | Não há multa nem juros | Nenhum cálculo de multa ou juros; atraso só aparece em inadimplência (dúvida para a dona) |

## Decisão

### 1. Quem deve o quê (modelo do repasse)

- **O vendedor deve à dona o valor do que vendeu**, recebido ou não do cliente: o fiado é do vendedor (§7.2: "o vendedor controla as contas a receber dos próprios clientes"; §13: "total do acerto = vendas − …"). Padrão seguro até a dona responder (dúvida 1).
- Por isso existem **dois níveis de contas a receber** (§7.5), que nunca se misturam:
  - **nível 1, cliente → vendedor:** parcelas da venda a prazo; é do vendedor, a dona só vê nos relatórios;
  - **nível 2, vendedor → dona:** o que sobrou sem pagar no acerto (inclusive o boleto ainda não pago) e as faltas cobradas depois.
- **Crédito do cliente** (Fase 6) usado numa compra nova é forma de pagamento do nível 1 e **não reduz** o que o vendedor deve: quem reduz é a devolução que gerou o crédito. Assim nada conta duas vezes.

### 2. Modelo de dados

Migração nova, a próxima livre na hora de cada tarefa (em 13/11/2026 a última é a V040). Todas as tabelas com os campos de controle do ADR-0002; dinheiro em `NUMERIC(14,2)`; porcentagem em `NUMERIC(5,2)`; instantes em `timestamptz` UTC; datas de calendário (vencimento) em `date`, contadas em horário de Brasília. Toda chave `id` é UUID **vindo do cliente** quando o registro nasce de um comando (acerto, leitura, recebimento, baixa, lançamento, conta a pagar); v7 gerado no servidor quando nasce como efeito (comissão, parcela, item do acerto, pendência, lançamento automático de caixa).

| Tabela | Colunas principais | Restrições | Imutável? | Vai ao vendedor? |
| --- | --- | --- | --- | --- |
| `acertos` | vendedor, semana, status `EM_ACERTO`/`ACERTADO`/`DESISTIDO`, iniciado_em/por, fechado_em/por, `cotacao_id`, totais: `vendido_bruto`, `descontos`, `devolvido`, `vendido_liquido`, `comissao`, `percentual_comissao` (30 ou 40, o que a meta decidiu), `vendido_para_meta_guarani`, `faltas_cobradas`, `liquido`, `recebido_no_acerto`, `ficou_devendo` | índice único parcial: um `EM_ACERTO` por vendedor; único (vendedor, semana) fora `DESISTIDO`; `CHECK` das contas do item 8; gatilho de transições | Depois de `ACERTADO`, totalmente | Sim, só os dele |
| `remessas.acerto_id` (coluna nova) | liga a maleta ao acerto | FK; gatilho: só muda em `EM_ACERTO` (liga ou desliga ao desistir), congelada em `ACERTADA` | — | Sim (já sincronizável) |
| `leituras_acerto` | acerto, produto, quantidade (±1 ou ±n, desfazer é negativo), `estado_peca` `BOA`/`DEFEITO`, quem leu (vendedor ou dona), dispositivo | só enquanto o acerto está `EM_ACERTO` | Sim (desfazer = leitura negativa) | Sim, só as dele |
| `itens_acerto` | acerto, remessa, produto, `preco_remessa`, esperado e contado (boa e defeito), diferença | gravados no fechamento | Sim | Sim |
| `acerto_eventos` | acerto, `origem_tipo` (`VENDA`, `ESTORNO_VENDA`, e na Fase 6 `DEVOLUCAO`, `TROCA`), `origem_id`, `valor` com sinal | **único (`origem_tipo`, `origem_id`)**: um evento entra em um acerto só | Sim | Sim |
| `comissoes` | vendedor, venda, acerto, `tipo` `COMISSAO`/`ESTORNO`, `base`, `percentual`, `valor` com sinal, `estorna_id`, origem do estorno | único (venda) para `COMISSAO`; sinal pelo tipo; soma dos estornos de uma venda ≤ comissão dela | Sim | Sim, só as dele |
| `pendencias_acerto` | acerto, produto, `tipo` `FALTA`/`SOBRA`/`DEFEITO`, quantidade, valor (`quantidade × preco_remessa`), status `ABERTA`/`COBRADA`/`ASSUMIDA_PELA_LOJA`/`ENCONTRADA`/`APROVADA`/`RECUSADA`, resolvida_por/em, motivo | transições por gatilho; resolver exige motivo | Só o status muda, com auditoria | Sim (sem custo) |
| `contas_receber` | `devedor_tipo` `CLIENTE`/`VENDEDOR`, cliente ou vendedor (exatamente um), `origem_tipo` (`VENDA`, `ACERTO`, `PENDENCIA`), `origem_id`, parcela n de N, vencimento, `valor`, atalhos `valor_pago`, `valor_cancelado`, `status` | `valor > 0`; `valor_pago + valor_cancelado ≤ valor`; só o serviço mexe nos atalhos | O conteúdo sim; os atalhos só pelo serviço | Sim, só as dele (nos dois níveis) |
| `baixas_receber` | conta, `tipo` `PAGAMENTO`/`CANCELAMENTO`, valor, data, `pagamento_cliente_id` ou `recebimento_id`, `estorna_id` | estorno único por baixa | Sim | Sim, só as dele |
| `recebimentos_vendedor` | vendedor, `momento` `ACERTO`/`AVULSO`, acerto (quando houver), `forma` `DINHEIRO`/`PIX`/`BOLETO`, valor, data, `estorna_id` | `valor > 0` (estorno negativo com `estorna_id` único) | Sim | Sim, só os dele |
| `contas_pagar` | fornecedor, compra, parcela n de N, vencimento, `valor`, atalhos `valor_pago`, `valor_cancelado`, `status` | como `contas_receber` | Como `contas_receber` | Não |
| `baixas_pagar` | conta, `tipo`, valor, forma, data, `lancamento_caixa_id`, `estorna_id` | — | Sim | Não |
| `lancamentos_caixa` | `tipo` `ENTRADA`/`SAIDA`, `valor` com sinal pelo tipo, forma, categoria, `origem_tipo` (`RECEBIMENTO_VENDEDOR`, `PAGAMENTO_FORNECEDOR`, `ACERTO_A_FAVOR_DO_VENDEDOR`, `MANUAL`), `origem_id`, `data_lancamento` (date), descrição, `estorna_id` | sinal por `CHECK`; estorno único; origem automática única por (`origem_tipo`, `origem_id`) | Sim | Não |
| `categorias_caixa` | nome, tipo (entrada/saída), ativo | — | Cadastro comum | Não |

Notas:

- `acertos` usa `ACERTADO` (o acerto); a maleta continua `ACERTADA` (ADR-0010). `DESISTIDO` é o acerto iniciado e abandonado antes de fechar (item 3).
- Cada tabela imutável entra no teste de convenções como imutável (recusa `UPDATE` de conteúdo e `DELETE`, como `movimentacoes_estoque`).
- `contas_receber`, `comissoes`, `acertos` e `pendencias_acerto` já estão no ADR-0002 como sincronizáveis só de leitura, filtradas pelo vendedor. `leituras_acerto`, `itens_acerto`, `acerto_eventos`, `baixas_receber` e `recebimentos_vendedor` entram na mesma classe. Caixa, contas a pagar e categorias **nunca** saem do servidor. Nenhuma tabela da Fase 7 tem custo; lucro só existe em relatório da dona (Fase 8).
- **Não se grava valor convertido** em Dólar ou Guarani como valor de lançamento (ADR-0006). Só `acertos.cotacao_id`. A única exceção é `acertos.vendido_para_meta_guarani`: não é dinheiro lançado, é a soma que decidiu o percentual da comissão (item 5), guardada para a dona e o vendedor conferirem; nenhuma conciliação em Real a usa.
- **Não existem** as tabelas `cheques` e `eventos_cheque` nem a coluna `entrada_abatida` (aceite de 13/11/2026).

### 3. Ciclo do acerto

```
(maletas ENVIADA/CONFERIDA) ──pedir/iniciar──► acerto EM_ACERTO + maletas EM_ACERTO
      ▲                                              │  leituras (contagem), conferência da dona
      └──────────── desistir (nada gravado) ─────────┤
                                                     ▼
                                    fechar (uma transação) ──► acerto ACERTADO + maletas ACERTADA
```

1. **Iniciar** (vendedor pelo app dele, online, ou a dona). Pré-condições: online; no app do vendedor, nada aguardando envio (Fase 5); nenhum acerto `EM_ACERTO` do vendedor; semana `ABERTA`. Efeito, numa transação: cria o acerto, liga **todas** as maletas `ENVIADA`/`CONFERIDA` do vendedor (`acerto_id`) e as passa a `EM_ACERTO`. Sem maleta aberta, o acerto pode existir só para eventos atrasados e pendências (por exemplo, devolução de venda antiga).
2. **Com a maleta em `EM_ACERTO`:** nenhuma venda nova, devolução, troca, perda, ajuste ou reposição nessa maleta, e nenhuma remessa nova para o vendedor ("Termine o acerto antes"). A exceção é o item 4 (venda feita antes, sem internet).
3. **Contagem:** cada bipe é uma `leitura_acerto` (UUID do cliente, idempotente). Bipar errado se desfaz com uma leitura negativa. O vendedor pode bipar no app dele; a dona confere e pode bipar no dela. **Vale a soma de todas as leituras** no momento do fechamento, e a tela da dona mostra quem bipou o quê.
4. **Esperado × contado**, por produto e estado da peça: esperado = saldo da maleta pelos movimentos (fórmula da §7.1) somado entre as maletas do acerto; peças `DEFEITO` separadas (Fase 6) são esperadas à parte. O servidor calcula o esperado **no fechamento**, não no início, para enxergar vendas atrasadas do item 4.
5. **Desistir:** só em `EM_ACERTO`. O acerto vira `DESISTIDO`, as maletas voltam ao estado anterior e o `acerto_id` é desligado. As leituras ficam como histórico. Nada de dinheiro foi gravado, porque tudo de dinheiro nasce no fechamento.
6. **Fechar** (só a dona): **uma transação só, tudo ou nada**, com o UUID do fechamento como chave de idempotência. Ordem dentro dela:
   1. trava (item 13) e confere que o acerto está `EM_ACERTO`;
   2. grava `itens_acerto` com esperado e contado;
   3. **sobra** (contado > esperado) impede o fechamento: "Sobrou peça: confira as vendas ou registre o ajuste". A dona resolve antes (estorno da venda errada ou `AJUSTE` positivo na maleta, com motivo);
   4. **falta** vira `pendencia_acerto` `FALTA` e um movimento `PERDA` na maleta, para o saldo chegar a zero. A dona escolhe, por pendência, **cobrar no acerto** (entra em `faltas_cobradas`), **cobrar depois** (fica `ABERTA` e, quando resolvida, vira conta a receber do vendedor, sem tocar no acerto fechado) ou **a loja assume** (perda da loja, sem cobrança);
   5. peças boas contadas: `DEVOLUCAO_DONA` na maleta e `DEVOLUCAO` no estoque central, pelo serviço do ADR-0008 (vários produtos, ordem por `id`);
   6. peças com defeito: a dona **aprova** (`DEVOLUCAO_DONA` na maleta, `DEVOLUCAO` + `AVARIA` no estoque central, saldo vendável não aumenta) ou **recusa** (a peça estava boa: volta como boa). Defeito não é cobrado do vendedor (dúvida 19);
   7. liga ao acerto, em `acerto_eventos`, **todos os eventos do vendedor ainda sem acerto** registrados no servidor até este instante (vendas, estornos e, na Fase 6, devoluções e trocas), de qualquer maleta ou semana;
   8. calcula as comissões (item 5) e os totais (item 8) com `Dinheiro`;
   9. grava os **recebimentos** do acerto (item 9) e os **lançamentos de caixa** (item 11);
   10. o que faltou pagar vira **conta a receber do vendedor** (nível 2), com o vencimento escolhido pela dona; o que a dona ficou devendo segue o item 8;
   11. grava `cotacao_id` (a cotação mais recente no instante do fechamento, ADR-0006 item 11), passa o acerto a `ACERTADO` e as maletas a `ACERTADA` (saldo zero em todas, conferido);
   12. auditoria.
7. **Depois de `ACERTADO`, nada muda** no acerto, nos itens, nos eventos ligados nem nas comissões dele. Correção é registro novo no próximo acerto ou no nível 2.
8. **Semana** (de segunda a domingo, um acerto por vendedor por semana; resposta da dona em 13/11/2026): a dona fecha a semana (`semanas.situacao = FECHADA`) quando quiser, depois dos acertos; acerto em `EM_ACERTO` impede fechar a semana. Semana fechada não aceita acerto novo, remessa nova nem lançamento financeiro com data dentro dela: o lançamento leva a data de hoje ("Essa semana já foi fechada. Lance com a data de hoje"). Nada reabre semana.

**Como ficou (tarefa 7.4, 14/11/2026).** Iniciar, contar e desistir estão prontos (V043; o acerto não tem dinheiro). O iniciar liga todas as maletas `ENVIADA`/`CONFERIDA` do vendedor, guardando o status de antes em `remessas.status_antes_do_acerto`; o desistir volta a ele. Um acerto `EM_ACERTO` por vendedor e um por semana fora `DESISTIDO` (índices únicos parciais). A semana é a informada, a mais recente das maletas ligadas ou a de hoje. O vendedor pede o próprio acerto com os pré-requisitos do item 1 (`aguardando_envio` é o **último número informado** pelo computador: a garantia de fila vazia é do aplicativo, 7.11); só a dona desiste, com motivo e o UUID do comando. A leitura é uma por pedido, online; aceita peça das maletas do acerto ou de devolução "para a loja" do vendedor; a contagem pode passar do que a maleta tem (a sobra aparece no fechamento, 7.5). Nada de esperado × contado nesta tarefa. Gatilhos que a 7.5 recria: `proteger_acerto` (para `ACERTADO`) e `conferir_acerto_da_remessa` (para `ACERTADA`, mantendo o `acerto_id`); o `CHECK` `acertos_desistencia_completa` também.

### 4. Regra de corte (vendas e eventos que chegam atrasados)

- Evento **novo** numa maleta `EM_ACERTO` (venda, devolução, troca) é recusado: 409, "Esta maleta está em acerto".
- **Exceção da Fase 5:** venda feita **sem internet** com `vendida_em` **anterior** ao início do acerto e vinda do aparelho da maleta é aceita enquanto o acerto está `EM_ACERTO`; como o esperado é calculado no fechamento, a contagem já bate. A tela da dona avisa "Chegou venda nova: confira a contagem".
- Depois do fechamento, venda atrasada **nunca entra calada**: vira pendência "venda depois do acerto" para a dona (conflito da Fase 5) e, se aceita, entra no **próximo** acerto, normalmente ligada à falta que ela explica (a pendência de falta passa a `ENCONTRADA`).
- Devolução, troca ou estorno de venda de semana fechada (Fase 6) entra no próximo acerto do vendedor (item 3, passo 6.7) e a peça boa entra na maleta aberta dele (regra a confirmar na Fase 6).

**Como ficou (tarefa 7.4, 14/11/2026).** A exceção da Fase 5 vale para a **venda e para o estorno de venda** vindos do envio, do computador da maleta (`remessas.dispositivo_id`), com a hora corrigida pelo servidor **anterior** a `acertos.iniciado_em`; o banco é a segunda trava (a venda só entra com `vendida_em` anterior ao início). A venda online grava `vendida_em` com o relógio da aplicação (o mesmo do início), para a regra nunca depender do relógio do banco. Devolução, troca, devolução à dona e cancelamento continuam recusados na maleta em acerto (`MALETA_EM_ACERTO`). **A dona pode estornar** venda de maleta em acerto (a peça volta à maleta e entra no esperado do fechamento); o vendedor, só pela exceção. A ficha da dona mostra `vendasQueChegaramDepoisDoInicio` (vendas que o servidor recebeu depois do início). Remessa nova: o rascunho é livre, o envio é recusado (`VENDEDOR_EM_ACERTO`).

### 5. Comissão

**Comissão por meta** (resposta da dona em 13/11/2026; substitui o percentual por vendedor da versão proposta).

- **Meta da semana.** No fechamento do acerto, o servidor soma o **vendido líquido** (`vendas.total`, depois do desconto) das vendas ligadas ao acerto, **convertendo cada venda para Guarani pela cotação gravada na própria venda** (`vendas.cotacao_id`, ADR-0006), cada uma arredondada sozinha por `ConversaoDeMoeda.paraGuarani` (Guarani sem centavos). A soma é a dos valores já convertidos, nunca a conversão do total.
  - Venda sem cotação gravada usa a cotação que valia no dia dela, pela regra do ADR-0006.
  - Venda estornada, ou a parte devolvida de uma venda, **quando o estorno ou a devolução está ligado ao mesmo acerto**, sai da soma, convertida pela mesma cotação da venda. Estorno e devolução de venda de acerto já fechado **não** entram na soma da meta: só geram o estorno de comissão abaixo.
- **Percentual do acerto.** Soma **≥ 700.000 Gs → 40%**; abaixo → **30%**. O percentual vale **sobre o total** vendido no acerto, não só sobre o que passa da meta. É **por semana**: um acerto por vendedor por semana.
- **Onde ficam os números.** A meta (700.000 Gs) e os dois percentuais (30 e 40) ficam em `configuracoes`, três chaves novas criadas na migração da tarefa 7.5; a dona pode mudar, e valem **iguais para todos os vendedores**. O fechamento usa os valores do instante em que acontece e grava no acerto o percentual decidido e a soma em Guarani. Mudar a meta ou os percentuais depois não muda nada gravado.
- **`vendedores.comissao_percentual` deixa de ser usado no cálculo.** A coluna continua no banco (não se apaga); o campo sai do cadastro do vendedor na tela quando a tarefa da tela chegar.
- **Por venda** (caso (a) do ADR-0002, `Dinheiro.comissaoPorVenda`): com o percentual decidido, para cada venda ligada ao acerto, `comissao = Dinheiro.aplicarPorcentagem(venda.total, percentual)`, HALF_UP com 2 casas, arredondada **uma vez por venda**, e o percentual fica **gravado na linha** (`percentual`). A comissão do acerto é a **soma** das comissões já arredondadas.
- **Base:** `vendas.total` (valor da venda já com o desconto), recebido ou não do cliente, coerente com o item 1 (resposta da dona em 13/11/2026).
- **Estorno:** venda estornada ou devolvida gera linha `ESTORNO`, negativa, com `estorna_id` na comissão original, calculada por `Dinheiro.estornosProporcionais` **pelo acumulado** e com o **percentual da linha original**. Venda e estorno no mesmo acerto dão comissão líquida zero, com as duas linhas visíveis. Estorno de venda de acerto já fechado entra no **próximo** acerto (reduz a comissão dele) e usa o percentual **da linha original**, não o do acerto novo.
- **Sempre o servidor calcula.** O app nunca manda comissão, base, percentual nem a soma da meta; se mandar, é ignorado. O app do vendedor mostra a **prévia** que o servidor devolve: o vendido da semana, o percentual que valeria hoje e **quanto falta para a meta, em Guarani e em Real**.
- **Paga e a pagar:** a comissão é paga **por abatimento** no fechamento (o vendedor fica com ela; §7.4.4 "valor líquido"): toda comissão gravada pertence a um acerto `ACERTADO` e conta como **paga** na data do fechamento. **A pagar** é a prévia sobre os eventos ainda sem acerto, calculada na hora com a meta e os percentuais atuais e nunca gravada (dúvida 3). Na prévia, o que falta para a meta é calculado em Guarani e mostrado também em Real pela cotação mais recente (só para mostrar).

### 6. Pendências

- `FALTA`: valor = quantidade × `preco_remessa` da maleta de onde a peça saiu (com mais de uma maleta, a mais antiga primeiro, ordem estável). Status: `ABERTA` → `COBRADA` (no acerto ou, depois, gerando conta a receber do vendedor), `ASSUMIDA_PELA_LOJA` ou `ENCONTRADA` (a peça voltou: `DEVOLUCAO` no estoque central com origem na pendência; se já tinha sido cobrada, a conta a receber é cancelada por baixa `CANCELAMENTO` ou, se já paga, vira crédito no próximo acerto).
- `DEFEITO`: `APROVADA` ou `RECUSADA` (item 3, passo 6.6), decidida no fechamento.
- `SOBRA`: nunca fica aberta; impede o fechamento (item 3, passo 6.3).
- Resolver pendência é só da dona, exige motivo e vai para a auditoria.

### 7. Contas a receber, parcelas e baixas

- **Nível 1 (venda a prazo).** O contrato da venda (ADR-0011) ganha campos **opcionais** e compatíveis: número de parcelas e vencimento da primeira; intervalo mensal (dúvida 17). O servidor gera as parcelas sobre `total − pago na hora` com `Dinheiro.dividirEmParcelas` (as n−1 primeiras iguais, a última fecha exato), na mesma transação da venda, com o UUID da venda como origem. Venda a prazo já gravada antes da migração recebe uma parcela única com vencimento na data da venda (só existe em teste; não há produção).
- **Recebimento posterior** do cliente é um `pagamento_cliente` novo (já previsto na §7.2). O servidor o **distribui** nas parcelas abertas daquela venda **por ordem de vencimento** (a mais antiga primeiro; empate pela parcela), gerando `baixas_receber`. Pagamento acima do saldo em aberto é recusado.
- **Devolução e estorno** (§7.3): cancelam as parcelas em aberto, da última para a primeira, por baixa `CANCELAMENTO`; o que já foi pago é reembolsado (registro negativo, Fase 6). Estorno da venda inteira cancela todas.
- **Nível 2 (vendedor).** Nasce no fechamento (o que faltou pagar, inclusive o boleto ainda não pago) e na resolução de falta cobrada depois. A dona escolhe o vencimento e pode dividir em parcelas (`dividirEmParcelas`). Pagamento é um `recebimento_vendedor` `AVULSO` ou `ACERTO` (o próximo acerto mostra as contas abertas e aceita pagá-las, aba "pendência paga").
- **Atalhos.** `valor_pago`, `valor_cancelado` e `status` (`ABERTA`, `PARCIAL`, `PAGA`, `CANCELADA`; **`VENCIDA` é calculado** pela data de hoje em Brasília, nunca gravado) só mudam pelo serviço, na mesma transação da baixa, com a linha travada. Teste de arquitetura e a conciliação C5 conferem.
- **Sem multa e sem juros** (§7.5 não manda; dúvida 10).

**Como ficou (tarefa 7.3, 14/11/2026).** Nível 1 pronto: tabelas `contas_receber` e `baixas_receber` (V041), parcelas de 1 a 24, mensais, sem valor mínimo; sem parcelamento informado, uma parcela um mês depois da venda. Recebimento posterior só em `DINHEIRO` e `PIX`, registrado pelo vendedor, com `pagamentos_cliente.posterior`; o estorno dele é `ESTORNO_PAGAMENTO` (vendedor no mesmo dia, dona sempre). O estorno da venda cancela só o que está em aberto (parcela paga continua paga; o dinheiro volta pelo reembolso). A devolução de venda a prazo ficou para a tarefa 6.6 (a 7.3b).

**Como ficou (tarefa 6.6, 14/11/2026).** A devolução e a troca de venda a prazo estão prontas (V042): a devolução abate primeiro o que o cliente deve (baixa `CANCELAMENTO` com motivo `DEVOLUCAO`, da última parcela para a primeira), depois volta crédito e por último dinheiro; `devolucoes.valor_abatido`; cancelar devolução estorna o abatimento; `PARCIAL` também vale para parcela abatida. Ordem de travas: vendedor, maleta, venda, devolução, créditos, parcelas por último. O Corona Vendas e o Corona Gestão deixam de bloquear a venda a prazo. A 7.5 deve saber que, na venda a prazo, `valor_reembolsado` não é mais o devolvido: o acerto usa `valor_devolvido`. Ordem de travas: estorno da venda remessa, venda, créditos, parcelas; recebimento venda, parcelas.

### 8. Totais do acerto (todos em Real, só somas de valores já arredondados)

```
vendido_bruto     = Σ subtotal das vendas ligadas
descontos         = Σ desconto dessas vendas
devolvido         = Σ |valor| dos estornos, devoluções e diferenças de troca a favor do cliente ligados
vendido_liquido   = vendido_bruto − descontos − devolvido      (= Σ valor de acerto_eventos)
comissao          = Σ comissoes do acerto (COMISSAO − ESTORNO)
liquido           = vendido_liquido − comissao + faltas_cobradas
a_pagar_no_acerto = liquido                                    (não existe entrada)
a_pagar_no_acerto = recebido_no_acerto + ficou_devendo         (CHECK)
```

- Pagamento de conta do nível 2 feito no acerto **não** entra nestes totais: é registrado à parte, como baixa da conta antiga.
- **Acerto a favor do vendedor** (`a_pagar_no_acerto < 0`, por exemplo muitas devoluções): o padrão é virar **crédito do vendedor**, abatido no próximo acerto; se a dona preferir pagar na hora, grava-se uma saída de caixa `ACERTO_A_FAVOR_DO_VENDEDOR` (dúvida 16). Nunca se esconde o sinal.
- **Não existe entrada** (aceite de 13/11/2026): nada é abatido do líquido. `a_pagar_no_acerto` não é coluna; é o próprio `liquido`.
- Não existe "desconto do acerto" livre: correção é estorno ou pendência (dúvida 18).
- Tela e PDF do acerto mostram Dólar e Guarani pela cotação gravada (ADR-0006 item 11), cada valor arredondado sozinho.

### 9. Recebimentos do vendedor

- **Não existe entrada** (aceite de 13/11/2026). O vendedor não adianta dinheiro ao levar a maleta: não há recebimento com momento `ENTRADA`, nem abatimento no fechamento. Se um dia existir, entra como tarefa nova, com migração nova.
- **Formas do repasse do vendedor à loja:** `DINHEIRO`, `PIX` (na tela, **"Transferência"**) e `BOLETO`. Não há cheque nem cartão (dúvidas 11 e 14, respondidas). Nenhuma é executada pelo sistema (§7.5).
- **Boleto** não é dinheiro na mão: no fechamento, o valor em boleto vira conta a receber do vendedor com o vencimento do boleto; quando a dona registra que foi pago, nasce o `recebimento_vendedor` `BOLETO` e o lançamento de caixa (dúvida 12).
- Estorno de recebimento (registrado errado) é um registro negativo com `estorna_id`, só da dona, com motivo; num acerto fechado, o estorno não muda o acerto: gera conta a receber do vendedor.

### 10. Cheques (retirado no aceite de 13/11/2026)

**No sistema novo não existe cheque**, nem recebido do vendedor nem emitido a fornecedor. Não são criadas as tabelas `cheques` e `eventos_cheque`, o lançamento de caixa `CHEQUE_COMPENSADO`, a conta a receber de cheque devolvido nem a conciliação C6. O valor `CHEQUE` que já existe no banco como forma de `pagamentos_cliente` (V022) continua aceito para não quebrar dados antigos (tarefa 6.13), mas não aparece em tela nova e não tem controle de compensação.

### 11. Caixa

- Um caixa só; os relatórios separam por forma (dúvida 13). Saldo = Σ entradas − Σ saídas, pela soma dos lançamentos (sem atalho gravado; poucos lançamentos por dia).
- **Lançamentos automáticos**, na mesma transação do fato, com origem única: recebimento do vendedor em dinheiro, transferência (`PIX`) ou boleto pago (entrada de caixa); pagamento a fornecedor (saída); acerto pago a favor do vendedor (saída).
- **Lançamentos manuais** (despesas e receitas avulsas) com categoria, descrição e data, só da dona. Correção é estorno (`estorna_id`, sinal oposto, único).
- Caixa negativo **não é bloqueado** (o dinheiro já saiu de verdade), mas gera aviso em "O que precisa da sua atenção" (dúvida 13). Esta regra é de dinheiro; a de peças (decisão 4) continua recusando sempre.

### 12. Contas a pagar

- A dona gera as contas de uma compra (Fase 2) escolhendo o número de parcelas e o primeiro vencimento; o servidor divide o **custo total da compra** com `dividirEmParcelas` (dúvida 15). A soma das parcelas é sempre o total da compra.
- Pagamento ao fornecedor: `baixa_pagar` + saída de caixa, na mesma transação; forma registrada: dinheiro, transferência ou boleto (`DINHEIRO`, `PIX`, `BOLETO`, os mesmos valores do repasse). Pagamento acima do saldo da parcela é recusado.
- Compra estornada cancela as parcelas em aberto (baixa `CANCELAMENTO`); parcela já paga não se cancela: a devolução do fornecedor é lançamento manual de entrada.

### 13. Concorrência

- **Ordem única de travas** (`SELECT … FOR UPDATE`), sempre nesta sequência, para nunca haver espera em volta (deadlock): **vendedor → acerto → remessas (por `id`) → itens da remessa e produtos (por `id`, ADR-0008) → contas (por `id`)**.
- Iniciar, desistir e fechar travam a linha do **vendedor** primeiro: dois fechamentos ou um início e um fechamento do mesmo vendedor são serializados.
- Venda, devolução e troca (Fases 4 e 6) **travam a linha da remessa antes de conferir o status**. A tarefa 7.4 confere isso no `ServicoDeVendas` e acrescenta a trava se faltar: sem ela, uma venda pode passar entre a leitura do status e a mudança para `EM_ACERTO`.
- Baixa de conta trava a conta.
- O índice único de `acerto_eventos` é a segunda trava: um evento nunca entra em dois acertos, mesmo com erro de programação.
- Testes com operações paralelas (lista no plano).

**Como ficou (tarefa 7.4, 14/11/2026).** A trava do vendedor é `SELECT … FROM vendedores … FOR NO KEY UPDATE`, **não** `FOR UPDATE`: inserir venda, movimento ou conferência pega `FOR KEY SHARE` na linha do vendedor (chave estrangeira), e `FOR UPDATE` esperaria por ela; com a venda já segurando a maleta, isso fecharia uma espera em volta (venda: maleta, depois a chave do vendedor; iniciar: vendedor, depois a maleta). `FOR NO KEY UPDATE` não conflita com `FOR KEY SHARE` e serializa iniciar, desistir e enviar remessa (e, na 7.5, fechar) do mesmo vendedor. Iniciar: vendedor, depois as maletas abertas por `id`, depois o acerto; desistir: vendedor, acerto, maletas por `id`; leitura: só o acerto (`FOR UPDATE`, que também espera uma desistência em andamento); enviar remessa: a trava `limite-do-vendedor` que já existia, depois o vendedor, depois a remessa. A venda já travava a maleta antes de ler o status; a devolução e a troca seguem com a trava por `pg_advisory_xact_lock` do vendedor delas (outra trava, sem volta com a nova). Teste paralelo com tempo limite de 10 s para venda, devolução, troca e envio de remessa contra o início do acerto.

### 14. Idempotência

Todo comando que grava tem UUID do cliente: iniciar acerto (id do acerto), leitura, desistir, fechar (id do fechamento), resolver pendência, recebimento, baixa, lançamento manual, contas a pagar e o pagamento delas. Mesmo `id` e mesmo conteúdo: **200** com o registro já gravado, sem efeito novo; primeira gravação: **201**; mesmo `id` com conteúdo diferente: **409**. Igual ao ADR-0008 (item 6), ao ADR-0010 (item 9) e ao ADR-0011 (item 9).

### 15. Auditoria

Entram na auditoria (§11), com usuário, motivo e antes/depois: iniciar, desistir e fechar acerto; resolução de pendência; aprovação ou recusa de defeito; recebimento e seu estorno; lançamento manual de caixa e seu estorno; geração, pagamento e cancelamento de contas; fechamento de semana; mudança da meta e dos percentuais de comissão (em `configuracoes`) e de limite de consignação.

### 16. Permissões

- Toda rota de acerto e financeiro da dona fica em `/api/dona/...`, **só ADMIN**.
- O vendedor, em `/api/vendedor/...`, só: **pedir (iniciar) o próprio acerto**, **bipar a contagem** do próprio acerto `EM_ACERTO`, informar parcelas na venda a prazo e **ler** o que é dele: acerto, itens, pendências, comissões (gravadas e prévia, com o que falta para a meta), contas a receber nos dois níveis, recebimentos. Nunca caixa, contas a pagar, custo, lucro ou dados de outro vendedor (404 para registro de outro).
- ADMIN em rota do vendedor e VENDEDOR em rota da dona: 403, com teste. A varredura da barreira (`npm run barreira:varrer`) confere que os tipos novos do vendedor não têm campo de custo, caixa ou contas a pagar.

### 17. Relatórios e conciliações

**De onde sai cada número** (sempre do servidor, consulta única por número, §13.2):

| Relatório (§12) | Fonte |
| --- | --- |
| Acerto por vendedor e semana; esperado × contado; faltas e pendências; descontos | `acertos`, `itens_acerto`, `pendencias_acerto`; detalhamento por `acerto_eventos` até a venda |
| Comissões por vendedor e semana, pagas e a pagar | pagas: `comissoes` de acertos `ACERTADO` (com o percentual e a soma em Guarani que decidiu a meta); a pagar: prévia sobre eventos sem acerto |
| Contas a receber (dois níveis) e inadimplência | `contas_receber` + `baixas_receber`; vencida = em aberto com vencimento antes de hoje (Brasília) |
| Contas a pagar | `contas_pagar` + `baixas_pagar` |
| Fluxo de caixa | `lancamentos_caixa` por `data_lancamento`, saldo acumulado |
| Recebimentos por forma | `recebimentos_vendedor` (dona) e `pagamentos_cliente` (vendedor) |
| Do vendedor: meu acerto, minha comissão, meus clientes a receber, recebimentos por forma | as mesmas consultas, filtradas por ele, sem custo |
| DRE simplificado, lucro por produto e por vendedor | **Fase 8** (usa custo; só a dona) |

Valores em Real, Dólar e Guarani pelo ADR-0006 (cada lançamento pela cotação do seu dia; o acerto pela cotação gravada).

**Conciliações** (em Real, com os valores gravados; Dólar e Guarani nunca entram). São **nove** (C1 a C10, sem a C6). A Fase 7 entrega a consulta, o teste automático e uma rota da dona com as diferenças (zero em condições normais); a rodada diária com aviso é da Fase 8.

| # | Conciliação | Por quê |
| --- | --- | --- |
| C1 | Para cada venda e no total: `total − devolvido = Σ pagamentos líquidos (pagamentos − reembolsos, inclusive crédito usado) + Σ saldo em aberto das parcelas` | Versão correta de "soma das vendas = contas a receber" (F3) |
| C2 | Para cada acerto: `vendido_bruto − descontos − devolvido − comissao + faltas_cobradas = liquido` e `liquido = recebido_no_acerto + ficou_devendo`; `vendido_liquido = Σ acerto_eventos.valor` | Versão correta do "total do acerto" (F4) |
| C3 | Por vendedor: `comissão devida (Σ comissoes) = paga (Σ acertos.comissao dos ACERTADO)`; e cada linha `COMISSAO` = `aplicarPorcentagem(base, percentual)` recalculada, com o `percentual` igual ao do acerto dela; soma dos estornos de uma venda ≤ comissão dela | Comissão paga + a pagar = devida |
| C4 | `saldo do caixa = Σ entradas − Σ saídas`; cada recebimento confirmado (dinheiro, transferência, boleto pago) e cada baixa de conta a pagar tem **exatamente um** lançamento de caixa do mesmo valor; nenhum lançamento automático sem origem | "Total recebido = lançamentos de caixa" |
| C5 | Para cada conta (a receber e a pagar): `valor = pago + cancelado + em aberto`, com `valor_pago` e `valor_cancelado` iguais à soma das baixas | Atalho nunca diverge |
| C6 | _Retirada no aceite de 13/11/2026: era a conciliação dos cheques, e no sistema novo não existe cheque. O número fica vago_ | — |
| C7 | Por produto, em quantidade: `estoque central + Σ saldo das maletas abertas + peças com defeito separadas = comprado ± ajustes − saídas − avarias − vendido líquido − perdas` | "Estoque + maletas + vendido = comprado" (§13, completa o ADR-0010 item 12) |
| C8 | Cada maleta `ACERTADA` tem saldo zero; cada `FALTA` tem um `PERDA` da mesma quantidade | O acerto zera a maleta |
| C9 | Cada evento do vendedor (venda, estorno, devolução, troca) está em no máximo um acerto; eventos de maletas `ACERTADA` sem acerto = 0 | Nada fica fora nem entra duas vezes |
| C10 | Para cada compra com contas: `Σ parcelas não canceladas + canceladas = total da compra` | Contas a pagar fecham com a compra |

### 18. Exemplo numérico (vira teste das tarefas 7.5 e 7.6)

Configuração: meta de 700.000 Gs, 30% abaixo e 40% a partir dela. Maleta NOVA: 10 × peça A a R$ 50,00. Reposição: 5 × peça B a R$ 33,33. Cotação gravada nas vendas desta semana: 1 Gs = R$ 0,00090000.

| Evento | Valor | Para a meta (Gs) | Comissão (30%) |
| --- | --- | --- | --- |
| V1: 2 × A, desconto R$ 5,00 | total 95,00 (subtotal 100,00) | 95,00 ÷ 0,0009 = 105.555,56 → **105.556** | 28,50 |
| V2: 1 × B | 33,33 | 33,33 ÷ 0,0009 = 37.033,33 → **37.033** | 33,33 × 30% = 9,999 → **10,00** |
| V3: 1 × A, estornada no mesmo dia | +50,00 e −50,00 | +55.556 e −55.556 | +15,00 e −15,00 |
| Devolução (Fase 6) de 1 × A de venda de semana fechada, R$ 20,00, comissão original 8,00 (linha gravada a 40%), peça boa entra nesta maleta | −20,00 | não entra (venda de acerto já fechado) | estorno proporcional pelo percentual da linha original: 8,00 × 20,00 / 20,00 = **−8,00** |

- Meta: 105.556 + 37.033 = **142.589 Gs**, abaixo de 700.000 → o percentual do acerto é **30%**. Faltam 557.411 Gs (na prévia do vendedor, também em Real).
- Esperado de A: 10 − 2 − 1 + 1 (estorno) + 1 (devolução) = **9**; contado **8** → falta 1, valor R$ 50,00, a dona cobra no acerto. B: esperado 4, contado 4.
- `vendido_bruto` = 100,00 + 33,33 + 50,00 = 183,33; `descontos` = 5,00; `devolvido` = 50,00 + 20,00 = 70,00; `vendido_liquido` = **108,33** (= 95,00 + 33,33 + 50,00 − 50,00 − 20,00).
- `comissao` = 28,50 + 10,00 + 15,00 − 15,00 − 8,00 = **30,50**.
- `liquido` = 108,33 − 30,50 + 50,00 = **127,83** = `a_pagar_no_acerto` (não existe entrada).
- Pagamento: R$ 60,00 em dinheiro + R$ 50,00 por transferência (`PIX`) → `recebido_no_acerto` = 110,00; `ficou_devendo` = **17,83** (conta a receber do vendedor).
- Caixa: +60,00 e +50,00, os dois no fechamento.
- Estoque: A volta 8 (`DEVOLUCAO`), 1 sai como `PERDA` na maleta; B volta 4. As duas maletas ficam com saldo zero.
- Conferências: C2: 183,33 − 5,00 − 70,00 − 30,50 + 50,00 = 127,83 ✓ e 127,83 = 110,00 + 17,83 ✓.
- Variação para o teste da meta: com vendas somando exatamente 700.000 Gs, o percentual é 40% **sobre todas** as vendas do acerto; com 699.999 Gs, 30%.

## Consequências

### Boas

- Todo número do acerto é explicável até a venda (`acerto_eventos`), e nenhum evento entra em dois acertos.
- Acerto fechado e semana fechada nunca mudam; correção sempre aparece no próximo acerto ou no nível 2, com rastro.
- Venda atrasada e devolução tardia têm destino definido: nada some, nada entra calado.
- O arredondamento continua só em `Dinheiro`; a comissão por venda permite o estorno proporcional pelo acumulado que o ADR-0002 já testa.
- Os atalhos (`valor_pago`, `status`) dão listas rápidas sem perder a regra de evento imutável.

### Ruins ou custos

- Muitas tabelas novas (13) e uma transação de fechamento grande; os testes de concorrência e de conciliação são longos.
- O fiado pesa no vendedor (item 1). Se a dona decidir o contrário, muda o cálculo do líquido e as contas do nível 1 passam a ser da loja: é a dúvida que mais mexe no modelo e precisa de resposta **antes da tarefa 7.5**.
- A comissão por venda difere da comissão sobre o total em alguns centavos (ADR-0002, exemplos); a dona precisa ver isso na validação (§13.7).
- A meta está em Guarani e tudo é gravado em Real: a mesma venda em reais pode pesar mais ou menos na meta conforme a cotação do dia dela. Por isso cada venda usa a cotação gravada nela, e o acerto guarda a soma em Guarani que decidiu o percentual.
- Perto da meta, uma venda a mais muda a comissão do acerto inteiro (de 30% para 40% sobre o total): a prévia do vendedor precisa mostrar quanto falta.
- Boleto vira conta a receber, não pagamento: a tela precisa explicar.
- A Fase 7 depende da Fase 6 para devoluções e trocas; sem ela, o acerto funciona só com vendas e estornos.

## Riscos

- **Venda sem internet depois do fechamento** (Fase 5): vira pendência; se a dona não resolver, a falta cobrada e a venda podem cobrar o vendedor duas vezes. Mitigação: a pendência de venda atrasada mostra a falta que ela explica e a resolução cancela a cobrança.
- **Trava esquecida na venda** (item 13): sem travar a remessa, uma venda passa durante o início do acerto. Mitigação: teste paralelo "vender × iniciar acerto" obrigatório na 7.4.
- **Atalho divergente** (`valor_pago`, `status`): mitigado por teste de arquitetura (só o serviço escreve) e pela C5.
- **Fechamento parcial**: impossível por desenho (uma transação); um teste derruba a transação no meio e confere que nada ficou gravado.
- **Mudança de regra pela dona** (fiado, meta e percentuais da comissão): o modelo guarda base, percentual e eventos; trocar a regra é código novo e migração nova, sem perder dado, mas acertos já fechados ficam como foram.
- **Erro de digitação de recebimento**: correção só por estorno; a tela precisa deixar o estorno fácil para não virar lançamento "compensando" o erro.

## Alternativas consideradas

- **Fiado da loja (o vendedor repassa só o que recebeu):** contraria a §7.2 e a fórmula da §13; deixaria o acerto dependente do comportamento de cada cliente. Descartada como padrão; é a dúvida 1.
- **Comissão sobre o total do acerto (caso (b)):** não deixa estornar proporcionalmente uma venda de semana fechada sem inventar uma "comissão da venda". Descartada como padrão.
- **Comissão calculada e gravada na hora da venda:** exigiria histórico de percentual e recalcular quando a venda é estornada no mesmo dia; o fechamento é o momento natural e a prévia cobre o "a pagar". Descartada.
- **Acerto por período de datas (todas as vendas de segunda a domingo):** perde a venda que chega atrasada e mistura semanas fechadas. Trocado por "todos os eventos ainda sem acerto" com índice único.
- **Reabrir acerto ou semana para corrigir:** contraria §7.4.7 e §13.6. Descartada.
- **Status editável em `contas_receber` (como na §9):** perde o histórico de pagamentos parciais. Trocado por baixas imutáveis + atalho mantido pelo serviço.
- **Entrada (dinheiro adiantado ao levar a maleta, abatido no acerto):** era o padrão da versão proposta. Retirada no aceite de 13/11/2026: no sistema novo não existe entrada.
- **Controle de cheques (a compensar, compensado, devolvido):** estava na versão proposta. Retirado no aceite de 13/11/2026: no sistema novo não existe cheque.
- **Percentual de comissão por vendedor** (`vendedores.comissao_percentual` no instante do fechamento): estava na versão proposta. Trocado pela comissão por meta (resposta da dona em 13/11/2026); a coluna fica, sem uso no cálculo.
- **Meta só sobre o que passa de 700.000 Gs (comissão em duas faixas):** descartada; o percentual vale sobre o total.
- **Fechar o acerto com sobra aberta:** a peça a mais sem explicação esconderia venda errada. Descartada: sobra impede o fechamento.
- **Bloquear caixa negativo:** obrigaria a dona a inventar entrada para registrar uma despesa real. Descartada; vira aviso.
- **Gravar o saldo do caixa num atalho:** pouco volume, e mais um número para divergir. Descartada por ora.
- **Multa e juros automáticos:** a §7.5 não manda. Fora até a dona pedir.

## Seções da especificação relacionadas

§1, §2 (aba Totais), §4, §6 (premissas 6, 8 e 9), §7.1 a §7.5, §9 (Acerto e financeiro, campos de controle), §10 (o que o vendedor recebe), §11, §12, §13, §15 (Fase 7), §17 (6, 8, 12 e 13), §22 (v1.14). ADR-0002, ADR-0006, ADR-0008, ADR-0010, ADR-0011.

## Dúvidas

As dúvidas para a dona, cada uma com o padrão seguro adotado, estão em [fase-7-plano.md](../progresso/fase-7-plano.md#dúvidas-para-a-dona). A que ainda muda o modelo e precisa de resposta antes da 7.5 é a 1 (fiado). A 2 e a 4 (comissão), a 7 (entrada), a 11 (cheque), a 14 (cartão) e a 20 (semana) foram respondidas em 13/11/2026.

## Histórico

- **11/11/2026:** proposto (tarefa 7.1, PR 73).
- **13/11/2026:** aceito pelo responsável com quatro mudanças (tarefa 7.1b): não existe entrada (itens 8 e 9, C2, exemplo); não existe cheque (item 10, tabelas `cheques` e `eventos_cheque`, `CHEQUE_COMPENSADO`, conta a receber de cheque devolvido, C6); formas do repasse `DINHEIRO`, `PIX` ("Transferência") e `BOLETO`, e as mesmas no pagamento a fornecedor; comissão por meta de 700.000 Gs com 30% ou 40% sobre o total, no lugar do percentual por vendedor (item 5, exemplo do item 18 refeito). A última migração passou a ser a V040.
- **14/11/2026:** item 7 implementado no nível 1 (tarefa 7.3).
- **14/11/2026:** itens 2, 3, 4, 13, 14, 15 e 16 implementados na parte 1 do acerto, sem dinheiro (tarefa 7.4; V043).
