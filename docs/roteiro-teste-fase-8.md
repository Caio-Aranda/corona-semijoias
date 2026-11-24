# Roteiro do teste de uso da Fase 8 (painel, resultado do período, curva ABC e conferência)

Para o **responsável** aplicar com a dona (§13, item 7, e §19 da especificação). Não é para a dona ler sozinha. O objetivo é comparar o que o sistema mostra com o que ela já usa nas **planilhas dela**, numa **semana real**, e descobrir onde o número não bate ou onde a tela atrapalha. Quem está sendo testado é o sistema, não a dona. Segue o jeito de conduzir do [roteiro do teste de uso](roteiro-teste-de-uso.md) e do [roteiro da Fase 7](roteiro-teste-fase-7.md): peça uma tarefa de cada vez, **não ajude antes de ela tentar**, peça para pensar em voz alta e anote onde hesitou, errou ou não entendeu em [PROBLEMAS-DE-USO.md](PROBLEMAS-DE-USO.md).

Este teste **não foi feito pelo sistema**: é pendência do responsável (ver [pendencias-do-responsavel.md](pendencias-do-responsavel.md)). O que o sistema já conferiu sozinho está no [relatório da Fase 8](progresso/fase-8.md) e no teste `FechamentoDaSemanaTest` (uma semana de quatro vendedores em que o painel, o resultado do período, a curva ABC e as vendas dão o mesmo vendido líquido).

**Nenhuma pergunta nova para a dona.** Se um número não bater, **não pergunte nada a ela**: anote a diferença (veja "Como registrar a diferença", no fim) e ela vira tarefa.

## Antes de começar

1. **Escolher a semana.** Uma semana real **já acertada**, com todos os acertos fechados, da qual a dona tenha a planilha pronta (de preferência uma semana com pelo menos uma devolução ou troca de cliente, uma falta no acerto e um vendedor acima e outro abaixo da meta).
2. **Os dados dessa semana têm de estar no sistema** (maletas, vendas, acertos). Se a semana foi feita só na planilha, lance antes no sistema com os dados reais do papel, ou use uma semana de teste com os mesmos números da planilha. Sem isso a comparação não vale.
3. **Cotação do dia** lançada para os dias da semana (sem ela o Dólar e o Guarani saem vazios; o Real continua certo).
4. **Pedir à dona as planilhas** da semana: o total enviado, devolvido e vendido por vendedor, a comissão e o líquido de cada acerto, o total recebido por forma, as vendas e o lucro, se ela tiver. Anote cada número ao lado do que o sistema mostrar.
5. **Antes de abrir as telas, rode a conferência**: em **Conferência de hoje**, toque em **Conferir agora**. Resultado esperado: **sem diferença**. Se houver diferença, **pare**: a base não está boa para comparar. Anote o que a tela mostrou e avise o responsável.

## Parte 1: o Painel da semana contra a planilha (Corona Gestão)

Na tela inicial, **Painel da semana**. Escolha a **semana**. Aba **Maletas** (uma linha por vendedor):

| Coluna do painel | Comparar com a planilha | O que significa |
| --- | --- | --- |
| **Enviado** | O que saiu para o vendedor (valor e peças) | Maletas da semana (nova e reposições), pelo preço da maleta |
| **Devolvido** | O que voltou à loja no acerto | Peças boas e com defeito, pelo preço da maleta |
| **Vendido** | O vendido depois de descontos, menos o que o cliente devolveu ou trocou | Vendido líquido |
| **Comissão (%)** e **Comissão** | Percentual e valor da comissão | 30% abaixo da meta, 40% na meta ou acima |
| **Valor líquido** | O valor do acerto | Vendido menos comissão, mais as faltas cobradas |

