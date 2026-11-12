# Plano da Fase 6: devolução, troca e garantia

Pós-venda completo (§15): devolução do cliente dentro da garantia, troca com diferença, crédito do cliente, pedido de autorização à dona fora do prazo, reembolso, antifraude e relatórios (§7.3, §9, §12). O modelo está no [ADR-0014](../decisoes/ADR-0014-devolucao-troca-e-garantia.md) (Proposto). Este arquivo é só o plano (tarefa 6.1, nenhuma linha de código); o detalhe de cada tarefa feita vai para `fase-6.md`.

**Antes de começar o código:** ADR-0014 aceito (dúvida R1) e a Fase 5 com o push (5.5) e o motor do app (5.6) na `main`: o Corona Vendas grava sempre pela outbox (Fase 5, R6), então devolução e troca no app só existem depois do motor. O backend das tarefas 6.2 a 6.5 pode começar antes, com rotas online, como a Fase 4 fez. A especificação muda pouco: a §7.3 e a §9 ganham as decisões do ADR-0014 (sem `itens_troca`, `usos_credito` novo, peça para a loja), na tarefa 6.2.

## Regras da Fase 6 (valem para todas as tarefas)

- **Modo de plano obrigatório** nas tarefas marcadas (dinheiro, estoque da maleta e sync): plano escrito e aprovado pela orquestradora antes da primeira linha de código. Esforço **alto** nelas.
- **Testes primeiro** em dinheiro e estoque, sempre com PostgreSQL de verdade (Testcontainers). Pronto = funcionalidade + testes + relatório + conciliação passando.
- Dinheiro em `NUMERIC(14,2)`, tudo em Real; arredondamento **só** em `Dinheiro` (HALF_UP, escala 2); Dólar e Guarani só na tela, pela cotação do dia de cada lançamento (ADR-0006).
- **Imutável, correção por estorno.** Devolução, troca, crédito, uso de crédito, reembolso e movimentos nunca recebem `UPDATE` de conteúdo nem `DELETE`; "Cancelar devolução" e "Cancelar troca" são registros novos de sinal oposto. A venda original **não muda** (ADR-0014, item 1).
- **O servidor recalcula** prazo, valor devolvido, reembolso, diferença da troca, crédito e limites. O app manda só venda, peças, quantidades, estado da peça, motivo e forma do reembolso; nunca valor.
- O que já aconteceu no balcão nunca é recusado em silêncio: inconsistência vira **"para revisão da dona"** (ADR-0014, item 5); recusa só para o impossível.
- **Permissões na API:** o vendedor só vê e devolve venda dele (403 cruzado com teste); autorização e revisão só ADMIN.
- **Barreira do Corona Vendas:** nada de custo, nada de outro vendedor (nem crédito de cliente gerado por outro vendedor); `npm run barreira:varrer` passa.
- **Offline-first (Fase 5):** uma operação = registro local + linha da outbox na mesma transação; o push chama **os mesmos serviços** das rotas online; tabela sincronizável nova só entra com filtro, expansão e saída (ADR-0013, item 5) e no oráculo de dois vendedores; prazos e limites chegam no bloco `parametros`.
- UUID do cliente em todo comando: 201 na primeira gravação, 200 no reenvio igual, 409 com o mesmo id e conteúdo diferente.
- Texto de tela novo entra **no glossário antes** de ser usado (linguagem da loja: devolução, troca, crédito do cliente, pedido de autorização, devolver o dinheiro).
- Migração Flyway nova a cada tarefa (o próximo número livre na hora; hoje a última na `main` é a V032 e a Fase 5 ainda cria outras). Migração publicada não se edita.

## Tarefas

