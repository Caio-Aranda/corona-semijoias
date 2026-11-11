# Detalhe das tarefas da Fase 4

Uma seção por tarefa, no mesmo formato de [fase-3.md](fase-3.md). A tabela de acompanhamento está em [PROGRESSO.md](../PROGRESSO.md). O modelo da venda está no [ADR-0011](../decisoes/ADR-0011-venda-do-vendedor-online.md).

### Tarefa 4.1: plano da Fase 4 e ADR da venda (só documentos)

**Em revisão.** Nenhum código. A especificação não mudou: tudo cabe nas §7.1, §7.2, §9, §11 e §12.

**O que foi feito**

- Seção "Fase 4" em `docs/PROGRESSO.md` com o plano (4.1 a 4.6).
- [ADR-0011](../decisoes/ADR-0011-venda-do-vendedor-online.md), Proposto: tabelas, preço congelado, servidor recalcula, desconto, pagamento, baixa da maleta por movimento, estorno, idempotência, cotação, conferência com dispositivo único, o que o vendedor vê e relatórios próprios.
- Linha do ADR-0011 no índice de decisões (o 0009 é da Fase 9).

**Regras da Fase 4 (valem para todas as tarefas)**

- Rotas do vendedor em `/api/vendedor/...`; ADMIN nelas e VENDEDOR nas da dona dão 403, com teste.
- O app manda produto, quantidade, desconto e pagamentos; nunca preço nem total. O servidor recalcula.
- Venda, pagamento e movimento imutáveis; correção é estorno. Reenvio com o mesmo UUID: 200; primeira gravação: 201; conteúdo diferente: 409.
- Sem preço de custo, sem estoque central, sem dados de outros vendedores; `npm run barreira:varrer` passa.
- Dinheiro e estoque: testes primeiro.

**Dúvidas abertas:** as seis do ADR-0011 (desconto em valor ou %, parcela mínima, prazo do estorno, quem troca o aparelho, vender sem conferir, consulta fora da maleta).

### Tarefa 4.2: backend das vendas (vender, estornar, totais no servidor)

**Em revisão.** Migração `V022__vendas.sql`; pacote `vendas` (`ServicoDeVendas`, `ConsultasDeVendas`, `VendasDoVendedorController`, `VendasDaDonaController`).

**O que foi feito**

- Tabelas `vendas`, `itens_venda` e `pagamentos_cliente` (sincronizáveis; itens e pagamentos imutáveis; a venda só muda de status no estorno, protegida por gatilho). Chave nova `limite_desconto_percentual` em `configuracoes` (padrão 0).
- `POST /api/vendedor/vendas`: baixa a maleta pelo movimento `VENDA`, preço do `preco_remessa`, desconto em valor por linha respeitando o limite, pagamentos opcionais (à vista ou a prazo), cotação do dia guardada para mostrar Dólar e Guarani. 201 na primeira gravação, 200 no reenvio igual, 409 com o mesmo id e dados diferentes.
- Estorno da venda inteira (`POST /api/vendedor/vendas/{id}/estorno`, só no dia da venda; `POST /api/dona/vendas/{id}/estorno`, sem prazo): movimentos `ESTORNO_VENDA` e reembolso negativo por pagamento. Reenvio com o mesmo id: 200.
- Leitura: `GET /api/vendedor/vendas` (filtros por dia, cliente e situação) e `GET /{id}`; `GET /api/dona/vendas/{id}`. Sem custo em nenhuma resposta.
- 15 testes de integração: preço congelado, totais, a prazo, idempotência, saldo, peça fora da maleta, cliente e maleta de outro vendedor, maleta em rascunho ou cancelada, limite de desconto, pagamento a mais, estorno e seus reenvios, prazo do estorno, imutabilidade no banco, isolamento, 403 cruzado e concorrência (seis vendas da última peça: só uma entra).

**Decisões que valem daqui para a frente**

- O app nunca manda preço nem total; `totalConferido` só gera alerta na auditoria.
- A conferência da maleta e a troca de aparelho ficam na 4.3; a venda já recusa maleta ligada a outro aparelho (`MALETA_EM_OUTRO_APARELHO`).
- A tela de configuração do limite de desconto não existe (sem rota da dona): o valor 0 só muda no banco até a dona ter essa tela (pendência).
- A conciliação "estoque + maletas" subtrai as vendas líquidas de estorno (o CI do PR 67 mostrou que, sem isso, a primeira venda a quebrava); a conciliação completa com vendas e perdas é da Fase 8.

