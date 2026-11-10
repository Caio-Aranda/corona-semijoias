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
