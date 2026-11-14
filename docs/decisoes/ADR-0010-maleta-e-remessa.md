# ADR-0010: Maleta e remessa

- **Status:** Aceito (fechamento da Fase 3, tarefa 3.8). As dúvidas 1, 2 e 6 (limite de consignação) foram **resolvidas pelo responsável em 11/11/2026**; as dúvidas 3 a 5 seguem abertas para a dona e valem com os padrões provisórios descritos abaixo
- **Data:** 10/11/2026
- **Tarefas que implementam:** 3.2 a 3.7 (Fase 3); estados seguintes nas Fases 4 e 7

## Contexto

A §7.1 define a maleta (remessa): a dona monta online, bipando as peças; o estoque central baixa ao enviar; o preço fica congelado; há limite de consignação, restrição e saldo central como bloqueios. A §9 já nomeia as tabelas `remessas`, `itens_remessa` e `movimentos_remessa`. O ADR-0008 fixou que a maleta usa o mesmo serviço de estoque (`REMESSA` e `DEVOLUCAO`). Falta decidir estados, limite, reposição, idempotência, conciliação e nota em PDF antes da primeira migração da maleta (migração publicada não se edita). O número 0009 é da Fase 9.

## Decisão

1. **Tabelas (nomes da §9).** `remessas` (vendedor, semana, praça, tipo `NOVA` ou `REPOSICAO`, status, `dispositivo_id` vazio nesta fase, totais calculados pelo servidor, motivo do cancelamento), `itens_remessa` (remessa, produto, `qtd_enviada`, `preco_remessa`) e `movimentos_remessa` (UUID do cliente como chave, remessa, produto, tipo, quantidade, `venda_id`, `dispositivo_id`, data). Migração: V021 ou a próxima livre na hora da tarefa 3.2.
2. **Estados que a Fase 3 implementa:** `RASCUNHO`, `ENVIADA` e `CANCELADA`. `CONFERIDA` (Fase 4), `EM_ACERTO` e `ACERTADA` (Fase 7) ficam no `CHECK` do banco, mas nenhuma rota da Fase 3 chega a eles. Transições da Fase 3: `RASCUNHO → ENVIADA`, `RASCUNHO → CANCELADA` e `ENVIADA → CANCELADA` (estorno, só se nada foi conferido, vendido ou devolvido).
3. **Rascunho não mexe no estoque.** Só o envio (`ENVIADA`) baixa o estoque central, **numa única transação**: uma operação com todos os produtos, ordem estável por `id`, tudo ou nada (ADR-0008, itens 7 e 10), com `REMESSA` e `origem_tipo = MALETA`. Saldo central insuficiente recusa a remessa inteira ("Não há peças suficientes no estoque"), dizendo qual peça (ADR-0008, item 4). Rascunho pode ser editado e excluído (exclusão lógica).
4. **Devolução à dona e cancelamento.** Peça que volta da maleta é o movimento `DEVOLUCAO_DONA` em `movimentos_remessa` mais `DEVOLUCAO` no estoque central, na mesma transação. Cancelar uma remessa enviada é **estorno** dos movimentos (mesmo tipo, sinal oposto, `estorna_id`), nunca apagar. O saldo da maleta não passa de zero por item.
5. **Imutável.** `movimentos_remessa` e os itens de uma remessa enviada nunca recebem `UPDATE` de conteúdo nem `DELETE`. Correção é estorno. O saldo da maleta é `qtd_enviada` mais a soma dos movimentos (fórmula da §7.1), nunca um número digitado.
6. **Preço congelado.** `preco_remessa` copia o preço de venda **vigente no momento do envio** (não no rascunho). Mudar o preço do produto depois não altera maletas enviadas. O servidor calcula; o app manda só produto e quantidade.
7. **Limite de consignação.** Valor em aberto do vendedor = soma, sobre as maletas `ENVIADA` ainda não acertadas, de `saldo atual × preco_remessa` (a **preço de venda**, não de custo) **mais** o valor da remessa que está sendo enviada. Se passar de `limite_consignacao`, o envio é recusado, mostrando o limite, o valor em aberto e quanto falta. **A reposição entra na mesma conta** (é mercadoria a mais com o vendedor); como o que foi vendido ou devolvido sai do saldo, repor o vendido costuma caber. ~~Vendedor sem limite cadastrado não é bloqueado pelo limite.~~ **Corrigido em 11/11/2026 (decisão do responsável; migração V026):** o limite é um campo **obrigatório** do cadastro, em reais (a preço de venda congelado; zero vale zero), com a opção explícita **"Sem limite"** (`limite_consignacao_ilimitado`; os dois ao mesmo tempo o banco recusa). Vendedor **sem limite definido** (nenhuma das duas escolhas; é como ficaram os cadastros antigos com limite 0) **não recebe maleta**: rascunho e envio são recusados com 409 `LIMITE_DE_CONSIGNACAO_NAO_DEFINIDO` ("Defina o limite deste vendedor antes de montar a maleta"). ~~Tudo isso é provisório até a dona confirmar (dúvidas 1, 2 e 6).~~ **Definitivo (decisões do responsável de 11/11/2026, registradas no ADR-0012 e conferidas na tarefa 7.2):** o limite conta o preço de venda congelado (`preco_remessa`), a reposição entra e o campo é obrigatório com "Sem limite". As dúvidas 1, 2 e 6 estão resolvidas.
8. **Restrição e inativo bloqueiam.** Vendedor inativo, ou com restrição marcada "Impede montar maleta" (glossário, tarefa 1.2), não recebe remessa. A regra vale na criação do rascunho e no envio, validada na API. Restrição que não impede só aparece como aviso.
9. **Idempotência por UUID do cliente.** O `id` da remessa e dos movimentos vem do app. Reenviar o mesmo `id` com o mesmo conteúdo devolve o registro já gravado (**200**, sem mexer de novo no estoque); a primeira gravação é **201**; o mesmo `id` com conteúdo diferente é 409. A ponte do app já mostra o aviso de reenvio (tarefa 2.9).
10. **Um dispositivo por maleta fica para a Fase 4.** O campo `dispositivo_id` existe, mas só é preenchido na conferência.
11. **Rotas só do perfil ADMIN (dona).** O vendedor não cria nem altera remessa nesta fase. A leitura da própria maleta pelo vendedor entra na Fase 4, **sem preço de custo e sem dados de outros vendedores**; `produtos_custos` e o estoque central nunca entram no contrato do vendedor (ADR-0008, item 11).
12. **Conciliação.** Para cada produto: `estoque central + soma dos saldos das maletas = entradas − saídas − avarias ± ajustes − vendas − perdas` (compras, ajustes e inventário no estoque; vendas e perdas nos movimentos da maleta). Na Fase 3, sem vendas, vale `estoque central + saldo nas maletas = entradas − saídas manuais − avarias ± ajustes`. Teste automático e relatório de diferenças (zero em condições normais), completando o ADR-0008, item 9. A parte de vendas entra nas Fases 4 e 8.
13. **Nota de remessa em PDF.** Os PDFs das tarefas 2.5 e 2.8 são gerados **no app da dona**, à mão, sem biblioteca, com os dados vindos da API. A nota segue o mesmo padrão: o **servidor devolve os dados completos e congelados da nota** (loja, vendedor, semana, praça, itens, quantidades, preços, totais, cotação do dia do envio) e o Corona Gestão gera o PDF no gerador da 2.8, reaproveitando cabeçalho e exportação sem segunda biblioteca. Se na 3.4 o gerador à mão não aguentar a nota (várias páginas, linha de assinatura), a tarefa registra a troca de biblioteca em ADR curto. O PDF sai em **Real**, com Dólar e Guarani pela cotação do dia do envio quando houver cotação. Miniaturas de foto: opcionais, só se couberem (dúvida 4).
14. **Três moedas só na tela e no PDF (ADR-0006).** Tudo é gravado em Real; Dólar e Guarani não são gravados.
15. **Relatórios da maleta** (tarefa 3.4): remessas por vendedor e semana, saldo atual por maleta, mercadoria em maletas por vendedor (a que a Fase 2 deixou), maletas em aberto e atrasadas (prazo na dúvida 5) e reposições, com filtros, totais, detalhamento e exportação do padrão da §12.
16. **Foto e leitor.** A montagem usa o leitor e mostra a foto grande da peça lida (§21); falta de foto nunca trava. Código desconhecido na montagem não cadastra peça (diferente da compra): mostra "Peça não encontrada".