| Nº | Tarefa | Modo de plano | Depende de |
| --- | --- | --- | --- |
| 6.1 | docs: este plano e o ADR-0014 (só documentos) | — | — |
| 6.2 | backend: garantia em `configuracoes`, especificação §7.3 e §9 atualizadas, bloco `parametros` | Não (médio) | R1 |
| 6.3 | backend: devolução do cliente (à vista), peça boa e com defeito, reembolso, cancelar devolução | **Sim** | 6.2 |
| 6.4 | backend: pedido de autorização, revisão da dona e antifraude | **Sim** | 6.3 |
| 6.5 | backend: crédito do cliente e troca | **Sim** | 6.3 |
| 6.6 | backend: devolução e troca de venda a prazo (parcelas) | **Sim** | 6.3, tarefa 7.3 (contas a receber) |
| 6.7 | backend: push e pull das tabelas da Fase 6 | **Sim** | 6.3 a 6.5, tarefas 5.4 e 5.5 |
| 6.8 | seller-app: devolução, troca, crédito e pedido de autorização no Corona Vendas | **Sim** | 6.7, tarefa 5.6 (5.7 para o indicador) |
| 6.9 | owner-app: devoluções para autorizar, para revisão, devolução pela dona, créditos | Não (médio-alto) | 6.4, 6.5 |
| 6.10 | relatórios de devolução e troca (dona e vendedor) e conciliações | Não (alto) | 6.3 a 6.5 |
| 6.11 | `testes-sync/`: devolução fora do prazo offline, relógio errado, crédito offline | **Sim** | 6.7, 6.8, tarefa 5.12 |
| 6.12 | fechamento da Fase 6 (revisão, teste de uso, ADR-0014 Aceito) | — | todas |

**Ordem sugerida.** O backend em sequência, porque as tarefas mexem no mesmo serviço e criam migrações: 6.2 → 6.3 → 6.4 e 6.5 (uma depois da outra, pelo número das migrações) → 6.7. A 6.6 entra assim que a tarefa 7.3 estiver na `main` (antes dela, a devolução de venda a prazo responde 409 "Esta venda tem parcelas; a devolução dela chega em breve"). Depois, em paralelo: 6.8 (app), 6.9 (dona) e 6.10 (relatórios), se não tocarem os mesmos arquivos. A 6.11 com tudo integrado. A 6.12 por último.

**Fase 7.** O acerto (7.5) lê devoluções, trocas e peças para a loja. Se a Fase 6 chegar antes da 7.5, a 7.5 já liga `DEVOLUCAO` em `acerto_eventos`; se chegar depois, a 6.12 acrescenta o tipo por migração nova (o `CHECK` é ampliável, plano da Fase 7). O tipo `TROCA` não é necessário (ADR-0014, item 8). A **pendência do vendedor** por devolução não aceita (ADR-0014, item 5) usa `pendencias_acerto` da 7.5: até lá, fica registrada na revisão e entra no primeiro acerto.

### 6.1: docs (esta tarefa)

ADR-0014 (Proposto), este plano e uma linha em `PROGRESSO.md`. Nenhum código, nenhuma migração.

### 6.2: garantia e parâmetros

- Migração: chaves em `configuracoes` com `CHECK` de faixa: `prazo_arrependimento_dias` (padrão 7), `prazo_defeito_dias` (padrão 30), `limite_reembolso_semanal` (Real; padrão sem limite até a dona responder), `valor_aprovacao_reembolso` (padrão sem valor) e `percentual_alerta_devolucao` (padrão 20).
- Rotas da dona para ler e mudar (auditoria do valor antigo); bloco `parametros` do pull ganha `prazoArrependimentoDias` e `prazoDefeitoDias` (ADR-0013: chave nova = linha de código com teste).
- Atualizar a especificação (§7.3, §9 e §22) com as decisões do ADR-0014 antes do código das tarefas seguintes.
- **Testes:** faixa recusada, mudança auditada, `parametros` sem chave a mais, 403 do vendedor na rota da dona.

### 6.3: devolução do cliente (modo de plano, crítica)

