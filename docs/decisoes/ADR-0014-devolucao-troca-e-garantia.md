# ADR-0014: Devolução, troca e garantia

- **Status:** Aceito em 12/11/2026 pelo responsável (dúvidas R1 a R6 do plano, todas com o padrão). As dúvidas para a dona valem com os padrões seguros de [fase-6-plano.md](../progresso/fase-6-plano.md)
- **Data:** 11/11/2026
- **Tarefas que implementam:** 6.2 a 6.12 (Fase 6)

## Contexto

A §7.3 define três casos (devolução do cliente, troca, devolução à dona), a garantia com dois prazos, a autorização da dona fora do prazo, a diferença da troca, o tratamento do reembolso e o antifraude. A §9 nomeia `devolucoes`, `itens_devolucao`, `trocas`, `itens_troca`, `creditos_cliente` e `autorizacoes_excecao` e os tipos `DEVOLUCAO_CLIENTE`, `TROCA_ENTRADA` e `TROCA_SAIDA` em `movimentos_remessa`. Já estão decididos:

- venda (ADR-0011 e V022): venda imutável, só o estorno **da venda inteira** muda o status (`CONCLUIDA → ESTORNADA`); desconto por linha em Real; reembolso é `pagamentos_cliente` `REEMBOLSO` negativo; o servidor recalcula tudo;
- maleta (ADR-0010) e estoque (ADR-0008): saldo por movimentos, nunca negativo, travas em ordem estável por `id`;
- sync (ADR-0013): o Corona Vendas grava sempre na outbox; o push chama os mesmos serviços das rotas; hora do aparelho corrigida e limitada pelo servidor (item 11); `configuracoes` só do servidor, com a garantia no bloco `parametros`; as tabelas da Fase 6 entram filtradas pelo vendedor do token;
- acerto (ADR-0012, Aceito em 13/11/2026): o acerto liga **eventos** (`acerto_eventos`), conta a **devolução** (nunca o reembolso em dinheiro à parte), peças `DEFEITO` são esperadas à parte, semana fechada nunca reabre (o efeito vai para o próximo acerto), crédito usado é forma de pagamento e não reduz o que o vendedor deve.

Ficam abertos pontos que mexem em tabela (e migração publicada não se edita): o que acontece com a venda numa devolução parcial, para onde volta a peça boa de uma maleta já acertada, como a troca se liga à venda nova, de quem é o crédito sem internet, e o que o servidor faz quando o app achou que estava no prazo e ele acha que não.

## Decisão

