# ADR-0008: Estoque e movimentações

- **Status:** Aceito
- **Data:** 10/11/2026
- **Tarefas que implementam:** 2.2 a 2.5 (backend) da Fase 2; a Fase 3 (maleta) reutiliza o mesmo serviço

## Contexto

A §7.6 manda registrar cada movimentação (`ENTRADA`, `SAIDA`, `AJUSTE`, `REMESSA`, `DEVOLUCAO`, `AVARIA`), com o saldo como soma e a coluna `estoque_atual` mantida na mesma transação. A §13 manda dado imutável (corrigir é estornar) e conciliação automática. A migração V014 já criou `produtos.estoque_atual` e `estoque_minimo`. Antes da primeira migração de movimentações é preciso fixar o modelo, o estorno, a concorrência, o inventário e o saldo negativo, porque a Fase 3 (maleta) e a Fase 5 (sync) dependem disso e migração publicada não se edita.

## Decisão

1. **Um registro por movimento, imutável.** Tabela `movimentacoes_estoque`: `id` (UUID do cliente), `produto_id`, `tipo`, `quantidade` (inteiro **com sinal**, diferente de zero), `origem_tipo` e `origem_id` (compra, inventário, maleta, manual), `motivo`, `usuario_id`, `data` (UTC), `estorna_id` e os campos de controle. Nunca recebe `UPDATE` de conteúdo nem `DELETE`; o `deleted_at` existe pela convenção, mas **não é usado** (a tabela entra no teste de convenções como imutável).
2. **Sinal pelo tipo.** `ENTRADA` e `DEVOLUCAO` somam; `SAIDA`, `REMESSA` e `AVARIA` subtraem; `AJUSTE` pode ser positivo ou negativo. Um `CHECK` confere o sinal de cada tipo (exceto no estorno). O servidor monta o sinal: o app manda só o tipo e a quantidade positiva (no ajuste, o novo valor ou a diferença).
3. **Saldo é a soma dos movimentos**, e `produtos.estoque_atual` é só o atalho rápido. **Só o serviço de movimentações altera `estoque_atual`, na mesma transação do movimento.** Nenhum `UPDATE` direto em lugar nenhum do código; um teste de arquitetura e a conciliação cuidam disso.
4. **Saldo nunca fica negativo.** O serviço **recusa** (409, "Não há peças suficientes no estoque") toda saída, avaria, remessa, ajuste ou estorno que deixaria o saldo abaixo de zero, e há um `CHECK (estoque_atual >= 0)` no banco como segunda trava. Se a contagem real é menor que o sistema, a dona faz um **ajuste** (positivo ou negativo) com motivo, que também não passa de zero. Não existe "saldo negativo temporário".
5. **Estorno é um movimento novo.** Tem o **mesmo tipo** do original, quantidade de **sinal oposto** e `estorna_id` apontando para o original. Índice único em `estorna_id`: um movimento só é estornado uma vez. Estorno de estorno é recusado (a dona lança de novo). O estorno exige motivo e respeita a regra do saldo (item 4).
6. **Idempotência pelo UUID do cliente.** O `id` do movimento (e da compra, e do inventário) vem do app. Reenviar o mesmo `id` com o mesmo conteúdo devolve o registro já gravado, sem somar de novo; o mesmo `id` com conteúdo diferente é 409. O servidor recalcula tudo (sinal, saldo, totais, custo) e nunca confia no app.
7. **Concorrência.** Toda alteração de estoque acontece numa transação que **bloqueia a linha do produto** (`SELECT ... FOR UPDATE`), confere o saldo e grava o movimento e o novo `estoque_atual`. Quando uma operação mexe em vários produtos (compra, inventário, maleta), o serviço **ordena os produtos por `id` e bloqueia nessa ordem**, para duas operações ao mesmo tempo nunca se esperarem em volta (deadlock). Os testes da 2.2 rodam operações paralelas sobre o mesmo produto e sobre conjuntos que se cruzam.
8. **Inventário.** `inventarios` + `itens_inventario`. Ao **iniciar**, o servidor **congela** o `esperado` de cada produto do escopo (todos, ou por categoria ou fornecedor). A contagem grava `contado` por leitura. Ao **fechar**, o servidor gera **um `AJUSTE` por produto com diferença**, de valor `contado − esperado congelado`, com motivo "Contagem do estoque" e `origem` no inventário. Assim, entradas e saídas feitas durante a contagem **não são desfeitas** pelo fechamento. Produto do escopo que não foi lido aparece como diferença e só vira ajuste (contado = 0) se a dona confirmar. Inventário fechado é imutável; correção é um novo ajuste.
9. **Conciliação.** Para cada produto, `soma(movimentos) = estoque_atual`. Há um teste e um relatório (diferenças encontradas, zero em condições normais). A conciliação "estoque central + maletas = comprado − vendido − perdido" (§13) completa-se na Fase 8, quando existirem maletas e vendas.
10. **Fase 3 (maleta).** Montar a maleta chama o **mesmo serviço** com `REMESSA` (uma operação com vários produtos, ordem estável, tudo ou nada) e a devolução do vendedor no acerto chama `DEVOLUCAO`. O tipo, o sinal e a origem já existem desde a 2.2; a Fase 3 só adiciona `origem_tipo = MALETA`. O saldo da maleta é uma segunda soma, de outra tabela, mas nunca altera o estoque central por fora do serviço.
11. **O vendedor não recebe estoque central.** `estoque_atual`, `estoque_minimo`, movimentos, compras e inventários são **só do servidor** (§4, §10). A 2.2 confere que `estoque_atual` e `estoque_minimo` não estão no contrato do pull de produtos. Custo continua só em `produtos_custos` (ADR-0007 e §9).
12. **Auditoria.** Ajuste, estorno e fechamento de inventário entram na auditoria (§11) com usuário, motivo e valores antes e depois.

## Consequências

### Boas
- Qualquer saldo é explicável e reconstruível pelos movimentos; a conciliação pega erro de programação.
- Reenvio e duas pessoas ao mesmo tempo não duplicam nem estragam o saldo.
- A Fase 3 não precisa de migração nem de regra nova no estoque.

### Ruins ou custos
- Recusar saldo negativo obriga a dona a lançar a compra antes de enviar a maleta; se a peça chegou e ainda não foi lançada, ela faz primeiro a entrada. É o preço de um número confiável.
- Bloqueio de linha serializa operações no mesmo produto (aceitável: loja única, poucos usuários).
- O fechamento do inventário usa o esperado congelado: se alguém lança entrada de uma peça já contada, o saldo final é a soma dos dois efeitos, o que é o certo, mas a tela precisa explicar.

## Alternativas consideradas

- **Permitir saldo negativo e só alertar:** descartada; esconde erro de lançamento e quebra a conciliação.
- **Estorno com tipo próprio (`ESTORNO`):** descartada; os seis tipos da §7.6 ficam fixos e o relatório por tipo continua útil; `estorna_id` já marca o estorno.
- **Calcular o saldo só pela soma, sem `estoque_atual`:** descartada; a §7.6 manda a coluna por rapidez nas listas e na leitura.
- **Fechar o inventário gravando o contado direto no saldo:** descartada; apagaria as movimentações feitas durante a contagem e não deixa rastro.
- **Bloqueio otimista (versão) em vez de `FOR UPDATE`:** descartada; com várias linhas por operação gera muita repetição; o bloqueio ordenado é simples e sem deadlock.

## Seções da especificação relacionadas

§4, §7.6, §9 (Estoque e compras), §10, §11, §12, §13, §15 (Fase 2 e Fase 3).