- Migração: `devolucoes` (venda, cliente, vendedor, remessa, motivo `ARREPENDIMENTO | DEFEITO | TAMANHO | OUTRO`, observação, `destino: REEMBOLSO | TROCA`, `dentro_garantia`, `situacao: ACEITA | EM_REVISAO | MANTIDA | NAO_ACEITA | CANCELADA`, `autorizacao_id`, `valor_devolvido`, `valor_reembolsado`, hora e `hora_ajustada`) e `itens_devolucao` (item da venda, quantidade, valor, `estado_peca`, `destino_peca: MALETA | LOJA`); sincronizáveis, `origem_dispositivo` com FK; foto opcional fica para depois (dúvida D10).
- `ServicoDeDevolucoes`: trava a venda, depois remessas e itens em ordem de `id`; soma das devoluções ativas por linha nunca passa do vendido (segunda trava no banco); valor por unidade com `Dinheiro.dividirEmParcelas`; prazo em dias de Brasília pelo motivo; destino da peça (ADR-0014, item 7): `DEVOLUCAO_CLIENTE` na maleta aberta ou peça para a loja; reembolso `REEMBOLSO` negativo na forma escolhida; venda com devolução não pode mais ser estornada.
- `POST /api/vendedor/devolucoes`, `GET` das devoluções dele e busca da venda pela peça lida (§8: "localiza a venda original") com o que ainda pode ser devolvido; `POST .../{id}/cancelar` (vendedor até o fim do dia, depois só a dona).
- Fora do prazo sem autorização: 409 "Passou do prazo da garantia. Peça autorização à dona." (a autorização é da 6.4).
- Maleta em acerto: 409 (ADR-0012). Venda a prazo: 409 até a 6.6.
- **Testes primeiro:** devolução total e parcial; várias parciais da mesma linha somando o total exato da linha (centavos); quantidade acima da vendida; duas devoluções da mesma venda ao mesmo tempo (uma passa); prazo no último dia e no dia seguinte, com virada de dia em Brasília e UTC; defeito com prazo maior; peça boa de maleta aberta volta ao saldo e de maleta acertada vira peça para a loja; defeito nunca volta ao saldo; cancelar devolve tudo como era; estorno de venda com devolução recusado; idempotência 201/200/409; 403 cruzado; nada de custo nas respostas; conciliação do saldo da maleta.

### 6.4: autorização, revisão e antifraude (modo de plano)

- Migração: `autorizacoes_excecao` (venda, itens e quantidades pedidos, motivo, `tipo: FORA_DO_PRAZO | ACIMA_DO_VALOR`, `situacao: PEDIDA | APROVADA | NEGADA | USADA | VENCIDA`, quem decidiu, quando, validade de 7 dias), sincronizável; índice único de uso por devolução.
- Vendedor: `POST /api/vendedor/autorizacoes`; a devolução com `autorizacao_id` aprovada e dentro da validade passa. Dona: listar, aprovar, negar (com motivo).
- Revisão: devolução que chega `EM_REVISAO` (fora do prazo pelo servidor, acima do limite semanal, reembolso maior que o pago, uso de crédito acima do saldo); a dona marca **Manter** ou **Não aceitar** (gera pendência do vendedor; ADR-0014, item 5).
- Antifraude: limite semanal pela semana de acerto; valor acima do limite de aprovação exige autorização; tarefa diária da taxa de devolução (4 semanas) com aviso em "O que precisa da sua atenção" ("Devoluções para autorizar", "Devoluções para revisar", "Muitas devoluções de um vendedor").
- **Testes primeiro:** pedido, aprovação, negação, autorização vencida e usada duas vezes; limite semanal no limite exato e um centavo acima; taxa de devolução com massa conhecida; revisão mantida e não aceita; 403 do vendedor nas rotas da dona.

### 6.5: crédito do cliente e troca (modo de plano, crítica)

- Migração: `creditos_cliente` (cliente, vendedor que gerou, origem `TROCA | DEVOLUCAO`, valor, atalhos `valor_utilizado` e situação pelo serviço), `usos_credito` (crédito, venda, valor), `trocas` (cliente, vendedor, venda de origem, devolução, venda nova, diferença); `vendas.troca_id`; forma `CREDITO` no `CHECK` de `pagamentos_cliente`; tipos `TROCA_ENTRADA` e `TROCA_SAIDA` passam a ser usados.
- Troca numa operação (ADR-0014, item 8): devolução de A, crédito do valor de A usado na hora, venda de B pelo `ServicoDeVendas` (maleta aberta e conferida, preço congelado, desconto no limite), resto pago pelo cliente ou sobra como crédito aberto (ou em dinheiro, se a dona responder que pode, D4).
- Crédito: uso numa venda nova como forma `CREDITO`; sem internet, só pelo vendedor que gerou; a dona usa e transfere online. "Cancelar troca" com crédito já usado: 409.
- **Testes primeiro:** B mais cara, mais barata e igual; troca de várias peças; A com defeito e B da maleta; B sem saldo (tudo ou nada); crédito usado em parte, de novo e até zerar; uso acima do saldo; cancelar troca; comissão e eventos de acerto como devolução + venda (consulta, sem a Fase 7); conciliação C1 do ADR-0012 com crédito; 403 cruzado e crédito de outro vendedor invisível.