1. **Devolução é registro novo, a venda não muda.** `devolucoes` + `itens_devolucao` (UUID do app, idempotência 201/200/409). A venda continua `CONCLUIDA`; o quanto já foi devolvido é a soma das devoluções ativas dela. O estorno da venda inteira (Fase 4) continua existindo para **erro de lançamento** no mesmo dia; devolução é para o cliente que volta. Uma venda com devolução ativa não pode mais ser estornada (409, "Esta venda já tem devolução").
2. **Limite por item, com trava.** O serviço trava a linha da venda (`SELECT ... FOR UPDATE`) antes de somar as devoluções, e depois as remessas e itens em ordem de `id` (ADR-0008). Devolvido por linha nunca passa da quantidade vendida; um `CHECK`/gatilho no banco é a segunda trava (como a V024 fez no saldo da maleta).
3. **Valor devolvido = preço pago.** Por unidade: total da linha (`preco_unitario × quantidade − desconto`) dividido pela quantidade com `Dinheiro.dividirEmParcelas` (HALF_UP, a sobra de centavos vai para as últimas unidades). A unidade número _k_ de uma linha tem sempre o mesmo valor, devolvida de uma vez ou aos poucos, e a soma de todas as unidades é o total da linha. O app nunca manda valor.
4. **Prazo da garantia.** Motivo `DEFEITO` usa `prazo_defeito_dias`; os outros (arrependimento, tamanho, outro) usam `prazo_arrependimento_dias`. Conta-se em dias do calendário de Brasília: dentro do prazo se `dia(devolução) − dia(venda) ≤ prazo`. O dia da venda é o da hora gravada (já corrigida, `hora_ajustada`); o da devolução, a hora corrigida do push (ADR-0013, item 11). Os dois prazos chegam ao app no bloco `parametros` (`prazoArrependimentoDias`, `prazoDefeitoDias`).
5. **O servidor decide, mas nunca recusa o que já aconteceu no balcão.** Se o app registrou a devolução dentro do prazo e o servidor calcula fora do prazo (relógio do computador errado), a devolução é **aceita** com `situacao = EM_REVISAO` e vira aviso para a dona; o mesmo vale para os casos de antifraude (item 10). A dona **mantém** (nada muda) ou **não aceita**: os movimentos ficam (a peça está fisicamente com o vendedor), e nasce uma **pendência do vendedor** com o valor devolvido, cobrada no próximo acerto (ADR-0012, pendências). Recusa (`RECUSADO`) só para o que é impossível: venda de outro vendedor, quantidade acima da vendida, maleta em acerto fora da regra de corte, autorização inexistente.
6. **Fora do prazo, só com autorização.** O app bloqueia e oferece "Pedir autorização à dona". `autorizacoes_excecao` (venda, itens, quantidade, motivo, situação `PEDIDA → APROVADA | NEGADA`, quem e quando) nasce no app e vai pela outbox; a dona decide online; a resposta volta pelo pull. Aprovada, vale **7 dias** para o vendedor registrar a devolução (também sem internet), que leva o `autorizacao_id` e consome a autorização (uma devolução por autorização, índice único). O mesmo caminho serve para o valor acima do limite de aprovação (item 10).
7. **Para onde vai a peça.** Cada item de devolução tem `estado_peca` (`BOA`, `DEFEITO`) e o serviço deduz o destino:
   - `BOA` de venda cuja maleta ainda está aberta (`ENVIADA`/`CONFERIDA`): movimento `DEVOLUCAO_CLIENTE` na **maleta original**, volta a ser vendida pelo mesmo preço congelado;
   - `BOA` de venda cuja maleta já foi acertada, e toda peça `DEFEITO`: **não** entra no saldo vendável. Fica registrada como **peça para a loja** (no próprio `itens_devolucao`, `destino_peca = LOJA`) e é esperada à parte no próximo acerto, como o ADR-0012 (item 4) já faz com o defeito. No acerto: boa volta ao estoque central (`DEVOLUCAO`); defeito aprovado vira `DEVOLUCAO` + `AVARIA`.
   - Assim nenhuma maleta acertada recebe movimento novo (a conciliação C9 do ADR-0012 continua valendo) e nenhuma maleta ganha item que não foi enviado.
8. **Troca = devolução + venda nova, ligadas.** Uma operação só (`TROCA`, um UUID, tudo ou nada) grava: `trocas` (cliente, vendedor, venda de origem, diferença), uma devolução com `destino = TROCA` (peça A, regras 1 a 7) e uma **venda nova** da peça B pelo `ServicoDeVendas` (maleta aberta e conferida do vendedor, preço congelado, desconto no limite, `troca_id`). Na maleta, A entra como `TROCA_ENTRADA` (quando boa e a maleta está aberta) e B sai como `TROCA_SAIDA`, no lugar de `DEVOLUCAO_CLIENTE` e `VENDA`, para o relatório separar troca de devolução; o sinal e o saldo são os mesmos. `itens_troca` não é criada: as linhas já estão na devolução e na venda (evita guardar o mesmo valor duas vezes).
   - **Diferença** = valor de B − valor devolvido de A. O valor de A vira um crédito do cliente (origem `TROCA`) usado **na mesma operação** como pagamento `CREDITO` da venda de B (um caminho só para o dinheiro, item 9). Positiva: o cliente paga o resto na venda de B (à vista ou a prazo). Negativa: a sobra fica como **crédito do cliente em aberto** (padrão) ou é devolvida em dinheiro (§17.7, dúvida da dona).
   - **No acerto** entram dois eventos: `DEVOLUCAO` (−A) e `VENDA` (+B). O tipo `TROCA` previsto em `acerto_eventos` (ADR-0012) não é necessário; a linha datada do ADR-0012 registra isso quando a Fase 7 chegar lá. A comissão de B nasce da venda; a de A é estornada pela devolução (proporcional, ADR-0002).