### Tarefa 4.3: backend das rotas do vendedor (maleta, conferência, clientes, cotação, relatórios)

**Em revisão.** Migração `V023__conferencia_da_maleta.sql`; pacote `vendedor` (`ConsultasDaMaletaDoVendedor`, `ServicoDeConferencia`, `ClientesDoVendedor`, `RelatoriosDoVendedor` e cinco controladores).

**O que foi feito**

- `GET /api/vendedor/maletas` e `/{id}`: só as maletas do vendedor do token, com vendida, devolvida, saldo, preço de venda e foto; nada de custo nem de estoque central. `GET /{id}/pecas?codigo=` lê a peça pelo leitor e só responde peça da maleta.
- Conferência (`POST /{id}/conferencia`): o vendedor manda o que contou (peça ausente conta zero), o servidor compara com o saldo da maleta, grava a diferença (imutável, uma por maleta), liga a maleta ao aparelho e passa a `CONFERIDA`. 201, 200 no reenvio igual, 409 com dados diferentes ou segunda conferência.
- Um aparelho por maleta: a conferência ou a primeira venda liga a maleta ao aparelho; outro aparelho recebe "Esta maleta já está em outro aparelho"; a dona libera em `POST /api/dona/remessas/{id}/liberar-aparelho` (auditado). A devolução à dona agora também vale para maleta `CONFERIDA`.
- Clientes do vendedor: cadastro (201/200/409, CPF conferido, responsável = o vendedor do token), busca por nome, telefone, WhatsApp e CPF, leitura; cliente de outro vendedor é 404.
- `GET /api/vendedor/cotacao` (Dólar e Guarani de hoje, só para mostrar) e `GET /api/vendedor/fotos/{hash}/{tamanho}` (só foto de peça da própria maleta).
- Relatórios próprios (§12): vendas por cliente, peça ou dia, recebimentos por forma e valores a receber por cliente; vendas estornadas não contam; totais também em Dólar e Guarani.
- 8 testes de integração (isolamento entre vendedores, 403 do perfil errado, conferência, aparelho único com liberação, clientes, relatórios).

**Fora desta tarefa:** comissão e acerto (Fase 7), devoluções e trocas (Fase 6), "dados até" e sync (Fase 5). A consulta de produtos fora da maleta não existe (dúvida 6 do ADR-0011). Edição de cliente pelo vendedor ficou para depois (só cadastro e leitura).

### Tarefa 4.4: Corona Vendas, conferência da maleta, venda, cliente e estorno

**Em revisão.** Primeira tarefa de telas do vendedor (`apps/seller-app`).

**O que foi feito**

- **Ponte com operações fechadas** (`shared/operacoes.ts`, `shared/validacao.ts`): a tela só pede operações da lista (maleta, conferência, venda, estorno, clientes, cotação, foto, relatórios); cada pedido é conferido (UUID, datas, dinheiro com até duas casas, quantidades, tamanhos) e o que sobra é descartado. O processo principal (`main/api.ts`) faz a chamada com o token, que nunca chega à tela, e devolve a foto como endereço de dados. Nenhum pedido leva preço, total ou vendedor.
- **Minha maleta**: peças com enviada, vendida, devolvida, saldo e preço (Real, Dólar e Guarani pela cotação do dia); botão **Conferir maleta** só enquanto a maleta está enviada.
- **Conferir maleta**: leitura do código peça a peça (cada leitura conta uma), foto grande da peça lida, tabela esperado x contado, janela "Concluir conferência" dizendo o que falta ou sobra; reenvio após queda de internet usa o mesmo id.
- **Vender**: ler o código, escolher ou cadastrar o cliente na hora, desconto por linha em reais, como o cliente pagou (dinheiro, Pix, cartão, cheque ou a prazo), total nas três moedas, confirmação "Concluir venda". As recusas do servidor (saldo, limite de desconto, aparelho) aparecem como vieram. A venda reenviada usa os mesmos ids (não duplica).
- **Minhas vendas**: lista, detalhe e **Estornar venda** com motivo; reenvio com o mesmo id do estorno.
- Tela inicial abre **Vender** e **Minha maleta**; **Meus clientes** e **Meus números** seguem "Em breve" até a 4.5.
- Testes: validação da ponte, mapeamento de cada operação para a rota da API, e as telas (leitura, conferência, venda a vista e a prazo, desconto, estorno, reenvios, recusas, três moedas).

**Decisões**

