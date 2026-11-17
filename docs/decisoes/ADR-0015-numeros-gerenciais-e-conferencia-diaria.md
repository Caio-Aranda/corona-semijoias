# ADR-0015: Números gerenciais (painel, curva ABC, DRE, lucro) e conferência diária

- **Status:** Proposto (tarefa 8.1, 15/11/2026). Aguarda o responsável. Até a decisão, as tarefas da Fase 8 seguem os padrões seguros abaixo, que **não mudam nenhuma regra de negócio** já aceita.
- **Data:** 15/11/2026
- **Tarefas que implementam:** 8.3 a 8.8 (Fase 8; plano em [fase-8-plano.md](../progresso/fase-8-plano.md))

## Contexto

A Fase 8 (§15) entrega o gerenciamento semanal da tela de referência (§2), a curva ABC (§12, Estoque), o DRE simplificado e o lucro por produto e por vendedor (§12, Financeiro), as conciliações automáticas diárias com alerta (§13, item 3) e os relatórios de controle (§12, Controle). Já estão decididos:

- dinheiro em `NUMERIC(14,2)`, tudo em Real, arredondamento só em `Dinheiro` e conversão só em `ConversaoDeMoeda` (ADR-0006); Dólar e Guarani nunca entram em conferência (§13);
- relatório lê consulta validada no servidor, com uma fórmula só (§13, item 2), e o motor de relatórios das Fases 1 a 7 (`MotorDeRelatorio`, filtros, rodapé, detalhamento, PDF, Excel e CSV);
- o acerto grava seus totais, comissões com o percentual na linha e a cotação (ADR-0012); acerto `ACERTADO` e semana `FECHADA` nunca mudam;
- o custo é só do servidor e só da dona (`produtos.preco_custo`, `itens_compra.custo_unitario`, `historico_custo`, imutável); **a venda não grava custo** (`itens_venda`, V022, é sincronizável e vai ao Corona Vendas);
- os avisos da dona são calculados na hora (`FonteDeAvisos`), nunca gravados; as nove conciliações (`ConciliacoesDaFase7`), a conferência do estoque (ADR-0008, item 9) e a conferência da devolução e troca (tarefa 6.10) são só leitura e não guardam resultado.

Ficam abertos cinco pontos que mudam tabela, fórmula ou número mostrado à dona, e por isso precisam ser escritos antes do código.

## Decisão (proposta)

1. **Custo da peça vendida (para o DRE, o lucro e a curva ABC por lucro).** O custo de cada linha vendida é o **último custo do `historico_custo` daquele produto com data até a data da venda** (em Brasília); sem histórico até essa data, o `preco_custo` do cadastro hoje, e a linha sai marcada "custo do cadastro" no relatório. Custo da linha = custo unitário × quantidade (produto de `NUMERIC(14,2)` por inteiro: exato, sem arredondamento). É **calculado na leitura**, nunca gravado: nada muda no caminho da venda, do push nem do pull, e o vendedor continua sem receber custo. Como o histórico é imutável, o número de uma semana fechada não muda depois, **exceto** nas linhas marcadas "custo do cadastro" (que seguem o cadastro); o relatório diz isso no cabeçalho.
2. **DRE simplificado (só a dona, em Real, por período, regime de competência).** Linhas, cada uma com detalhamento até o registro:
   - **Vendas** (total depois do desconto das vendas `CONCLUIDA`, pela data da venda; venda estornada fora);
   - (−) **Devoluções** (valor devolvido das devoluções ativas, pela data da devolução);
   - = **Receita líquida**;
   - (−) **Custo das peças vendidas** (item 1, das vendas menos o das peças devolvidas, que voltam ao estoque);
   - = **Lucro bruto**;
   - (−) **Comissões** (linhas de comissão e seus estornos, pela data do fechamento do acerto);
   - (−) **Perdas**: faltas do acerto e avarias, **pelo custo** (item 1, na data do movimento), e diferenças de inventário para menos;
   - (+) **Faltas cobradas do vendedor** (pendência `FALTA` decidida como cobrar, pelo valor cobrado);
   - (+/−) **Outras entradas e despesas da loja**: lançamentos **manuais** do caixa, por categoria, pela data do lançamento (o caixa automático do acerto e o pagamento a fornecedor **não** entram: são recebimento de venda já contada e compra de estoque);
   - = **Resultado**.
   Reembolso em dinheiro, crédito do cliente e parcelas não entram (são forma de pagamento, não resultado). Totais por soma no banco em `NUMERIC`; Dólar e Guarani só na tela, pela cotação do dia de cada lançamento (§12).
