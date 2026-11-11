# Detalhe das tarefas da Fase 3

Uma seção por tarefa, no mesmo formato de [fase-2.md](fase-2.md). A tabela de acompanhamento está em [PROGRESSO.md](../PROGRESSO.md). O modelo da maleta está no [ADR-0010](../decisoes/ADR-0010-maleta-e-remessa.md).

### Tarefa 3.1: plano da Fase 3 e ADR da maleta (só documentos)

**Em revisão.** Nenhum código. A especificação não mudou: tudo cabe na §7.1, na §9 e na §13.

**O que foi feito**

- Seção "Fase 3" em `docs/PROGRESSO.md`, com o plano (3.1 a 3.8) e as pendências da maleta.
- [ADR-0010](../decisoes/ADR-0010-maleta-e-remessa.md), Proposto: tabelas, estados, envio que baixa o estoque, limite, restrição, preço congelado, idempotência, conciliação e nota em PDF.
- Linha do ADR-0010 no índice de decisões (o 0009 é da Fase 9).

**Regras da Fase 3 (valem para todas as tarefas)**

- Montar é **online**, só a dona (ADMIN), com leitor; foto grande da peça lida; falta de foto nunca trava.
- Rascunho não mexe no estoque; **só o envio** baixa o estoque central, tudo ou nada, pelo serviço único do ADR-0008 (`REMESSA`; devolução à dona é `DEVOLUCAO`).
- Preço congelado no envio; o servidor recalcula tudo e nunca confia no app.
- Movimentos e remessa enviada são imutáveis; correção é estorno.
- Bloqueios no servidor: limite de consignação, restrição que impede, vendedor inativo, peça sem saldo central.
- Reenvio com o mesmo UUID: 200 (já gravado); primeira gravação: 201.
- O app do vendedor nunca recebe custo nem dados de outros vendedores.

**Escopo de cada tarefa:** ver a tabela em `PROGRESSO.md`. Os estados `CONFERIDA`, `EM_ACERTO` e `ACERTADA` ficam para as Fases 4 e 7.

**Decisões que tomei**

- Limite medido a **preço de venda**, com a reposição dentro da conta (provisório).
- Nota em PDF gerada no **app da dona**, com dados vindos do servidor, no mesmo gerador da 2.8; troca de biblioteca só se a 3.4 mostrar que não dá.
- Montagem não cadastra peça nova (diferente da compra).
- Restrição só bloqueia se marcada "Impede montar maleta" (glossário, 1.2).

**Dúvidas abertas:** as seis do fim do ADR-0010.

**Não feito:** código, termos de tela da maleta no glossário (vêm na 3.5) e mudança na especificação.