- A conta de moedas (`renderer/moedas.ts`) é uma cópia da do Corona Gestão: o Vendas não importa código da dona (ADR-0003). Mover para um pacote neutro fica como melhoria (dívida registrada no PR).
- A foto vem como `data:` (a CSP já permitia) e fica em cache de memória por tamanho.
- Sem a tela de cotação ou de configuração de desconto: o limite é do servidor.

### Tarefa 4.5: Corona Vendas, Meus clientes e Meus números

**Em revisão.** Completa as quatro funções da tela inicial do vendedor.

**O que foi feito**

- **Meus clientes**: busca (nome, telefone, WhatsApp, CPF), lista com o que cada cliente deve, cadastro e ficha (contato, o que deve e as compras feitas com este vendedor). Só os clientes dele: o servidor filtra pelo login.
- **Meus números** (§12, "App do vendedor"): vendas por cliente, peça ou dia, recebimentos por forma de pagamento (já sem os reembolsos) e quanto os clientes ainda devem, com período (de, até) e totais. Vendas estornadas não entram. Real, Dólar e Guarani em todos os valores, pela cotação de hoje; sem cotação, só Real e um aviso.
- **Valores nas três moedas** em toda a tela do vendedor (maleta, venda, vendas, clientes, números): o componente de valor usa a cotação do dia, que a tela busca uma vez.
- O botão **Ver minhas vendas** (lista, detalhe e estorno da 4.4) também abre a partir de Meus números.
- Testes das duas telas (lista, busca, ficha, cadastro e recusa do servidor, períodos, agrupamentos, vazio, falha, três moedas).

**Decisões**

- A consulta de produtos fora da maleta não existe nesta fase (dúvida 6 do ADR-0011): "consulta com foto" é a Minha maleta e a leitura do código na conferência e na venda.
- Os totais mostram Dólar e Guarani calculados na tela a partir do total em Real do servidor (a mesma cotação e o mesmo arredondamento do servidor, ADR-0006); nada disso é gravado.
- Sem o aviso "Dados até dd/mm às hh:mm" da §12: ele só vale no modo sem internet (Fase 5).

### Tarefa 4.6: fechamento da Fase 4 (revisão pós-fase)

**Em revisão.** Revisão do que a Fase 4 entregou, como a 2.9 e a 3.8.

**Bugs achados e corrigidos**

- **Conciliação "estoque + maletas" quebrava com a primeira venda** (a venda tira peça da maleta sem mexer no estoque central). Foi achada pelo CI do PR 67 e **corrigida lá (tarefa 4.2)**: a conta (`ServicoDeEstoque.conciliar` e o relatório de conferência do estoque) subtrai as vendas líquidas de estorno, como o ADR-0010, item 12, já previa; teste novo com venda, venda e estorno. Registrada aqui porque a revisão confirmou a regra.
- **A ficha da maleta da dona sumia com Devolver, Repor e os movimentos quando a maleta virava Conferida** (só olhava o status Enviada). Agora Conferida mantém Devolver e Repor (Cancelar continua só para Enviada, como no servidor), o filtro da lista ganhou "Conferida" e os movimentos mostram "Vendida pelo vendedor" e "Venda desfeita".
- **A dona não via o resultado da conferência** (os textos diziam que sim). Nova rota `GET /api/dona/remessas/{id}/conferencia` e a seção "Conferência do vendedor" na ficha.

**O que foi conferido e está certo:** preço sempre do `preco_remessa`; saldo por movimentos, nunca por número; idempotência 201/200/409 em venda, estorno, conferência e cliente; 403 cruzado e isolamento entre vendedores (maleta, cliente, venda, foto, relatórios) com testes; contrato do vendedor sem custo (teste do grupo OpenAPI e `barreira:varrer`); falta de foto nunca trava a venda.

**Pendências com a dona**

- As seis dúvidas do ADR-0011 (desconto em valor ou %, parcela mínima, prazo do estorno, quem troca o aparelho, vender antes de conferir, consulta fora da maleta).
- **Limite de desconto**: hoje 0% (sem desconto) e **não existe tela da dona** para mudar; só por banco. Precisa de uma tarefa pequena (tela de configurações do Gestão).

**Pendências com o responsável:** leitor de código físico e app instalado ainda não testados com o Corona Vendas; ícone definitivo.

**A Fase 5 pede:** SQLite criptografado, outbox e push/pull (as tabelas de venda, itens, pagamentos, conferência e cliente já estão no contrato de sync), indicador "Dados até...", bloqueio remoto, e a venda com o app sem internet (hoje a tela só tenta de novo). Também: parcelas e contas a receber completas na Fase 7 e devolução/troca na Fase 6.