### 6.6: venda a prazo (modo de plano)

- Depois da tarefa 7.3: a devolução cancela primeiro as parcelas em aberto (da última para a primeira, baixa `CANCELAMENTO`) e devolve em dinheiro só o que passar do que falta pagar (ADR-0014, item 10); troca com B a prazo.
- **Testes primeiro:** a prazo sem pagamento (nada a devolver), pago em parte, pago tudo; devolução parcial; cheque devolvido depois da devolução; conciliação C1.

### 6.7: push e pull (modo de plano, crítica)

- Tipos novos da outbox, formato 1: `DEVOLUCAO`, `TROCA`, `PEDIDO_AUTORIZACAO`, `CANCELAR_DEVOLUCAO`, `CANCELAR_TROCA`, despachando para os serviços da 6.3 a 6.5 (nenhuma regra só no caminho do sync).
- Pull: `devolucoes`, `itens_devolucao`, `trocas`, `autorizacoes_excecao`, `creditos_cliente`, `usos_credito` pelo vendedor do token, com expansão e no oráculo de dois vendedores; resultado `EM_REVISAO` visível ao vendedor ("A dona vai olhar esta devolução").
- Hora corrigida (ADR-0013, item 11) decide o prazo; dependência entre operações (troca que usa crédito de outra troca ainda não enviada).
- **Testes primeiro:** reenvio sem duplicar; devolução de venda ainda não enviada (dependência); fora do prazo pelo servidor vira revisão, nunca recusa; maleta em acerto; crédito usado duas vezes no mesmo lote; oráculo sem nenhum id de outro vendedor.

### 6.8: Corona Vendas (modo de plano)

- Telas: **Devolução** (ler a peça ou escolher a venda; foto da peça; quantidades; motivo; estado da peça; prévia do valor e do reembolso), **Troca** (peça que volta, peça que sai, diferença, crédito), crédito do cliente na venda, **Pedir autorização à dona** quando passar do prazo ou do valor, "Minhas devoluções e trocas".
- Tudo no banco local e na outbox, funciona sem internet; prazo e limites pela última `parametros` recebida; aviso das autorizações respondidas.
- Desenho aprovado pela dona antes (§19); textos no glossário antes; botões nomeados (**Registrar devolução**, **Registrar troca**, **Cancelar devolução**).
- **Testes:** cada caminho sem internet, prazo no limite pelo relógio local, bloqueio fora do prazo, palavras proibidas, barreira verde.

### 6.9: Corona Gestão

- "O que precisa da sua atenção": devoluções para autorizar e para revisar, muitas devoluções de um vendedor.
- Telas: autorizar e negar, revisar (Manter, Não aceitar), registrar devolução e troca pela dona (peça vai à loja), créditos dos clientes (usar, transferir), configurar a garantia e os limites.
- Desenho aprovado pela dona antes; textos no glossário antes; **Aprovar reembolso**, nunca "OK".

### 6.10: relatórios e conciliações

- Dona (§12): devoluções e trocas por vendedor e motivo, taxa de devolução, peças com defeito, créditos em aberto e usados, reembolsos, autorizações; com exportação e três moedas.
- Vendedor: minhas devoluções e trocas, créditos dos meus clientes (só dele, sem custo).
- Conciliações: saldo da maleta com devoluções e trocas (§13); devolvido por linha ≤ vendido; Σ créditos − Σ usos = créditos em aberto; reembolso ≤ pago; C1 do ADR-0012 com devolução.
- **Testes:** massa conhecida com resultado calculado à mão.