1. **Cada linha, de um vendedor.** "Procure a linha da Fulana e me diga se os números são os da sua planilha." Conferir, em cada vendedor: Enviado, Devolvido, Vendido, Comissão e Valor líquido. Anote a planilha e o sistema lado a lado.
2. **A conta das peças.** Para um vendedor já acertado: **Enviado = Vendido + Devolvido + Faltou**, em peças. Se a dona souber a contagem do papel, confira.
3. **Linha de acerto ainda não fechado** (se houver): ela aparece marcada como **prévia**. A dona entende que é "o que daria se fechasse hoje"? O aviso diz que a prévia inclui o que o vendedor ainda não acertou de semanas anteriores: ela entendeu? Anote.
4. **Aba Totais.** Conferir os blocos com os totais da planilha dela: **Novas maletas**, **Reposições**, **Acerto**, **Vendas**, **Trocas**, **Total** e **Total recebido no acerto** (Dinheiro, Transferência, Boleto). Nesta tela **não existem** entrada, cheque nem remarcadas: se a planilha dela tem essas colunas, anote em quais linhas isso pesa e **não** tente "fazer bater" (é regra decidida, ADR-0012).
5. **Abrir o registro.** Clicar num total e ver os registros que o formam (acerto, relatório de recebimentos, de vendas). Conferir que dá para ir do número ao documento.
6. **Filtros.** Filtrar por **vendedor** e por **praça**; os totais devem mudar de acordo. Conferir um vendedor contra a planilha dele.
7. **Três moedas.** Conferir que Dólar e Guarani aparecem e que **o que vale é o Real** (aviso "pela cotação do dia de cada lançamento").

## Parte 2: Resultado do período e lucro (Corona Gestão)

Em **Relatórios financeiros**, **Resultado do período**. Período: do primeiro ao último dia da semana (ou do mês, se a planilha for mensal). **O custo só aparece aqui, só para a dona.**

| Linha | Comparar com |
| --- | --- |
| **Vendas** | O total vendido da planilha (antes das devoluções) |
| **Devoluções e trocas** | O que os clientes devolveram ou trocaram |
| **Receita líquida** | Vendas menos devoluções; deve ser **o mesmo número do Vendido do painel** (somando todos os vendedores) |
| **Custo das peças vendidas** | O custo das peças que ela vendeu, pela última compra até o dia da venda |
| **Lucro bruto** | Receita líquida menos custo |
| **Comissões** | A soma das comissões dos acertos fechados |
| **Perdas** | O que faltou ou estragou, pelo custo |
| **Cobrado dos vendedores** | O que foi cobrado por faltas |
| **Resultado** | O que sobrou |

1. **Cada linha contra a planilha.** Anote as diferenças.
2. **A mesma semana, três lugares.** A **Receita líquida** do resultado, o **Vendido** somado das linhas do painel e o **total de Vendas por produto** (em **Relatórios de vendas**) têm de ser o mesmo número. Se não forem, é defeito: anote os três valores.
3. **Custo do cadastro.** A tela avisa quantas peças estão sem compra e usam o custo do cadastro. Conferir se a dona entende o aviso e se o custo dessas peças é o dela.
4. **Devolução abate o mês da venda.** Se houver devolução de venda de um mês anterior, a tela explica que o resultado daquele mês muda. Conferir se a dona entende ("A devolução abate o mês em que a venda foi feita").
5. **Lucro por produto** e **Lucro por vendedor**: a soma do lucro por produto é o **Lucro bruto**; a soma do lucro por vendedor é o lucro bruto **menos as comissões**. Conferir contra a planilha, se ela tiver lucro por peça.
6. **Clicar numa linha** do resultado e ver os registros (vendas, devoluções, comissões, lançamentos do caixa) que a formam.

## Parte 3: Vendas e Curva ABC (Corona Gestão)

1. **Relatórios de vendas** (por vendedor, produto, praça, cliente, categoria e dia). Conferir o **Vendido líquido** e o **Valor médio por venda** (vendido líquido dividido pelo número de vendas) contra a planilha. Conferir o **ranking** dos vendedores e das peças: a ordem é a que ela esperava?
2. **Vendas canceladas** (estornadas): conferir se aparecem com quem estornou e o motivo, e **fora** dos totais.
3. **Curva ABC dos produtos** (grupo de Estoque). Com a foto da peça ao lado do código. Conferir que a **classe A** (as peças que somam até 80% do vendido líquido) é mesmo o que ela considera as "campeãs" e se a classe C é mesmo o que vende pouco. A soma do **Vendido líquido** da curva é o mesmo da Parte 2.
4. **Peça sem foto** não trava nada: a linha aparece sem foto. Conferir.