3. **Lucro por produto e por vendedor.** Receita líquida − custo das peças vendidas (item 1), por produto e por vendedor; por vendedor também **− comissão**. Mesma consulta do DRE (uma fórmula só); a soma das linhas é igual às linhas correspondentes do DRE no mesmo período (teste obrigatório).
4. **Curva ABC.** Por produto, pelo **vendido líquido** no período (vendas menos devoluções, em Real), ordenado do maior para o menor; classe **A** até 80% do acumulado, **B** até 95%, **C** o resto (a peça que cruza a fronteira fica na classe de cima). Empate pelo código da peça. Sem venda no período: fora da curva (o relatório "Produtos parados" já mostra). Percentuais com `Dinheiro` (HALF_UP).
5. **Painel semanal (gerenciamento da tela de referência).** Só leitura, só a dona, por semana. Uma linha por **vendedor na semana** (o acerto dele, ou as maletas em aberto se ainda não houve acerto), com as maletas listadas em "Nota". Definições (resolvem a "premissa a confirmar" da §2, sem mudar regra):
   - **Enviado (DX):** soma, pelo preço congelado, do que foi enviado nas maletas da linha (nova e reposições);
   - **Devolvido (DV):** o que voltou à loja no acerto (peças boas e com defeito), pelo preço da maleta;
   - **Vendido (VD):** vendido líquido (o do acerto quando `ACERTADO`; antes, o das vendas ativas até agora);
   - **Comissão %**, **Comissão** e **Valor líquido:** os gravados no acerto `ACERTADO`; antes do acerto, a **prévia** pelo mesmo cálculo do fechamento (ADR-0012, item 5), marcada "prévia" — é a "comissão a pagar prevista" que faltava na Fase 7;
   - **Situação:** a da maleta (ou do acerto).
   A aba **Totais** reproduz os blocos da referência **sem entrada, sem cheque e sem "remarcadas"** (não existem no sistema novo): Novas maletas, Reposições, Acerto (devolvidos, vendidos, comissão, líquido, pendências pagas e em aberto), Vendas (quantidade, total, descontos, líquido), Trocas (trocas e créditos em aberto e usados), Total e Total recebido no acerto (Dinheiro, Transferência, Boleto). Cada bloco sai das consultas já validadas (acerto, comissão, recebimentos, créditos); nenhum total novo é gravado. Identidade conferida por teste: para cada maleta acertada, enviado = vendido + devolvido + falta, em peças.
   **Como ficou (tarefa 8.7, sem migração; só a consulta, a tela é da 8.8).** `GET /api/dona/painel-semanal?semana=&vendedor=&praca=&situacao=`, só ADMIN, só leitura. Feito como descrito, com estes acertos:
   - **Quem entra na semana:** o acerto `ACERTADO` ou `EM_ACERTO` com aquela semana, e o vendedor com maleta enviada daquela semana ainda sem acerto (linha `EM_ABERTO`). No máximo **duas linhas por vendedor** na semana (uma acertada e uma em aberto), sem misturar número gravado com prévia. Nota da linha: as maletas do acerto, ou todas as maletas abertas do vendedor (`ENVIADA`, `CONFERIDA`).
   - **Número de cada linha:** acertada = os gravados no acerto (os mesmos do relatório de acertos da 7.12, pela mesma coluna); em acerto = a prévia do fechamento da dona (com as faltas contadas cobradas, o padrão dela); em aberto = a prévia da comissão da 7.5, com **líquido previsto sem faltas** (ainda não contou). O vendido da linha **não** sai do `VendidoLiquido`: ele só alimenta o bloco Vendas (a linha tem de bater com o acerto e com a prévia, que contam por eventos).
   - **A prévia é "de hoje", não "da semana":** ela inclui o que o vendedor ainda não acertou de semanas anteriores (o fechamento junta todos os eventos sem acerto). A resposta traz um campo que diz isso (`previaIncluiSemanasAnteriores` e o texto `avisoDaPrevia`), para a tela explicar à dona.
   - **Sem cotação**, a rota não cai: as linhas acertadas saem normais e as de prévia saem sem comissão e sem líquido, marcadas "sem cotação".
   - **Filtros:** semana (padrão: a que contém hoje em Brasília; sem ela, 400 "Escolha a semana."), vendedor, praça (praça do vendedor nas linhas; filtro de praça de cada relatório nos blocos, com `pracaId` opcional acrescentado onde faltava) e situação (só filtra as linhas e o bloco Prévia; os demais blocos não mudam).
   - **Blocos:** cada um é o rodapé do relatório que já existe, chamado com os mesmos filtros (o motor soma no banco); o bloco Prévia soma as linhas em prévia com `Dinheiro.somar` e o bloco Total soma dois rodapés. **Descontos do bloco Vendas:** soma de `itens_venda.desconto` na mesma fonte do vendido líquido. Cada bloco devolve a rota e os filtros do relatório de origem, para a 8.8 abrir os registros sem rota nova.
   - **Datas:** Recebido e Vendas contam pelos dias da semana; o bloco Acerto, pela semana do acerto. Um acerto da semana fechado depois do fim dela tem o recebimento fora do bloco Recebido.
   - **Recebido no acerto não tem Boleto de verdade:** no fechamento o boleto vira conta a receber (só o boleto pago, depois, é recebimento avulso). O bloco mostra a linha Boleto, que fica zerada no momento "no acerto".

