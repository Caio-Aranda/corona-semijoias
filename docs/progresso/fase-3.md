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

### Tarefa 3.2: backend das remessas (montar, enviar, limite, restrição, preço congelado)

**Em revisão.** Migração V021, rotas só da dona em `/api/dona/remessas`, serviço de envio e testes. Nenhuma tela.

**O que foi feito**

- Tabelas `remessas`, `itens_remessa` e `movimentos_remessa` (sincronizáveis; sem custo em nenhuma coluna). O `CHECK` de status já inclui `CONFERIDA`, `EM_ACERTO` e `ACERTADA`. Depois do envio, gatilhos recusam mudar conteúdo e itens; movimentos são imutáveis.
- Rotas (só ADMIN): criar rascunho (id do app), colocar/trocar e tirar peça (produto e quantidade, com `version`), excluir rascunho (lógico), ver, listar (vendedor, semana, status) e **enviar**.
- Envio numa transação: confere vendedor ativo e sem restrição que impede, peças ativas e limite; congela o preço de venda do dia; baixa o estoque central pelo serviço único (`REMESSA`, origem `MALETA`, ordem estável, tudo ou nada). Trava por vendedor para o limite não ser furado por envios simultâneos.
- Idempotência: criar com o mesmo id e dados iguais = 200; primeira vez = 201; dados diferentes = 409. Enviar de novo = 200 sem baixar de novo.
- Mensagens em português simples (a palavra "remessa" é proibida na tela: os textos dizem "maleta"). Clientes da API regerados.

**Decisões que tomei**

- **Limite 0 = sem limite cadastrado** (o banco guarda 0 por padrão; não distingue "sem limite" de "limite zero"). Limite exato passa; um centavo a mais não.
- **Sem movimento de "saldo inicial"** em `movimentos_remessa`: o saldo da maleta é `qtd_enviada` + movimentos (§7.1, ADR-0010 item 5).
- Em aberto conta as maletas `ENVIADA`, `CONFERIDA` e `EM_ACERTO` (as duas últimas ainda inalcançáveis), a preço congelado.
- Rascunho: uma linha por produto; colocar o mesmo produto de novo troca a quantidade. O pedido de criação não leva itens (assim o reenvio idempotente não briga com edições). Praça vazia usa a do vendedor.
- Restrição que não impede aparece só como `avisos` no rascunho.
- Tabelas registradas como sincronizáveis já agora; o filtro por vendedor do pull é da Fase 5 (hoje não há pull de vendedor).

**Dúvidas abertas:** as do ADR-0010 (limite 0 como "sem limite" entra na dúvida 6).

**Não feito:** devolução, cancelamento de remessa enviada, PDF, relatórios e telas.

### Tarefa 3.3: backend: devolução da maleta, cancelamento e conciliação

**Em revisão.** Sem migração nova (a V021 já tinha tudo). Rotas só ADMIN em `/api/dona/remessas`; nenhuma tela, PDF, relatório ou venda.

**O que foi feito**

- **Devolução à dona** (`POST /{id}/devolucoes`): várias peças por chamada, cada uma com id do aplicativo. Grava `DEVOLUCAO_DONA` (quantidade negativa) em `movimentos_remessa` e `DEVOLUCAO` (origem MALETA) no estoque central, na mesma transação, em ordem estável de produto, tudo ou nada. Nunca passa do saldo da maleta (`qtd_enviada` + movimentos). Só maleta enviada.
- **Cancelar** (`POST /{id}/cancelar`, motivo obrigatório): rascunho vira cancelada sem mexer no estoque; enviada só cancela se não há movimento ativo, e então estorna a baixa do envio pelo serviço de estoque. Cancelada não aceita mais nada; cancelar de novo = 200.
- **Desfazer devolução** (`POST /{id}/devolucoes/{devolucaoId}/estorno`): movimento de sinal oposto com `estorna_id` (único) e o estoque central baixa de novo; se o central não tiver as peças, 409 com texto simples.
- **Leitura**: `GET /{id}/maleta` (por peça: enviada, devolvida, saldo; valor em aberto a preço congelado; movimentos) e `GET /em-aberto?vendedorId=` (valor em aberto por maleta, total, limite e quanto sobra).
- **Conciliação** (`GET /api/dona/estoque/conciliacao`, a que já existia, sem rota nova): além de `divergencias`, traz `diferencasComMaletas` = estoque central + saldo das maletas enviadas contra o que entrou e saiu fora da maleta (compras, entradas, saídas, perdas, ajustes e estornos). Os movimentos da maleta só trocam peça de lugar e ficam de fora do lado direito.
- Testes primeiro (22 em `DevolucaoDaMaletaTest`): saldo, parcial e total, idempotência 201/200/409, cancelamentos, estorno, permissão (vendedor 403), limite que libera depois da devolução, concorrência (devoluções, devolução x cancelamento, central sob saída simultânea) e conciliação depois de compra, maleta, devolução, estorno, cancelamento, perda e inventário, inclusive com operações simultâneas.
- `operationId` explícito em todas as rotas novas; clientes regerados e `npm run typecheck` passando.

**Decisões que tomei**

- O id da devolução do aplicativo é também o id do movimento no estoque central: o reenvio é idempotente nos dois lados e o estorno acha o movimento certo.
- "Movimento ativo" (que impede cancelar maleta enviada) = movimento da maleta que não é estorno e não foi estornado: devolução desfeita deixa cancelar.
- Idempotência vem antes da conferência de status: reenviar uma devolução já gravada dá 200 mesmo que a maleta tenha sido cancelada depois (porque a devolução foi desfeita).
- A mesma peça não pode aparecer duas vezes na mesma chamada de devolução.
- Maleta cancelada ou em rascunho mostra saldo 0 e valor em aberto 0 na leitura. A lista de "em aberto" inclui todas as maletas enviadas, mesmo com saldo 0 (maleta toda devolvida segue enviada até as Fases 4 e 7).
- Todo movimento da maleta trava primeiro a linha da remessa e depois os produtos (ordem de id): a Fase 4 (vendas) deve seguir a mesma ordem.
- Motivo de cancelamento e de estorno: até 400 caracteres.

**Dúvidas abertas:** as do ADR-0010. Nova: depois de devolver tudo, a maleta deve virar "encerrada" sozinha? Hoje continua enviada.

**Não feito:** PDF, relatórios, telas e vendas.

