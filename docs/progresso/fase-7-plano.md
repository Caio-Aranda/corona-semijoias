# Plano da Fase 7: acerto e financeiro

Só plano: nenhuma linha de código nesta tarefa (7.1). O modelo está no [ADR-0012](../decisoes/ADR-0012-acerto-e-financeiro.md), **aceito pelo responsável em 13/11/2026 com quatro mudanças**, já aplicadas neste plano pela tarefa 7.1b:

- **não existe "entrada"** (se um dia existir, entra como tarefa nova);
- **não existe cheque**; o vendedor repassa à loja em dinheiro, por transferência (gravada como `PIX`) ou boleto, e o fornecedor é pago pelas mesmas três formas;
- **comissão por meta**: 40% se o vendido da semana chegar a 700.000 Gs, 30% abaixo, sobre o total (ADR-0012, item 5);
- as respostas da dona de 13/11/2026 estão anotadas nas [dúvidas](#dúvidas-para-a-dona).

## Regras da Fase 7 (valem para todas as tarefas)

- **Plano antes do código** (CLAUDE.md): toda tarefa de backend de acerto ou financeiro começa em **modo de plano** e com **esforço alto**, e o plano é aprovado antes de qualquer migração.
- **Testes primeiro** em dinheiro e estoque. Pronto = funcionalidade + testes + relatório + conciliação passando.
- Dinheiro em `NUMERIC(14,2)`, tudo em Real; arredondamento **só** em `Dinheiro` (HALF_UP, escala 2) e conversão só em `ConversaoDeMoeda`; o `ArredondamentoSoNoUtilitarioTest` continua valendo.
- O servidor calcula comissão, parcelas, totais e líquido; o app nunca manda esses valores.
- Tudo imutável; correção é estorno. Acerto `ACERTADO` e semana `FECHADA` nunca mudam.
- **Saída sem estoque é sempre recusada** (maleta e estoque central), também no acerto (decisão de 11/11/2026).
- UUID do cliente em todo comando: 201 na primeira gravação, 200 no reenvio igual, 409 com o mesmo id e conteúdo diferente.
- Rotas da dona só ADMIN; o vendedor só pede o acerto, bipa a contagem e lê o que é dele; 403 cruzado com teste; `npm run barreira:varrer` passa.
- Migração nova a cada tarefa (a próxima livre; em 13/11/2026 a última é a V040). Migração publicada não se edita.

## Ordem e dependências

1. **7.2** e **7.3** podem correr em paralelo (arquivos diferentes).
2. **7.4** depois da 7.3 (as duas mexem no `ServicoDeVendas`: parcelas e trava da remessa).
3. **7.5** depois da 7.4.
4. **7.6** depois da 7.5 (os recebimentos entram no fechamento).
5. **7.7** depois da 7.6 (usa o caixa).
6. **7.8** depois da 7.7 (precisa de todas as tabelas).
7. **7.9**, **7.10**, **7.11** e **7.12** depois das rotas que usam; podem correr em paralelo entre si.
8. **7.13** por último.

- **Fase 6 antes das partes que leem devolução, troca e crédito.** Se a Fase 6 ainda não estiver na `main` quando a 7.5 começar, o fechamento liga só vendas e estornos, e a tarefa que fechar a Fase 6 acrescenta os tipos `DEVOLUCAO` e `TROCA` em `acerto_eventos` por migração nova (o `CHECK` é ampliável).
- **Fase 5 (offline):** a regra de corte do ADR-0012 (item 4) já vale online; a 7.4 deixa o caminho pronto e a Fase 5 testa o caso "venda sem internet que chega durante o acerto".
- **Dúvidas que travam:** 1 (fiado) antes da 7.5; sem resposta, segue o padrão seguro. A 2 e a 4 (comissão) e a 7 (entrada) foram respondidas em 13/11/2026.

## Tarefas

| Tarefa | O que entrega | Esforço | Modo de plano |
| --- | --- | --- | --- |
| **7.1** | Este plano e o ADR-0012 (só documentos) | Alto | — |
| **7.2** | Limite de consignação com as decisões de 11/11 | Médio | Não |
| **7.3** | Contas a receber do cliente (nível 1): parcelas da venda a prazo, recebimento posterior, baixas | **Alto** | **Sim** |
| **7.4** | Acerto, parte 1: iniciar, travas da venda, contagem por leitura, desistir | **Alto** | **Sim** |
| **7.5** | Acerto, parte 2: fechamento atômico, comissão por meta, pendências, devolução ao estoque | **Alto** | **Sim** |
| **7.6** | Recebimentos do vendedor (dinheiro, transferência, boleto), contas do nível 2, caixa automático | **Alto** | **Sim** |
| **7.7** | Contas a pagar das compras, caixa manual, categorias, fechar semana | **Alto** | **Sim** |
| **7.8** | Conciliações C1 a C10 (nove; a C6, de cheque, foi retirada): consultas, testes e rota de diferenças | **Alto** | **Sim** |
| **7.9** | Corona Gestão: telas do acerto (contar, conferir, pendências, fechar, PDF) | Alto | Sim |
| **7.10** | Corona Gestão: financeiro (contas, caixa, comissões, meta e percentuais da comissão) | Médio | Não |
| **7.11** | Corona Vendas: pedir acerto, bipar sobras, parcelas na venda, meu acerto, minha comissão (com quanto falta para a meta), meus recebíveis | Médio | Não |
| **7.12** | Relatórios financeiros (§12) com exportação e três moedas | Médio | Não |
| **7.13** | Fechamento da fase: ponta a ponta, roteiro de uso, especificação atualizada (o ADR-0012 já está Aceito desde 13/11/2026) | Médio | Não |

### 7.2: limite de consignação (decisões de 11/11/2026)

- Migração: `vendedores.sem_limite boolean NOT NULL` e `CHECK (sem_limite OR limite_consignacao IS NOT NULL)`; quem hoje está sem limite recebe `sem_limite = true` (mantém o comportamento) e a dona vê na lista.
- Cadastro do vendedor: campo obrigatório com a opção "Sem limite"; a conta do envio da maleta continua a do ADR-0010 item 7 (preço congelado, reposição entra).
- Atualizar a especificação (§7.1, §9, §17) e marcar resolvidas as dúvidas 1, 2 e 6 do ADR-0010.
- Testes: vendedor sem valor e sem "Sem limite" é recusado; envio acima do limite com reposição é recusado; "Sem limite" nunca bloqueia; 403 cruzado.

### 7.3: contas a receber do cliente

- Tabelas `contas_receber` e `baixas_receber` (ADR-0012 itens 2 e 7); campos opcionais de parcelas no contrato da venda (compatível com a Fase 4 e com o sync).
- Parcelas com `Dinheiro.dividirEmParcelas`; recebimento posterior distribuído por vencimento; estorno da venda cancela as parcelas em aberto.
- Atalhos `valor_pago`, `valor_cancelado`, `status` só pelo serviço (teste de arquitetura).

### 7.4: acerto, parte 1

- Tabelas `acertos`, `leituras_acerto`, coluna `remessas.acerto_id`; transições `EM_ACERTO`/`DESISTIDO`.
- Iniciar (vendedor ou dona), bloquear venda/remessa nova na maleta em acerto, contagem por leituras idempotentes, desistir.
- Conferir que o `ServicoDeVendas` trava a remessa antes de ler o status; acrescentar a trava se faltar.

### 7.5: acerto, parte 2 (fechamento)

- Tabelas `itens_acerto`, `acerto_eventos`, `comissoes`, `pendencias_acerto`; transição `ACERTADO`; maletas `ACERTADA`.
- Fechamento em uma transação: esperado × contado, sobra impede, falta vira pendência + `PERDA`, peças boas voltam (`DEVOLUCAO_DONA` + `DEVOLUCAO`), defeito aprovado (`DEVOLUCAO` + `AVARIA`), eventos ligados, comissão por meta (abaixo), totais, cotação gravada.
- **Comissão por meta** (ADR-0012, item 5): migração com três chaves novas em `configuracoes` (meta em Guarani, padrão 700.000; percentual abaixo da meta, padrão 30; percentual a partir da meta, padrão 40), iguais para todos os vendedores, e rota da dona para mudar, com auditoria. No fechamento, o servidor soma o vendido líquido das vendas do acerto, cada venda convertida para Guarani pela cotação gravada nela e arredondada sozinha (`ConversaoDeMoeda`); soma ≥ meta → 40%, abaixo → 30%, sobre o total. Com o percentual decidido, a comissão continua por venda (`Dinheiro.aplicarPorcentagem`, uma vez por venda), com o percentual gravado na linha e no acerto. Estorno de venda de acerto já fechado usa o percentual da linha original. O `comissao_percentual` do cadastro do vendedor deixa de ser usado no cálculo (a coluna fica).
- Prévia para o vendedor: vendido da semana, percentual que valeria hoje e quanto falta para a meta, em Guarani e em Real (a tela é da 7.11).
- Nesta tarefa o fechamento grava os totais e o "ficou devendo" sem recebimentos (a 7.6 acrescenta o pagamento); o exemplo do ADR-0012 item 18 vira teste na 7.6, com a parte de comissão e totais já aqui.

### 7.6: recebimentos e caixa automático

- Tabelas `recebimentos_vendedor`, `lancamentos_caixa`, `categorias_caixa`. **Não há entrada nem cheque** (aceite de 13/11/2026): nenhuma tabela, coluna ou tela para eles.
- Recebimentos no fechamento nas três formas do repasse: `DINHEIRO`, `PIX` (na tela, "Transferência") e `BOLETO` (o boleto vira conta a receber até a dona registrar que foi pago); conta do nível 2 com o que faltou; pagamento de conta antiga no acerto; estorno de recebimento.
- Acerto a favor do vendedor: crédito no próximo acerto ou saída de caixa.

### 7.7: contas a pagar, caixa manual e semana

- Tabelas `contas_pagar`, `baixas_pagar`; gerar parcelas de uma compra; pagar (saída de caixa) em dinheiro, por transferência ou boleto; compra estornada cancela as abertas.
- Lançamento manual com categoria e estorno; aviso de caixa negativo.
- Fechar semana: recusa com acerto `EM_ACERTO`; depois de fechada, recusa lançamento, remessa ou acerto com data nela.

### 7.8: conciliações

- As nove conciliações do ADR-0012 (item 17: C1 a C10, sem a C6, que era a dos cheques) como consultas únicas, com teste de massa conhecida e um teste que **estraga** cada uma de propósito (por exemplo, `UPDATE` direto num atalho no teste) e confere que a diferença aparece.
- Rota da dona com as diferenças; a rodada diária e o aviso ficam para a Fase 8.

### 7.9 a 7.12: telas e relatórios

- Textos só em `textos.ts`, termos do glossário (acrescentar os novos: acerto, pendência, ficou devendo, meta da comissão; no repasse do vendedor, a forma `PIX` aparece como "Transferência"; o glossário já diz, desde a 7.1b, que cheque e entrada não existem e não aparecem em tela nenhuma; `CHEQUE` e `CARTAO` do pagamento do cliente continuam aceitos no banco, sem tela nova), erros em português simples dizendo o que fazer.
- Tela do acerto mostra a data da cotação gravada (ADR-0006 item 11) e explica que vale o valor em reais.
- 7.10: tela da dona para a meta e os dois percentuais da comissão; o campo de comissão do cadastro do vendedor deixa de valer (sai da tela ou fica só para consulta). 7.11: "Minha comissão" mostra a prévia com quanto falta para a meta, em Guarani e em Real.
- Relatórios com filtros, totais no rodapé, detalhamento até o registro e exportação PDF, Excel e CSV (§12). DRE e lucro ficam para a Fase 8.

## Testes obrigatórios

**Dinheiro e arredondamento**

- HALF_UP só em `Dinheiro` (e conversão só em `ConversaoDeMoeda`): o teste de guarda continua passando com o código novo.
- Comissão por venda: R$ 33,33 a 30% = 10,00 (9,999 arredonda uma vez); três vendas de R$ 10,05 a 30% = 3,02 + 3,02 + 3,02 = 9,06 (soma das arredondadas, não 9,05); R$ 10,05 a 40% = 4,02.
- Comissão por meta: soma exatamente 700.000 Gs dá 40% e 699.999 Gs dá 30%; os 40% valem sobre todas as vendas do acerto, não só sobre o que passa da meta; duas vendas com cotações diferentes são convertidas cada uma pela sua e arredondadas sozinhas; venda estornada no mesmo acerto não conta para a meta; mudar a meta ou os percentuais em `configuracoes` vale para o próximo fechamento e não muda acerto fechado; o `comissao_percentual` do cadastro do vendedor não muda o resultado; o percentual fica gravado em cada linha de comissão e no acerto.
- Prévia do vendedor: quanto falta para a meta, em Guarani e em Real; zero quando a meta já foi batida.
- Estorno proporcional de comissão pelo acumulado (exemplos do ADR-0002), inclusive devoluções pequenas repetidas que nunca invertem o sinal.
- Parcelas: R$ 100,00 em 3 = 33,33 + 33,33 + 33,34; total pequeno demais para tantas parcelas é recusado.
- Exemplo completo do ADR-0012 item 18, com todos os totais, o caixa e o estoque.
- Acerto a favor do vendedor (líquido negativo).
- Nenhum valor convertido gravado; tela do acerto pela cotação gravada, mesmo depois de chegar cotação nova do dia.

**Acerto**

- Iniciar liga todas as maletas abertas (nova e reposição) e bloqueia venda, devolução e remessa nova; desistir devolve tudo como estava, sem dinheiro gravado.
- Sobra impede o fechamento; falta vira pendência e `PERDA`; cobrar no acerto, cobrar depois e a loja assumir; peça encontrada depois.
- Defeito aprovado não aumenta o saldo vendável do estoque central; recusado volta como boa.
- Todas as maletas do acerto terminam com saldo zero; saída maior que o saldo é recusada em qualquer passo.
- Evento antigo (estorno de venda de semana fechada) entra no próximo acerto e reduz a comissão dele, pelo percentual da linha original (40% na origem continua 40% mesmo que o acerto novo fique em 30%), sem entrar na soma da meta.
- Acerto `ACERTADO`, seus itens, eventos e comissões recusam `UPDATE` e `DELETE` no banco.
- Semana fechada recusa acerto, remessa e lançamento com data nela.

**Estorno**

- Estorno de venda, de recebimento, de baixa e de lançamento de caixa: registro negativo com `estorna_id` único; estorno de estorno recusado; o original nunca muda.
- Estorno de venda cancela as parcelas em aberto e estorna a comissão (no mesmo acerto ou no próximo).
- Boleto: no fechamento vira conta a receber do vendedor, sem lançamento de caixa; o caixa só recebe quando a dona registra o pagamento.
- Forma de repasse fora de `DINHEIRO`, `PIX` e `BOLETO` (por exemplo `CHEQUE` ou `CARTAO`) é recusada na API e no banco.

**Idempotência**

- Para cada comando (iniciar, leitura, desistir, fechar, resolver pendência, recebimento, baixa, lançamento, contas a pagar): 201, 200 no reenvio igual sem efeito novo, 409 com conteúdo diferente.
- Fechar duas vezes com o mesmo id: um acerto só, um conjunto só de movimentos, comissões e lançamentos.

**Concorrência** (threads paralelas contra o PostgreSQL do Testcontainers)

- Dois fechamentos do mesmo acerto ao mesmo tempo: um fecha, o outro recebe o mesmo resultado (mesmo id) ou 409 (id diferente).
- Vender × iniciar acerto na mesma maleta: ou a venda entra antes e aparece no esperado, ou é recusada; nunca uma venda gravada numa maleta acertada.
- Dois recebimentos que juntos passam do saldo de uma parcela: só um entra.
- Fechamentos de vendedores diferentes que devolvem os mesmos produtos ao estoque central, em ordens cruzadas: sem deadlock, saldo certo.
- Uma falha no meio do fechamento (exceção forçada no teste) não deixa nada gravado.

**Conciliações**

- C1 a C10 do ADR-0012 (sem a C6) zeradas com a massa de teste completa (várias semanas, dois vendedores, um acima e outro abaixo da meta, estornos, boleto pago depois, conta paga em parcelas).
- Cada conciliação acusa a diferença quando o teste estraga um dado de propósito.

**Permissões e barreira**

- 403 cruzado em todas as rotas novas; vendedor recebe 404 para acerto, conta ou comissão de outro vendedor.
- Contrato do vendedor sem custo, caixa ou contas a pagar; `npm run barreira:varrer` passa.

## Dúvidas para a dona

Cada uma tem o padrão seguro que o sistema usa até a resposta. Português simples; outra sessão traduz para o espanhol. **Em 13/11/2026 a dona respondeu a 2, a 4, a 7, a 11, a 14 e a 20** (marcadas abaixo); as outras continuam com o padrão seguro.

1. **Quando o cliente compra fiado e não paga, quem perde: o vendedor ou a loja?** Padrão: o vendedor. No acerto ele paga tudo o que vendeu, tendo recebido ou não; cobrar o cliente é com ele.
2. ~~**A comissão é calculada sobre o quê?**~~ **Respondida em 13/11/2026:** sobre o valor da venda já com desconto, igual para todas as peças e para todos os vendedores, **por meta**: se o vendedor vendeu 700.000 Gs ou mais na semana, a comissão é de 40% sobre tudo; abaixo disso, 30%. Não muda por categoria de peça.
3. **O vendedor tira a comissão do dinheiro no acerto, ou entrega tudo e a loja paga a comissão depois?** Padrão: tira no acerto.
4. ~~**Se a senhora mudar o percentual de comissão de um vendedor no meio da semana, qual vale?**~~ **Resolvida em 13/11/2026:** não existe mais percentual por vendedor. A meta (700.000 Gs) e os dois percentuais (30% e 40%) são os mesmos para todos e a senhora pode mudar; vale o que estiver gravado na hora do acerto, e acerto já fechado não muda.
5. **Peça que falta no acerto: cobra do vendedor por qual valor?** Padrão: pelo preço de venda da maleta, e a senhora decide em cada caso se cobra, se cobra depois ou se a loja assume.
6. **No acerto, tudo o que sobrou volta para a loja, ou o vendedor pode ficar com as peças para a semana seguinte?** Padrão: volta tudo, e a senhora monta uma maleta nova.
7. ~~**O que é a "entrada"?**~~ **Resolvida em 13/11/2026:** no sistema novo **não existe entrada**. O vendedor não paga nada ao levar a maleta. Se um dia existir, entra como tarefa nova.
8. **O vendedor pode ficar devendo parte do acerto? Pode dividir?** Padrão: pode; o que faltou vira uma conta dele, com a data que a senhora escolher, e aparece no próximo acerto.
9. **Vendedor com dívida pode levar maleta nova? A dívida entra no limite de consignação?** Padrão: pode e não entra no limite, mas aparece um aviso ao montar a maleta.
10. **Cobra multa ou juros de conta atrasada?** Padrão: não; o sistema só mostra o que está atrasado.
11. ~~**Aceita cheque de outra pessoa (do cliente do vendedor)? Cheque que voltou pode ser depositado de novo?**~~ **Resolvida em 13/11/2026:** no sistema novo **não existe cheque**. O vendedor paga o acerto em dinheiro, por transferência ou boleto; o fornecedor é pago do mesmo jeito.
12. **O boleto é feito por quem, e quando conta como pago?** Padrão: fora do sistema; conta como pago só quando a senhora registra o pagamento.
13. **O caixa é um só ou separado (dinheiro na gaveta e banco)? Pode ficar negativo?** Padrão: um só, com os relatórios separados por forma; pode ficar negativo, com aviso.
14. ~~**O cliente paga com cartão? E o vendedor pode pagar o acerto com cartão?**~~ **Respondida em 13/11/2026:** não. O cliente paga em dinheiro ou por transferência (tarefa 6.13), e o acerto não aceita cartão.
15. **As compras de fornecedor são pagas em parcelas?** Padrão: a senhora informa quantas parcelas e a primeira data; o sistema divide o valor.
16. **Quando o acerto dá a favor do vendedor (muitas devoluções), a loja paga na hora ou desconta no próximo?** Padrão: desconta no próximo; pagar na hora também é possível.
17. **Venda a prazo: parcelas mensais ou semanais? Tem valor mínimo de parcela?** Padrão: mensais, sem valor mínimo.
18. **A senhora dá desconto ao vendedor no acerto?** Padrão: não existe desconto livre; correção é por estorno ou pendência.
19. **Peça com defeito trazida no acerto é cobrada do vendedor?** Padrão: não; a senhora confirma o defeito e a peça sai do estoque como avaria.
20. **Quem fecha a semana e quando?** **Respondida em parte em 13/11/2026:** a semana vai de segunda a domingo, e o acerto acontece nos 3 a 4 dias seguintes. Esse prazo é **só informativo**: o sistema mostra, mas não bloqueia nem cobra nada por atraso. Continua no padrão: quem fecha a semana é a senhora, depois de todos os acertos; semana fechada não muda mais.