9. **Crédito do cliente.** `creditos_cliente` (cliente, vendedor que gerou, origem, valor) e `usos_credito` (crédito, venda, valor), imutáveis; `valor_utilizado` e a situação (`ABERTO`, `UTILIZADO`) são atalhos mantidos só pelo serviço. O uso é a forma de pagamento `CREDITO` da venda (o `CHECK` de `pagamentos_cliente.forma` é ampliado por migração nova). O crédito usado não conta como dinheiro recebido no caixa (ADR-0012). **Sem internet, só o vendedor que gerou o crédito pode usá-lo**: um vendedor tem um computador ativo, então o saldo do crédito no app dele é exato e não há uso duplo. O pull manda a cada vendedor só os créditos que ele gerou (filtro simples, nada de outro vendedor). A dona usa ou transfere qualquer crédito online. Uso acima do saldo que chegar pelo push vira `EM_REVISAO` (item 5).
   - **Linha datada, 13/11/2026 (tarefa 6.5, dúvidas T6 a T9 aprovadas no PR #129):** a dona **transfere** o saldo inteiro de um crédito para outro vendedor: nasce um crédito novo (origem `TRANSFERENCIA`) dele, e o original fica usado (uso do tipo `TRANSFERENCIA`); a partir daí só o vendedor novo usa, sempre na venda do mesmo cliente. O estorno de uma venda paga com crédito devolve o crédito (uso estornado). Na devolução de venda paga com crédito, o que foi pago em crédito volta **primeiro** como crédito novo (origem `DEVOLUCAO`) e só o resto em dinheiro; o limite da semana e o valor de aprovação olham só o dinheiro (a troca fica fora dos dois). Até a tarefa 6.7, o uso acima do saldo é sempre recusado (a venda com crédito ainda não chega pelo envio).
   - **Linha datada, 13/11/2026 (tarefa 6.7, dúvidas S1 e S2 aprovadas no PR #131):** "uso acima do saldo pelo push vira `EM_REVISAO`" quer dizer, na **venda**: ela entra; o pagamento em crédito fica limitado ao saldo que o crédito tinha (nenhuma linha quando era zero, cancelado ou já usado); o resto fica **a receber do cliente** pela conta de sempre (total − devolvido − pagos), sem valor do app; e nasce uma linha `creditos_sem_saldo` `EM_REVISAO` (o pedido e o usado) com aviso à dona. A dona **mantém** (o cliente continua devendo) ou **não aceita** (pendência do vendedor, cobrada no acerto da tarefa 7.5); nenhuma das duas mexe em dinheiro. Crédito inexistente, de outro vendedor ou de outro cliente continua `RECUSADO` (o impossível do item 5). Online, acima do saldo continua 409.
10. **Reembolso e antifraude.**
    - Dinheiro: a devolução **cancela primeiro as parcelas em aberto** da venda (da última para a primeira, baixa `CANCELAMENTO`, ADR-0012) e devolve em dinheiro só o que passar disso, como `pagamentos_cliente` `REEMBOLSO` negativo ligado à devolução, na forma que o vendedor escolher. Venda à vista: reembolso integral. O app nunca manda o valor do reembolso; manda a forma.
    - `configuracoes` ganha `prazo_arrependimento_dias`, `prazo_defeito_dias`, `limite_reembolso_semanal` (por vendedor, na semana de acerto), `valor_aprovacao_reembolso` e `percentual_alerta_devolucao`, com migração e padrão seguro.
    - Acima de `valor_aprovacao_reembolso`, a devolução precisa de autorização (item 6). Acima do limite semanal, ou reembolso maior que o pago, entra `EM_REVISAO`. A taxa de devolução (valor devolvido ÷ valor vendido nas últimas 4 semanas, por vendedor) acima do percentual gera aviso diário em "O que precisa da sua atenção".
11. **Correção.** Devolução e troca erradas se corrigem por **estorno** (`Cancelar devolução`, `Cancelar troca`): registro novo com sinal oposto em todos os movimentos, pagamentos e créditos, nunca `UPDATE` de conteúdo. O vendedor cancela até o fim do dia; depois, só a dona. Crédito já usado impede cancelar a troca que o gerou (409).
12. **Maleta em acerto.** Devolução ou troca nova numa maleta `EM_ACERTO` é 409 (ADR-0012); a feita sem internet antes do início do acerto segue a mesma regra de corte das vendas (ADR-0012, item 4). Devolução de venda de semana fechada entra no **próximo** acerto do vendedor (ADR-0012, F7).
13. **Quem registra.** Dentro do prazo: o vendedor que fez a venda (só venda dele, 403 cruzado) e a dona. A dona registra pelo Corona Gestão, online; a peça boa vai direto ao estoque da loja (`DEVOLUCAO`) e o valor entra no próximo acerto do vendedor da venda.
14. **Sync.** Tipos novos na outbox, formato 1: `DEVOLUCAO`, `TROCA`, `PEDIDO_AUTORIZACAO`, `CANCELAR_DEVOLUCAO`, `CANCELAR_TROCA`. Tabelas sincronizáveis novas (filtro pelo vendedor do token, expansão e saída pela regra do ADR-0013, item 5, e no oráculo de dois vendedores): `devolucoes`, `itens_devolucao`, `trocas`, `autorizacoes_excecao`, `creditos_cliente`, `usos_credito`. O app calcula o saldo, o prazo e o valor só para mostrar a prévia, com o mesmo utilitário de dinheiro do `packages/shared`; o servidor recalcula tudo.
    - **Linha datada, 13/11/2026 (tarefa 6.7, dúvidas S4 e S12 aprovadas no PR #131):** nenhuma expansão nova (a pertença de toda tabela nova vem de uma coluna que nunca muda e já existe quando a linha nasce). Duas colunas **ficam fora do pull** porque apontam para crédito de outro vendedor: `creditos_cliente.credito_origem_id` (no crédito transferido) e `usos_credito.credito_destino_id` (no uso de transferência). A tabela nova `creditos_sem_saldo` vai ao vendedor da venda.

## Consequências

### Boas

- A venda continua imutável e o acerto continua contando só eventos: devolução e venda, sem um terceiro tipo.
- Nenhuma maleta acertada recebe movimento novo; peças para a loja usam o mesmo caminho do defeito no acerto.
- O vendedor nunca é travado no balcão por relógio errado: o servidor marca para revisão em vez de recusar o que já aconteceu.
- Crédito sem internet sem risco de uso duplo e sem mandar dado de outro vendedor.

### Ruins ou custos

- O cliente que comprou com um vendedor não usa o crédito com outro vendedor sem internet (só a dona, online).
- Peça boa de maleta já acertada não volta a ser vendida pelo vendedor até passar pela loja.
- Devolução de venda a prazo depende das parcelas (`contas_receber`, tarefa 7.3): até lá, só venda à vista (plano, tarefa 6.6).
- Cinco chaves novas em `configuracoes`, todas com padrão seguro até a dona responder.

## Alternativas consideradas

- **Devolução muda o status da venda (`DEVOLVIDA_PARCIAL`).** Quebra a imutabilidade e não serve para várias devoluções da mesma venda. Descartada: a soma das devoluções diz o mesmo.
- **Devolução parcial = estornar a venda inteira e lançar outra.** Simples no banco, mas muda o dia da venda (prazo, comissão, semana) e confunde o relatório. Descartada.
- **Peça boa de maleta acertada entra na maleta aberta do vendedor.** Exige criar item que não foi enviado, com preço de qual maleta? Mexe na nota de remessa e no limite de consignação. Descartada.
- **Troca como entidade própria com `itens_troca` e cálculo próprio.** Duplicaria preço, desconto, parcelas e comissão do `ServicoDeVendas`. Descartada; fica a ligação `troca_id`.
- **Crédito usável por qualquer vendedor sem internet.** Precisaria mandar créditos de todos os clientes a todos os vendedores (valor de compra de outro vendedor) e abre uso duplo offline. Descartada.
- **Recusar a devolução fora do prazo que chega pelo push.** O dinheiro já foi devolvido; a recusa esconderia o fato. Descartada pela §7.3 ("nunca aceito em silêncio").

## Seções da especificação relacionadas

§3, §7.1, §7.3, §7.4, §9, §10, §12, §13, §17 (4, 5 e 7), §19.

## Dúvidas

Em [fase-6-plano.md](../progresso/fase-6-plano.md), seção "Dúvidas": R1 a R6 para o responsável e D1 a D13 para a dona. Nenhuma muda a especificação sozinha; a especificação é atualizada na tarefa que implementar cada resposta.
