# ADR-0011: Venda do vendedor (Fase 4, online, preparada para sync)

- **Status:** Proposto (a Orquestradora 4.0 e o responsável aceitam no PR). As dúvidas 1 a 6 valem com os padrões provisórios descritos
- **Data:** 11/11/2026
- **Tarefas que implementam:** 4.2 a 4.5 (Fase 4); o offline entra na Fase 5 sem mudar o contrato

## Contexto

A §7.2 define a venda, a §7.1 a conferência e o dispositivo único da maleta, a §11 a segurança e a §12 os relatórios do vendedor. O ADR-0003 separa os apps, o ADR-0006 fixa as moedas, o ADR-0008 o estoque e o ADR-0010 a maleta (o item 10 deixou o `dispositivo_id` para esta fase). As Fases 1 a 4 são online, mas já preparadas para sync (UUID do cliente, `deleted_at`, vendas imutáveis, idempotência). Migração publicada não se edita: as tabelas novas vêm numa migração nova (a próxima livre na hora da tarefa 4.2).

## Decisão

1. **Tabelas (nomes da §9).** `vendas` (id do cliente, vendedor, cliente, remessa, data, status `CONCLUIDA` ou `ESTORNADA`, subtotal, desconto, total, cotação usada), `itens_venda` (venda, produto, quantidade, `preco_unitario` copiado do `preco_remessa`, desconto da linha) e `pagamentos_cliente` (id do cliente, venda, valor, forma, data, `tipo` `PAGAMENTO` ou `REEMBOLSO`; reembolso é registro negativo). Campos de controle do ADR-0002 em todas.
2. **Quem vende.** Só o perfil VENDEDOR, em `/api/vendedor/...`. Um vendedor só vê e usa a **própria maleta**, os **próprios clientes** e as **próprias vendas**. ADMIN em rota do vendedor e VENDEDOR em rota da dona dão 403, com teste. A venda sai só de peças da maleta `ENVIADA` ou `CONFERIDA` do vendedor ("Esta peça não consta na sua maleta"; saldo zero recusa).
3. **Preço congelado.** O preço da linha é o `preco_remessa`. O app manda produto, quantidade, desconto e pagamentos; **nunca** manda preço nem total. O servidor ignora qualquer preço que venha do app.
4. **Servidor recalcula tudo.** Subtotal, desconto, total, parcelas e valor a receber saem do servidor, com `Dinheiro` (NUMERIC(14,2), arredondamento só ali). Divergência entre o total que o app mostrou (campo opcional de conferência) e o do servidor vira **aviso registrado**, não rejeição silenciosa; o valor que vale é o do servidor.
5. **Desconto.** Em **valor em Real** por linha e/ou na venda; o limite vem de `configuracoes.limite_desconto` (percentual do subtotal, definido pela dona), validado no servidor: acima do limite a venda é recusada com a mensagem "O desconto passa do limite que a dona definiu" (dúvida 1: confirmar se o desconto é em valor ou em porcentagem).
6. **Pagamento.** À vista (um pagamento `PAGAMENTO` igual ao total, na forma escolhida: dinheiro, Pix, cartão, cheque) ou a prazo (fiado ou parcelado). Na Fase 4 a venda a prazo grava só o que foi pago na hora; as parcelas e as contas a receber completas são da Fase 7. Pagamento a mais que o total é recusado. Valor mínimo de parcela fica sem regra nesta fase (dúvida 2).
7. **Baixa da maleta por movimento.** Vender grava o movimento `VENDA` em `movimentos_remessa` (UUID do cliente, `venda_id`), na mesma transação da venda; o saldo nunca é editado e nunca passa de zero (bloqueio com trava por linha, como na devolução, ADR-0010). O estoque central não muda (a peça já saiu dele no envio).
8. **Imutável e estorno.** Venda, itens, pagamentos e movimentos nunca recebem `UPDATE` de conteúdo nem `DELETE`. Corrigir é **estornar a venda inteira**: a venda vira `ESTORNADA`, nascem o movimento de estorno (`ESTORNO_VENDA`, sinal oposto, `estorna_id`) e, se houve dinheiro, um pagamento `REEMBOLSO` negativo. Estorno parcial, devolução de cliente com garantia e troca são da Fase 6. O estorno pelo vendedor vale até o fim do dia da venda; depois, só a dona (dúvida 3).
9. **Idempotência por UUID do cliente.** `id` da venda e de cada pagamento vêm do app. Reenviar o mesmo `id` com o mesmo conteúdo devolve a venda já gravada (**200**, sem mexer de novo na maleta); a primeira gravação é **201**; o mesmo `id` com conteúdo diferente é **409**. O mesmo vale para o estorno (chave: o `id` do movimento de estorno).
10. **Cotação no lançamento.** A venda guarda a cotação do dia (Dólar e Guarani) usada para **mostrar** os valores; tudo é gravado em Real (ADR-0006). Sem cotação, a venda segue em Real e a tela avisa.
11. **Conferência da maleta e dispositivo único.** A maleta `ENVIADA` vira `CONFERIDA` quando o vendedor bipa as peças recebidas e confirma; a divergência (falta ou sobra) é registrada em `conferencias_remessa` (remessa, produto, esperado, contado) e a dona vê no app dela. O servidor guarda o `dispositivo_id` na primeira conferência **ou na primeira venda** (o que vier antes; a conferência usa o saldo da maleta naquele momento como "esperado"); outro aparelho do mesmo vendedor recebe "Esta maleta já está em outro aparelho" ao vender ou conferir. A dona libera o aparelho pela rota dela (dúvida 4). Vender sem conferir é permitido (padrão mais seguro para a loja: a venda não trava), e a tela lembra de conferir (dúvida 5).
12. **O que o vendedor vê.** Maleta (peças, saldo, preço de venda, foto), produtos **da própria maleta** (a consulta de produtos fora da maleta fica de fora na Fase 4; padrão mais seguro para a barreira, dúvida 6), clientes dele, cotação do dia, vendas e valores dele. **Nunca**: preço de custo, `produtos_custos`, estoque central, fornecedores, outros vendedores, comissão dos outros. O contrato do vendedor tem tipos próprios e a varredura em três camadas (ADR-0003) passa a conferir os campos proibidos.
13. **Clientes do vendedor.** Cadastro e busca dos clientes com `vendedor_responsavel` igual ao dele; cliente de outro vendedor não aparece (404). Cliente novo criado pelo vendedor entra com ele como responsável. Idempotência por UUID também no cadastro.
14. **Relatórios do vendedor (§12, só dele, sem custo).** Minha maleta, minhas vendas por período, cliente e produto, meus clientes e valores a receber, recebimentos por forma de pagamento. Comissão e acerto são da Fase 7; devoluções e trocas, da Fase 6. Online, sem o aviso "dados até" (vem na Fase 5).
15. **Auditoria.** Venda acima de certo desconto, estorno e troca de dispositivo gravam auditoria (§11), sem dado sensível.
16. **Telas.** Quatro botões grandes: **Vender**, **Minha maleta**, **Meus clientes**, **Meus números**. Leitor com foto grande da peça, falta de foto nunca trava. Textos só em `textos.ts`; termos do glossário; erro em português simples dizendo o que fazer.

## Dúvidas abertas (padrão provisório entre parênteses)

1. Desconto em valor ou em porcentagem por linha? (valor em Real, limite em % do subtotal.)
2. Valor mínimo de parcela. (sem regra.)
3. Até quando o vendedor pode estornar? (fim do dia da venda.)
4. Quem libera a troca de aparelho da maleta? (a dona.)
5. Vender antes de conferir? (permitido, com lembrete.)
6. Vendedor consulta produtos fora da maleta? (não nesta fase.)

## Consequências

### Boas
- Venda, maleta e estoque sempre explicáveis por movimentos; reenviar não vende duas vezes.
- A Fase 5 só troca o transporte (outbox e push): o contrato e as regras já são os do sync.
- Barreira reforçada: o vendedor nunca vê custo, nem por engano.

### Ruins ou custos
- Venda a prazo fica incompleta até a Fase 7 (só o pago na hora).
- Sem consulta fora da maleta, o vendedor não vê o que há na loja.