## Parte 4: A conferência diária (Corona Gestão)

1. Na tela inicial, em **O que precisa da sua atenção**, ver se aparece algum aviso da conferência. Sem aviso é o normal quando a última conferência está sem diferença e tem menos de 26 horas.
2. **Conferência de hoje:** ver a **última conferência** (quando rodou, se deu diferença) e o **histórico**. A dona entende o que cada linha quer dizer? Ela sabe o que fazer se aparecer "N diferenças"? (A tela diz: nada é corrigido sozinho; a correção é feita pela tela da operação, por estorno.)
3. Tocar em **Conferir agora** duas vezes seguidas: a segunda mostra "Uma conferência já está rodando" ou um resultado novo, **nunca** duas rodadas ao mesmo tempo.
4. **Dia seguinte** (se o teste durar mais de um dia): conferir que a conferência das 03:00 rodou sozinha e ficou no histórico.

## Parte 5: o que o vendedor não pode ver (só o responsável confere)

Entrar no **Corona Vendas** com um vendedor e conferir que **não existe** nenhuma tela de painel, resultado, lucro, custo, curva ABC, auditoria nem conferência, e que ele só vê os próprios dados. No Corona Vendas, a Fase 8 só trouxe **Pedir acerto** e **Contar as sobras** (só com internet).

## Parte 6: Pedir o acerto e contar as sobras no Corona Vendas (vendedor)

1. Com internet: "Peça o acerto da sua maleta." (**Pedir acerto**, com a janela de confirmação.) Conferir que o acerto aparece na dona como **Em acerto** e que o vendedor não consegue mais vender nessa maleta.
2. **Contar as sobras:** ler o código de algumas peças, escolher **Boa** ou **Com defeito**, desfazer uma leitura. Conferir que a foto da peça aparece e que o vendedor **não tem** botão de fechar o acerto.
3. **Sem internet:** tirar a internet e tentar de novo. Conferir a mensagem em português simples ("Para pedir o acerto é preciso internet...") e que **nada fica guardado** para depois.

## Como registrar a diferença

Para cada número que não bater, anote numa tabela **antes de discutir**:

| Tela e linha | Número da planilha | Número do sistema | Diferença | Quem pode ter razão (palpite) |
| --- | --- | --- | --- | --- |

Depois, em ordem, confira as causas já conhecidas **antes de chamar de defeito**:

1. **Entrada, cheque e "remarcadas"** da planilha: não existem no sistema novo (decisão da dona, ADR-0012).
2. **Custo do cadastro** no lugar do custo da compra (peças sem compra registrada até a venda).
3. **Devolução de outro mês** que abateu o mês da venda.
4. **Prévia** (acerto ainda não fechado): o painel mostra o que daria hoje, e a prévia inclui semanas anteriores sem acerto.
5. **Cotação do dia** diferente da que ela usa (afeta só Dólar e Guarani; o Real não muda).
6. **Falta que a loja assumiu** ou cobrada depois (muda o líquido do acerto, não o vendido).
7. Lançamento da planilha que **não foi lançado no sistema** (ou o contrário).

Se nenhuma dessas causas explicar, a diferença é **defeito ou regra a rever**: registre em [PROBLEMAS-DE-USO.md](PROBLEMAS-DE-USO.md) com a tabela acima e o id do registro (clicando no total dá para chegar até ele). **Não corrija nada à mão e não mexa no dado**: a correção do sistema é por estorno, e a diferença vira tarefa da próxima rodada.

## Critério de aceite da Fase 8

- Os números do **Painel da semana** (Enviado, Devolvido, Vendido, Comissão e Valor líquido) e do **Resultado do período** batem com as planilhas da dona numa semana real, ou cada diferença tem uma das causas conhecidas acima, explicada à dona.
- A **Conferência de hoje** roda sem diferença sobre os dados reais.
- A dona acha o painel mais fácil do que a planilha para ver "como foi a semana" e entende os avisos.
