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

### Tarefa 4.2: backend das vendas (vender, estornar, totais no servidor)

**Em revisão.** Migração `V022__vendas.sql`; pacote `vendas` (`ServicoDeVendas`, `ConsultasDeVendas`, `VendasDoVendedorController`, `VendasDaDonaController`).

**O que foi feito**

- Tabelas `vendas`, `itens_venda` e `pagamentos_cliente` (sincronizáveis; itens e pagamentos imutáveis; a venda só muda de status no estorno, protegida por gatilho). Chave nova `limite_desconto_percentual` em `configuracoes` (padrão 0).
- `POST /api/vendedor/vendas`: baixa a maleta pelo movimento `VENDA`, preço do `preco_remessa`, desconto em valor por linha respeitando o limite, pagamentos opcionais (à vista ou a prazo), cotação do dia guardada para mostrar Dólar e Guarani. 201 na primeira gravação, 200 no reenvio igual, 409 com o mesmo id e dados diferentes.
- Estorno da venda inteira (`POST /api/vendedor/vendas/{id}/estorno`, só no dia da venda; `POST /api/dona/vendas/{id}/estorno`, sem prazo): movimentos `ESTORNO_VENDA` e reembolso negativo por pagamento. Reenvio com o mesmo id: 200.
- Leitura: `GET /api/vendedor/vendas` (filtros por dia, cliente e situação) e `GET /{id}`; `GET /api/dona/vendas/{id}`. Sem custo em nenhuma resposta.
- 15 testes de integração: preço congelado, totais, a prazo, idempotência, saldo, peça fora da maleta, cliente e maleta de outro vendedor, maleta em rascunho ou cancelada, limite de desconto, pagamento a mais, estorno e seus reenvios, prazo do estorno, imutabilidade no banco, isolamento, 403 cruzado e concorrência (seis vendas da última peça: só uma entra).

**Decisões que valem daqui para a frente**

- O app nunca manda preço nem total; `totalConferido` só gera alerta na auditoria.
- A conferência da maleta e a troca de aparelho ficam na 4.3; a venda já recusa maleta ligada a outro aparelho (`MALETA_EM_OUTRO_APARELHO`).
- A tela de configuração do limite de desconto não existe (sem rota da dona): o valor 0 só muda no banco até a dona ter essa tela (pendência).
- A conciliação "estoque + maletas" já enxerga as vendas porque o saldo vem dos movimentos; a conciliação completa com vendas é da Fase 8.
