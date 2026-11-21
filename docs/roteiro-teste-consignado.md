# Roteiro de teste do consignado ao cliente (versão 1.1.0)

Para o **usuário**, a **dona** e o **representante** testarem juntos, com dados de teste (nada de cliente real). Um passo de cada vez; em cada passo, confira o que está escrito em "Deve aparecer". Se algo não bater ou confundir, anote o passo e o que a tela mostrou em [PROBLEMAS-DE-USO.md](PROBLEMAS-DE-USO.md).

Antes de começar: Corona Gestão e Corona Vendas na versão **1.1.0** (o instalador diz isso) e o representante com internet para a primeira abertura.

## Parte A: Corona Gestão (a dona)

1. **Produto com Tam./Ton.** Em Produtos, abra um produto e preencha **Tam./Ton.** (por exemplo "Aro 16" ou "Ouro rosé"). Salve.
   Deve aparecer: o campo na ficha, e o valor de volta ao abrir de novo. Produto sem Tam./Ton. continua salvando.
2. **Compra.** Lance uma compra com esse produto e confira que ele entra no estoque.
   Deve aparecer: a quantidade nova no estoque.
3. **Maleta.** Monte uma maleta para o representante com esse produto e mais duas peças, e envie.
   Deve aparecer: a maleta com um código, as peças e os preços da maleta.
4. **Fechamento da maleta, em duas abas.** Quando o representante devolver a maleta, abra o **Fechamento da maleta**. Leia as peças na aba de **conferência** e depois use a outra aba para ver **o que faltou e o que sobrou**.
   Deve aparecer: cada peça lida some do que falta; o que não foi lido aparece como faltando; dá para sair e voltar depois ("Fechamento em andamento").

## Parte B: Corona Vendas (o representante)

1. **Primeira semana.** Ao abrir pela primeira vez, o programa pede o número da semana. Digite o número da semana de agora (ou deixe 1).
   Deve aparecer: o Gerenciamento Semanal com a semana em andamento.
2. **Cliente com fotos.** Em **Clientes**, cadastre um cliente: nome, endereço, telefone e as **três fotos** (rosto, documento frente, documento verso).
   Deve aparecer: o cliente na lista, com a foto. Sem as três fotos, o programa avisa o que falta.
3. **Novo Pedido com duas maletas.** Em **Novo Pedido**, escolha o cliente e a primeira maleta; leia algumas peças. Troque para a segunda maleta e leia mais peças. Finalize.
   Deve aparecer: uma nota só, com as peças das duas maletas (o código da maleta de cada linha), a coluna **Tam./Ton.** e o total. Depois de finalizar, o pedido não aceita mais peças (só pela Reposição).
4. **Nota de saída.** Abra a nota e gere a **Nota de saída**.
   Deve aparecer: a nota com número, cliente, peças e valores, pronta para imprimir ou enviar.
5. **Cobrança com pendência.** Na **Cobrança**, escolha a nota, informe o que o cliente devolveu, confira a comissão e pague só uma parte (ou nada).
   Deve aparecer: **Total a Receber Bruto**, a comissão, as **Pendências anteriores** (se houver), o que foi pago e quanto **ficou devendo**. A nota passa a **Pendente**.
6. **Data do acerto no passado.** Em outra nota, na Cobrança, mude **Dt. Acerto** para um dia que já passou.
   Deve aparecer: o dia aceito. Tente um dia **depois de hoje** ou **antes do dia em que a nota saiu**: a tela não deixa e explica o que fazer.
7. **Lançar pagamento.** No cadastro do cliente, use **Lançar pagamento** para pagar parte da pendência.
   Deve aparecer: a pendência diminui, o pagamento mais antigo é abatido primeiro, o recibo (R 0000000-00) fica na lista. Tente pagar mais do que ele deve: o programa recusa.
8. **Estorno.** Estorne o pagamento (e depois, em outra nota, estorne um acerto), informando o motivo.
   Deve aparecer: a pendência volta como era e a nota volta a **Pendente** ou **Em aberto**. Se sobrou dinheiro de um recibo, ele já entra na nota mais antiga que ainda deve, **sem esperar o envio**.
9. **Reposição.** Na semana de agora, faça uma **Reposição** para um cliente.
   Deve aparecer: as peças na mesma nota (semana atual) ou uma nota nova com a mesma Dt. Cobrança (semana anterior).
10. **Troca.** Em **Troca**, devolva uma peça e leve outra.
    Deve aparecer: a peça devolvida volta à maleta de onde saiu e, antes de confirmar, quanto o valor da nota sobe ou desce (a Troca não gera crédito nem pagamento: a diferença fica no valor da nota, que o cliente paga no acerto). Nota já acertada não troca.
11. **Remarcar.** Em uma nota em aberto, use **Remarcar** e escolha outra Dt. Cobrança.
    Deve aparecer: a nova data e a nota **Remarcado**.
12. **Relatórios e WhatsApp.** Abra os relatórios do cliente e da semana e use o botão de **WhatsApp** para mandar um deles.
    Deve aparecer: os mesmos valores das telas anteriores; o WhatsApp abre com a mensagem pronta (o programa nunca manda sozinho).
13. **Troca de idioma.** No menu **Idioma**, passe para **Español** e volte para **Português**.
    Deve aparecer: todas as telas e mensagens no idioma escolhido (inclusive as mensagens de erro), valores e datas iguais.

## No fim

- A pendência de cada cliente na tela do Corona Vendas deve ser a mesma que a dona vê no Corona Gestão depois que o computador enviar os dados (com internet).
- Anote tudo que hesitou, errou ou não entendeu. Quem está sendo testado é o programa, não a pessoa.