6. **Conferência diária (rodada das conciliações com aviso).** Uma vez por dia (03:00 de Brasília, depois da limpeza técnica) e quando a dona pede ("Conferir agora", comando com UUID), o servidor roda **todas** as conferências de produção (estoque da ADR-0008, devolução e troca da 6.10, C1 a C10 sem a C6, e as novas da 8.8) num retrato só (`REPEATABLE READ`, só leitura) e **guarda o resultado** em duas tabelas só do servidor, imutáveis (só `INSERT`): `rodadas_conferencia` (quando, quem pediu ou "automática", situação `SEM_DIFERENCA`, `COM_DIFERENCA` ou `FALHOU`, duração) e `diferencas_conferencia` (rodada, chave, tipo e id do registro, esperado e encontrado em texto). Uma rodada por vez (`pg_try_advisory_xact_lock`; a segunda pedida enquanto uma roda recebe o resultado da que está rodando). O aviso em "O que precisa da sua atenção" é calculado na hora a partir da **última rodada** (uma nova `FonteDeAvisos`): com diferença, "A conferência de hoje achou N diferenças"; falhou ou não rodou nas últimas 26 horas, "A conferência de hoje não rodou". Nada é corrigido sozinho (correção continua sendo estorno). Retenção: guardar tudo (volume pequeno: uma linha por dia mais as diferenças, que são zero em condições normais).

   **Como ficou (tarefa 8.4, V047).** Foi feito como descrito, com estes acertos e **uma divergência**:
   - **Divergência (409).** A segunda rodada pedida enquanto outra roda **não** recebe o resultado da que está rodando: recebe **409** ("Uma conferência já está rodando. Espere um pouco e atualize a tela.") e nada é gravado por ela. Esperar a outra exigiria segurar a requisição e produziria outro retrato; o 409 é o padrão do resto da API (201, 200, 409) e a tela mostra o resultado ao atualizar. A agenda, ao dar de cara com uma rodada em andamento, só registra no log e tenta de novo no dia seguinte.
   - **Chaves.** Treze conferências, cada uma com a sua chave (`ESTOQUE`, `DEVOLUCAO_E_TROCA`, `C1` a `C5` e `C7` a `C12`), para o erro de uma não derrubar as outras. A falha guarda **só a chave** (o erro técnico vai para o log, nunca para a tela). Cada uma chama a classe de produção que já existia; nenhuma fórmula mudou.
   - **Ponto de salvamento.** Cada conferência roda dentro de um `SAVEPOINT`; se der erro, a rodada volta ao ponto e segue. Os três métodos de leitura que a rodada chama ganharam `noRollbackFor`, senão um erro marcaria a transação de fora como "só desfazer" e a rodada inteira se perderia.
   - **Tabelas.** `rodadas_conferencia` (origem `AUTOMATICA` ou `PEDIDA`, quem pediu, início e fim, situação, chaves conferidas e com falha, total) e `diferencas_conferencia` (chave, item, tipo, referência em texto, descrição, esperado e encontrado em texto exato, unidade, ordem). Imutáveis pelas convenções do servidor (o banco recusa `UPDATE`, `DELETE` e `TRUNCATE`); fora do sync. Só o `ServicoDaConferenciaDiaria` escreve nelas (teste de arquitetura).
   - **Aviso.** `CONFERENCIA_COM_DIFERENCA` e `CONFERENCIA_NAO_RODOU`; "nenhuma rodada ainda" também avisa, até a primeira rodada (a dona pode tocar em **Conferir agora**). 03:00 de Brasília e 26 horas são o padrão R2.

   **Como ficou (tarefa 8.6, sem migração).** O DRE e o lucro foram feitos como descrito, com estes acertos e **uma divergência** (decidida pela orquestradora no plano aprovado do PR #197):
   - **Divergência: a devolução abate o mês da venda, não o da devolução.** O item 2 diz "pela data da devolução". Foi feito **pela data da venda**, igual à tarefa 8.5. Motivo: o DRE, o lucro por produto, a curva ABC e os relatórios de vendas leem a MESMA consulta do vendido líquido (`VendidoLiquido`); com a devolução na data dela, a receita líquida do DRE deixaria de bater com a dos relatórios de vendas e o custo da peça devolvida precisaria de outra data. Assim a peça devolvida volta ao estoque pelo mesmo custo com que saiu e o lucro da venda fica certo. Consequência: o resultado de um mês já fechado muda se chegar devolução de venda dele depois; a tela avisa em linguagem simples ("A devolução abate o mês em que a venda foi feita; por isso o resultado de um mês pode mudar depois"). O estorno da comissão da devolução continua na data do fechamento do acerto seguinte (como no relatório de comissões); numa devolução que atravessa o período, o lucro por vendedor de um mês leva a receita menor e o mês seguinte o estorno da comissão.
   - **Custo (item 1, R1 no padrão).** Último custo de compra até o dia da venda, **o dia da compra inclusive**; duas compras no mesmo dia valem pela de `ordem` maior; **compra desfeita (estornada) não conta**; sem compra válida, o custo do cadastro hoje, com a marca "custo do cadastro" (a tela mostra quantas peças estão assim). Uma classe só (`CustoDaPeca`) serve ao custo vendido, às faltas e às avarias.
   - **Faltas (Perdas).** Toda pendência `FALTA` é perda pelo custo do dia em que o acerto fechou; a que for **encontrada** depois devolve o mesmo custo no dia em que foi encontrada (o período passado não muda). A parte que o vendedor pagou aparece em "Cobrado do vendedor" (`acertos.faltas_cobradas`, no fechamento do acerto que cobrou); se o valor incluir outra pendência cobrada, a linha mantém o número do relatório de acertos.
   - **Avaria e inventário.** Avaria é só a do estoque (inclui a defeito aprovada no acerto, que gera o movimento `AVARIA`); a da maleta não entra direto, para não contar duas vezes. Inventário entra **só para menos** (as sobras não viram ganho), com seus estornos.
   - **Comissões** pelo `fechado_em` do acerto `ACERTADO`, somando as linhas `COMISSAO` e `ESTORNO` (a mesma subconsulta serve ao DRE e ao lucro por vendedor).
   - **Caixa** só com lançamentos `MANUAL`, por categoria e pela data do lançamento; o pagamento a fornecedor e o caixa automático do acerto ficam fora (o primeiro já entra pelo custo, o segundo é venda já contada).
   - **Perdas, cobrado e caixa não são rateados** por produto nem por vendedor. Período obrigatório (de e até) nas três consultas.

## Consequências

### Boas

- Nenhuma mudança no caminho da venda, do sync, do acerto nem do caixa; nada novo vai ao Corona Vendas.
- Uma fórmula só para DRE, lucro e curva ABC; os números conferem entre si por teste.
- A dona fica sabendo de diferença no dia seguinte, sem abrir a tela de conferência.

### Ruins ou custos

- Custo pela data da venda depende do histórico de compras; a mudança manual do custo no cadastro (sem compra) só aparece nas linhas "custo do cadastro" e muda o passado delas.
- A rodada diária roda dentro da API (uma instância só, como as cotações e a limpeza); mais de uma instância continua pedindo ADR (pendência da tarefa 11).
- Duas tabelas novas (migração V047, feita na tarefa 8.4).

## Alternativas consideradas

- **Gravar o custo na venda** (coluna em `itens_venda`): descartada porque `itens_venda` é sincronizável e o vendedor nunca recebe custo. Uma tabela só do servidor `custos_da_venda`, gravada pelo `ServicoDeVendas` na mesma transação, congelaria o custo, mas mexe no caminho da venda e do push (crítico) e precisaria preencher as vendas antigas pela regra do item 1 de qualquer jeito. Fica como opção se o responsável preferir o custo congelado.
- **Custo médio ponderado:** mais correto na contabilidade, mas exige reconstruir o estoque valorado desde a primeira compra; desproporcional para um DRE "simplificado".
- **Custo do cadastro hoje para tudo:** simples, mas muda o DRE de semanas fechadas a cada compra nova.
- **Conferência sem guardar resultado** (só aviso calculado na hora rodando as consultas): cada abertura da tela inicial rodaria todas as conferências; descartada por custo e porque a Fase 7 já pediu o histórico das rodadas.
- **Corrigir sozinho a diferença achada:** proibido (imutável, correção é estorno).

## Seções da especificação relacionadas

§2 (tela de referência), §7.4, §7.5, §12, §13, §15 (Fase 8).

## Dúvidas

- **Para o responsável:** R1 (item 1: custo pela data da venda, calculado na leitura, ou tabela só do servidor com o custo congelado); R2 (item 6: horário da rodada e as 26 horas do aviso). Os demais itens seguem como escritos se não houver objeção.
- **Para a dona:** nenhuma pergunta nova. Os números do painel e do DRE são conferidos com as planilhas dela numa semana real (§13, item 7) no teste de uso; diferença achada vira tarefa.
