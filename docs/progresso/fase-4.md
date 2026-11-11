# Detalhe das tarefas da Fase 4

Uma seção por tarefa, no mesmo formato de [fase-3.md](fase-3.md). A tabela de acompanhamento está em [PROGRESSO.md](../PROGRESSO.md). O modelo da venda está no [ADR-0011](../decisoes/ADR-0011-venda-do-vendedor-online.md).

### Tarefa 4.1: plano da Fase 4 e ADR da venda (só documentos)

**Em revisão.** Nenhum código. A especificação não mudou: tudo cabe nas §7.1, §7.2, §9, §11 e §12.

**O que foi feito**

- Seção "Fase 4" em `docs/PROGRESSO.md` com o plano (4.1 a 4.6).
- [ADR-0011](../decisoes/ADR-0011-venda-do-vendedor-online.md), Proposto: tabelas, preço congelado, servidor recalcula, desconto, pagamento, baixa da maleta por movimento, estorno, idempotência, cotação, conferência com dispositivo único, o que o vendedor vê e relatórios próprios.
- Linha do ADR-0011 no índice de decisões (o 0009 é da Fase 9).

**Regras da Fase 4 (valem para todas as tarefas)**

- Rotas do vendedor em `/api/vendedor/...`; ADMIN nelas e VENDEDOR nas da dona dão 403, com teste.
- O app manda produto, quantidade, desconto e pagamentos; nunca preço nem total. O servidor recalcula.
- Venda, pagamento e movimento imutáveis; correção é estorno. Reenvio com o mesmo UUID: 200; primeira gravação: 201; conteúdo diferente: 409.
- Sem preço de custo, sem estoque central, sem dados de outros vendedores; `npm run barreira:varrer` passa.
- Dinheiro e estoque: testes primeiro.

**Dúvidas abertas:** as seis do ADR-0011 (desconto em valor ou %, parcela mínima, prazo do estorno, quem troca o aparelho, vender sem conferir, consulta fora da maleta).
