# Roteiro do teste de uso da Fase 7 (acerto e financeiro)

Para o **responsável** aplicar, sentado com a dona e um vendedor de verdade (§19 da especificação). Não é para a dona nem para o vendedor ler. O objetivo é descobrir onde o sistema atrapalha no acerto da semana e no dinheiro, não avaliar ninguém. Segue a forma do [roteiro da Fase 6](progresso/fase-6.md#roteiro-do-teste-de-uso-dona-e-um-vendedor) e o jeito de conduzir do [roteiro do teste de uso](roteiro-teste-de-uso.md): peça uma tarefa de cada vez, **não ajude antes de a pessoa tentar**, peça para pensar em voz alta e anote onde hesitou, errou ou não entendeu (registre em [PROBLEMAS-DE-USO.md](PROBLEMAS-DE-USO.md)).

Este teste **não foi feito pelo sistema**: é pendência do responsável (ver [pendencias-do-responsavel.md](pendencias-do-responsavel.md)). O que o sistema já conferiu sozinho está no [relatório da Fase 7](progresso/fase-7.md).

## Antes de começar

1. Computador de teste com a API, o Corona Gestão (dona) e o Corona Vendas (vendedor) funcionando, como no [roteiro do teste de uso](roteiro-teste-de-uso.md). Use dados de teste, nunca os de verdade.
2. **Cotação do dia lançada** (sem ela o acerto não fecha). Em **Comissão e meta**, confira: meta de **700.000 Gs**, **30%** abaixo da meta e **40%** a partir dela. Peças de **R$ 315,00** deixam a conta fácil: duas peças são R$ 630,00, que é exatamente a meta (700.000 Gs na cotação de R$ 0,0009 por guarani; com outra cotação, ajuste o preço).
3. Um vendedor com **maleta enviada e já conferida por ele**, com pelo menos 6 peças, e um cliente dele. A semana da maleta ainda **não terminou** (para o teste de fechar semana, tenha outra semana que já terminou).
4. Um fornecedor com uma compra recebida.
5. Corona Vendas do vendedor aberto e com internet no começo.

## Como apresentar

- "Quem está sendo testado é o sistema, não você."
- Peça para **pensar em voz alta**. Nunca diga onde clicar nem use palavra técnica.
- Se travar, espere; depois pergunte "o que você faria agora?". Só então mostre, anotando que precisou de ajuda.

## Parte 1: o vendedor vende e recebe (Corona Vendas)

1. **Venda à vista.** "Venda duas peças para este cliente, pagas em dinheiro." Conferir: venda feita; a maleta perdeu 2 peças.
2. **Venda a prazo com parcelas.** "Venda mais uma peça, o cliente paga R$ 15,00 agora e o resto em 3 vezes." O vendedor escolhe **Em quantas parcelas?** e **Primeiro vencimento**. Conferir: ele **não digita o valor das parcelas**; o sistema divide (a conta aparece depois que a venda chega à loja).
3. **Receber uma parcela.** Em **Meus números, A receber dos clientes**, tocar em **Receber** na venda a prazo, escolher **Dinheiro** ou **Transferência** e **Registrar recebimento**. Conferir: **Falta receber** diminui e a primeira parcela passa a **Paga**. Tentar receber mais do que falta: deve avisar, sem travar.
4. **Sem internet.** Tirar a internet e repetir um recebimento. Conferir "guardado, será enviado quando houver internet". Voltar a internet e esperar. Conferir que a dona vê o recebimento.
5. **Estornar um recebimento (mesmo dia).** Estornar o recebimento errado, com motivo. Conferir **Estornado**. Tentar estornar um de outro dia: deve dizer que só a dona pode.
6. **Minha comissão.** Em **Meu acerto e minha comissão**, ver a **prévia**: **Vendido na semana**, **Meta da semana**, **Falta para a meta** (em Guarani e em Real). Conferir que o vendedor entende que é só estimativa e não vê custo nem dado de outro vendedor.
7. **Devolução de venda a prazo.** O cliente devolve a peça da venda a prazo (em **Devolução e troca**, ou pela dona). Conferir: as parcelas que faltavam diminuem ("Abatido do que o cliente deve") e só o que o cliente já pagou volta a ele.

## Parte 2: a dona faz o acerto (Corona Gestão)

Esta parte vale para a dona, no menu **Acerto**.

1. **Começar o acerto.** Em **Acertos, Começar acerto**, escolher o vendedor. Conferir: a maleta passa a **Em acerto**; o vendedor não consegue mais vender nem devolver para essa maleta (a venda dele avisa que a maleta está em acerto).
2. **Contar as peças.** Com o leitor (ou digitando o código), contar cada peça como **Peça boa** ou **Com defeito**. Contar a peça devolvida pelo cliente também. Conferir o **Desfazer leitura** e os avisos "Chegou venda nova: confira a contagem." / "O computador do vendedor ainda tem N envios para mandar.".
3. **Conferir o acerto.** Abrir **Conferir o acerto**. Conferir: **Saiu na maleta**, **Voltou boa**, **Faltou**; o **Vendido** de R$ 945,00, **Devolvido** de R$ 315,00 e **Vendido depois dos descontos** de R$ 630,00; "Vendido na semana: Gs. 700.000 de Gs. 700.000 (meta)" e **"Bateu a meta: comissão de 40%."**; **Comissão do vendedor** de R$ 252,00 e **Valor do acerto** de R$ 378,00. Os valores em Dólar e Guarani aparecem pela cotação do dia.
4. **Uma peça que falta.** (Em outro acerto de teste.) Deixar uma peça sem contar. Escolher **Cobrar agora**, **Cobrar depois** ou **A loja assume** (este pede o motivo). Conferir que o valor do acerto muda e a pendência aparece em **Pendências**.
5. **Pagamento.** Em **Pagamento**: **Dinheiro** R$ 200,00, **Transferência (PIX)** R$ 100,00 e o resto (R$ 78,00) em **2 parcelas**, com **Data da primeira parcela**. Conferir que só existem Dinheiro, Transferência (PIX) e Boleto: **nunca cheque, cartão nem entrada**.
6. **Fechar o acerto.** Tocar em **Fechar acerto** e ler a pergunta ("Depois de fechado, o acerto não muda mais."). Conferir: "Acerto fechado."; maleta **Acertada**; a ficha só mostra, não deixa mudar. **Imprimir acerto** gera o PDF com os mesmos valores, o percentual e a soma em Guarani da meta.
7. **Um acerto abaixo da meta.** Fazer outro acerto com venda de R$ 629,99 (um pouco abaixo da meta): deve aparecer **"Ainda não bateu a meta: comissão de 30%."**.
8. **Desistir.** Começar um acerto e tocar em **Desistir do acerto** (com motivo). Conferir: a maleta volta a ficar com o vendedor e nada de dinheiro é gravado.
9. **O vendedor vê o acerto dele.** No Corona Vendas, **Meu acerto e minha comissão, Ver acerto**: ele vê o resumo, a comissão de cada venda e o que pagou, com a cotação do dia do acerto, **sem custo** e sem dado de outro vendedor. As parcelas do que ficou devendo aparecem em **O que você deve à loja**.

## Parte 3: o dinheiro da loja (Corona Gestão, menu Financeiro)

1. **Recebimentos do vendedor.** Em **Registrar recebimento**, receber uma parcela do que o vendedor ficou devendo (**Forma**, **Valor recebido**). Conferir que a parcela diminui. **Estornar recebimento** (com motivo): a dívida volta.
2. **Caixa.** Conferir **Saldo**: os recebimentos do acerto entraram **sozinhos** (R$ 300,00). Em **Lançar no caixa**, lançar uma saída (categoria, forma, data, descrição, valor). Conferir o saldo. Estornar o lançamento, com motivo. Se o saldo ficar negativo, aparece o aviso "O caixa está negativo...", sem travar nada.
3. **Contas a pagar.** Em **Contas a pagar**, achar a parcela da compra, **Registrar pagamento** (**Dinheiro**, **Transferência (PIX)** ou **Boleto**) e conferir que a saída entrou no caixa. **Ver pagamentos** e **Estornar pagamento**. Observação: **gerar as contas a pagar de uma compra ainda não tem botão na tela**; no teste, peça ao suporte para gerar.
4. **Contas a receber.** Em **Contas a receber**, conferir os dois lados: **Do cliente ao vendedor** e **Do vendedor à loja**.
5. **Fechar a semana.** Em **Cadastros, Semanas**, **Fechar semana** numa semana que já terminou e **sem acerto em andamento**. Ler a pergunta ("Isso não pode ser desfeito."). Depois, tentar lançar no caixa com data nessa semana: deve recusar em português simples. Tentar fechar uma semana que não terminou: deve explicar.
6. **Conferência do dinheiro.** Em **Conferência, Conferência do dinheiro**, ver as nove conferências, todas com "Tudo certo". Se aparecer diferença, **não corrija à mão**: anote e avise o suporte (é erro do sistema, não da loja).

## Parte 4: relatórios financeiros

Em **Relatórios financeiros**, abrir cada um e conferir com o que foi feito: **Acertos por vendedor e semana**, **Faltas e pendências**, **Esperado e contado**, **Comissões por vendedor e semana**, **Parcelas a receber**, **Parcelas a pagar**, **Quem está devendo**, **Fluxo do caixa** e **Recebimentos por forma de pagamento**. Baixar um em PDF, em Excel e em CSV. Conferir que as colunas de dinheiro têm Real, Dólar e Guarani e que o total bate com o que a dona fez.

## O que observar

Para cada passo: quanto tempo levou, **onde hesitou**, **onde errou**, **o que não entendeu** (palavra ou botão), onde precisou de ajuda e as frases ("isto aqui o que é?"). Em especial:

- A dona entende **por que a comissão é 30% ou 40%** olhando a conferência do acerto?
- A dona confia no **valor do acerto** antes de fechar? Entende **Cobrar agora, Cobrar depois e A loja assume**?
- O vendedor entende **quanto falta para a meta**? Entende que o recebimento da parcela é dele registrar?
- Alguém sente falta de **cheque**, de **entrada** ou de **cartão**? (Não existem de propósito: decisão de 13/11/2026.)
- Algo que a dona esperava poder fazer e não achou (o vendedor **pedir o acerto** pelo Corona Vendas e **bipar as sobras** não existem ainda: a dona começa o acerto).

Ao final, pergunte: "o que foi mais difícil?" e "o que você mudaria?", e registre as respostas.