### 6.11: testes de duas pontas (modo de plano)

- No `testes-sync/` da 5.12: devolução fora do prazo offline (§10), relógio adiantado e atrasado no limite do prazo, troca sem internet com a peça B vendida no mesmo lote, crédito gerado e usado sem internet, cancelar troca depois de enviada, rede ruim no meio do envio da troca.

### 6.12: fechamento

Revisão da fase, teste de uso com a dona e um vendedor (devolução, troca e pedido de autorização, também sem internet), conciliações passando, ADR-0014 Aceito, especificação e `PROGRESSO.md` atualizados; se a 7.5 já estiver na `main`, `DEVOLUCAO` em `acerto_eventos`.

## Dúvidas

Cada uma tem um **padrão seguro**, que vale enquanto não houver resposta.

### Para o responsável

| Nº | Pergunta | Padrão seguro |
| --- | --- | --- |
| R1 | Aceita o **ADR-0014**? | Nada além da 6.2 começa sem ele |
| R2 | Devolução fora do prazo **pelo servidor** (app achou que estava no prazo) é aceita como "para revisão", em vez de recusada? | **Sim** (§7.3: nunca aceito em silêncio, e o dinheiro já saiu) |
| R3 | Troca = devolução + venda nova ligadas, **sem** a tabela `itens_troca` e sem o tipo `TROCA` em `acerto_eventos` (muda §9 e o ADR-0012)? | **Sim** |
| R4 | Peça boa de maleta já acertada não volta ao saldo do vendedor e vai à loja no próximo acerto? | **Sim** (nenhuma maleta acertada recebe movimento) |
| R5 | A devolução de venda a prazo espera a tarefa 7.3 (contas a receber)? | **Sim**; até lá, 409 com aviso claro |
| R6 | Venda com devolução não pode mais ser estornada (só cancelar a devolução antes)? | **Sim** |

### Para a dona

| Nº | Pergunta | Padrão seguro |
| --- | --- | --- |
| D1 | Quantos dias o cliente tem para **desistir** da peça? E para devolver por **defeito**? Muda por tipo de peça? | 7 dias para desistir, 30 para defeito, igual para todas (§17.4) |
| D2 | "Não serviu" (tamanho) conta como desistência? | Sim, mesmo prazo da desistência |
| D3 | Até quanto um vendedor pode devolver em dinheiro por semana? A partir de que valor a senhora quer aprovar antes? | Sem limite semanal e sem aprovação por valor até a senhora definir; o aviso de muitas devoluções vale (D9) |
| D4 | Na troca por peça **mais barata**, a sobra fica como crédito do cliente ou pode voltar em dinheiro? (§17.7) | Fica como **crédito** |
| D5 | O crédito do cliente vence? | Não vence |
| D6 | O cliente pode usar o crédito com **outro vendedor**? | Só com o vendedor que fez a troca; com outro, só a senhora libera, com internet |
| D7 | O cliente pode devolver a peça a **outro vendedor** (não ao que vendeu)? | Não: só ao que vendeu, ou à senhora na loja |
| D8 | Fora do prazo, o vendedor pede autorização e o cliente espera a resposta. Pode ser? Por quantos dias a autorização vale? | Sim; vale 7 dias |
| D9 | A partir de quanto por cento de devolução a senhora quer ser avisada sobre um vendedor? | 20% do vendido nas últimas 4 semanas |
| D10 | A foto da peça devolvida (§7.3, "foto opcional") é necessária agora? | Fica para depois; o motivo é obrigatório |
| D11 | Quando o sistema marcar uma devolução "para revisão" e a senhora não aceitar, o valor é cobrado do vendedor no acerto? | Sim, como pendência do vendedor no próximo acerto |
| D12 | Peça que quebrou **na maleta**, sem ter sido vendida: o vendedor separa como "com defeito" pelo app ou só entrega no acerto? | Só entrega no acerto (a senhora aprova o defeito lá, Fase 7) |
| D13 | Na devolução de uma venda a prazo, o valor devolvido primeiro **abate o que o cliente ainda deve** e só o resto volta em dinheiro? | Sim |