## Consequências

### Boas
- Estoque e maleta sempre explicáveis por movimentos; estorno em vez de apagar.
- Reenviar a remessa não baixa estoque duas vezes.
- A Fase 4 reaproveita as tabelas sem mudar a estrutura (só preenche `dispositivo_id`).

### Ruins ou custos
- Recusar saldo central insuficiente obriga a lançar a compra antes de montar a maleta (já aceito no ADR-0008).
- O limite a preço de venda pode bloquear uma remessa que o custo deixaria passar.
- O PDF depende do app da dona; aceitável, pois só a dona imprime a nota.

## Alternativas consideradas

- **Rascunho já reservando estoque:** descartada; esconde peça de outras remessas e complica a conciliação.
- **Limite medido pelo custo:** descartada como padrão; o custo é só da dona e a §7.1 fala em "valor da maleta". Fica como dúvida.
- **Reposição fora do limite:** descartada como padrão; deixaria o vendedor passar do limite com várias reposições.
- **PDF gerado no servidor com biblioteca:** adiada; nova dependência e segundo gerador. Reavaliar na 3.4.
- **Estado único `ENVIADA`, sem `RASCUNHO`:** descartada; a §7.1 prevê o rascunho e a montagem leva tempo.

## Seções da especificação relacionadas

§4, §7.1, §7.6, §9 (Maleta), §10, §12 (Estoque e Maletas), §13, §15 (Fase 3), §21.

## Dúvidas

Para a dona ou o responsável (nenhuma muda a especificação sozinha):

1. ~~O limite de consignação conta o **preço de venda** ou o **custo** das peças?~~ **Resolvida em 11/11/2026 (responsável):** o preço de venda congelado na remessa (`preco_remessa`), nunca o custo (item 7).
2. ~~A **reposição** conta no limite ou fica de fora?~~ **Resolvida em 11/11/2026 (responsável):** entra na conta; conta tudo o que o vendedor tem em mãos (item 7).
3. A mesma peça pode aparecer **duas vezes** na mesma maleta ou só uma linha com a quantidade somada? (Provisório: uma linha por produto e remessa.)
4. A nota de remessa leva **foto** dos itens? (§21, pergunta 4.)
5. Depois de quantos dias uma maleta enviada conta como **atrasada**?
6. ~~Vendedor sem limite cadastrado: recebe maleta sem limite ou fica bloqueado?~~ **Resolvida em 11/11/2026 (responsável; migração V026):** o limite é obrigatório, com a opção explícita "Sem limite"; zero vale zero; quem ficou com o limite não definido não recebe maleta até a dona escolher (item 7).
