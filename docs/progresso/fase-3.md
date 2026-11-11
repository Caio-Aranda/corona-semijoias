# Detalhe das tarefas da Fase 3

Uma seção por tarefa, no mesmo formato de [fase-2.md](fase-2.md). A tabela de acompanhamento está em [PROGRESSO.md](../PROGRESSO.md). O modelo da maleta está no [ADR-0010](../decisoes/ADR-0010-maleta-e-remessa.md).

### Tarefa 3.1: plano da Fase 3 e ADR da maleta (só documentos)

**Mesclada.** Nenhum código. A especificação não mudou: tudo cabe na §7.1, na §9 e na §13.

**O que foi feito**

- Seção "Fase 3" em `docs/PROGRESSO.md`, com o plano (3.1 a 3.8) e as pendências da maleta.
- [ADR-0010](../decisoes/ADR-0010-maleta-e-remessa.md), Proposto: tabelas, estados, envio que baixa o estoque, limite, restrição, preço congelado, idempotência, conciliação e nota em PDF.
- Linha do ADR-0010 no índice de decisões (o 0009 é da Fase 9).

**Regras da Fase 3 (valem para todas as tarefas)**

- Montar é **online**, só a dona (ADMIN), com leitor; foto grande da peça lida; falta de foto nunca trava.
- Rascunho não mexe no estoque; **só o envio** baixa o estoque central, tudo ou nada, pelo serviço único do ADR-0008 (`REMESSA`; devolução à dona é `DEVOLUCAO`).
- Preço congelado no envio; o servidor recalcula tudo e nunca confia no app.
- Movimentos e remessa enviada são imutáveis; correção é estorno.
- Bloqueios no servidor: limite de consignação, restrição que impede, vendedor inativo, peça sem saldo central.
- Reenvio com o mesmo UUID: 200 (já gravado); primeira gravação: 201.
- O app do vendedor nunca recebe custo nem dados de outros vendedores.

**Escopo de cada tarefa:** ver a tabela em `PROGRESSO.md`. Os estados `CONFERIDA`, `EM_ACERTO` e `ACERTADA` ficam para as Fases 4 e 7.

**Decisões que tomei**

- Limite medido a **preço de venda**, com a reposição dentro da conta (provisório).
- Nota em PDF gerada no **app da dona**, com dados vindos do servidor, no mesmo gerador da 2.8; troca de biblioteca só se a 3.4 mostrar que não dá.
- Montagem não cadastra peça nova (diferente da compra).
- Restrição só bloqueia se marcada "Impede montar maleta" (glossário, 1.2).

**Dúvidas abertas:** as seis do fim do ADR-0010.

**Não feito:** código, termos de tela da maleta no glossário (vêm na 3.5) e mudança na especificação.

### Tarefa 3.2: backend das remessas (montar, enviar, limite, restrição, preço congelado)

**Mesclada.** Migração V021, rotas só da dona em `/api/dona/remessas`, serviço de envio e testes. Nenhuma tela.

**O que foi feito**

- Tabelas `remessas`, `itens_remessa` e `movimentos_remessa` (sincronizáveis; sem custo em nenhuma coluna). O `CHECK` de status já inclui `CONFERIDA`, `EM_ACERTO` e `ACERTADA`. Depois do envio, gatilhos recusam mudar conteúdo e itens; movimentos são imutáveis.
- Rotas (só ADMIN): criar rascunho (id do app), colocar/trocar e tirar peça (produto e quantidade, com `version`), excluir rascunho (lógico), ver, listar (vendedor, semana, status) e **enviar**.
- Envio numa transação: confere vendedor ativo e sem restrição que impede, peças ativas e limite; congela o preço de venda do dia; baixa o estoque central pelo serviço único (`REMESSA`, origem `MALETA`, ordem estável, tudo ou nada). Trava por vendedor para o limite não ser furado por envios simultâneos.
- Idempotência: criar com o mesmo id e dados iguais = 200; primeira vez = 201; dados diferentes = 409. Enviar de novo = 200 sem baixar de novo.
- Mensagens em português simples (a palavra "remessa" é proibida na tela: os textos dizem "maleta"). Clientes da API regerados.

**Decisões que tomei**

- **Limite 0 = sem limite cadastrado** (o banco guarda 0 por padrão; não distingue "sem limite" de "limite zero"). Limite exato passa; um centavo a mais não.
- **Sem movimento de "saldo inicial"** em `movimentos_remessa`: o saldo da maleta é `qtd_enviada` + movimentos (§7.1, ADR-0010 item 5).
- Em aberto conta as maletas `ENVIADA`, `CONFERIDA` e `EM_ACERTO` (as duas últimas ainda inalcançáveis), a preço congelado.
- Rascunho: uma linha por produto; colocar o mesmo produto de novo troca a quantidade. O pedido de criação não leva itens (assim o reenvio idempotente não briga com edições). Praça vazia usa a do vendedor.
- Restrição que não impede aparece só como `avisos` no rascunho.
- Tabelas registradas como sincronizáveis já agora; o filtro por vendedor do pull é da Fase 5 (hoje não há pull de vendedor).

**Dúvidas abertas:** as do ADR-0010 (limite 0 como "sem limite" entra na dúvida 6).

**Não feito:** devolução, cancelamento de remessa enviada, PDF, relatórios e telas.

### Tarefa 3.3: backend: devolução da maleta, cancelamento e conciliação

**Mesclada.** Sem migração nova (a V021 já tinha tudo). Rotas só ADMIN em `/api/dona/remessas`; nenhuma tela, PDF, relatório ou venda.

**O que foi feito**

- **Devolução à dona** (`POST /{id}/devolucoes`): várias peças por chamada, cada uma com id do aplicativo. Grava `DEVOLUCAO_DONA` (quantidade negativa) em `movimentos_remessa` e `DEVOLUCAO` (origem MALETA) no estoque central, na mesma transação, em ordem estável de produto, tudo ou nada. Nunca passa do saldo da maleta (`qtd_enviada` + movimentos). Só maleta enviada.
- **Cancelar** (`POST /{id}/cancelar`, motivo obrigatório): rascunho vira cancelada sem mexer no estoque; enviada só cancela se não há movimento ativo, e então estorna a baixa do envio pelo serviço de estoque. Cancelada não aceita mais nada; cancelar de novo = 200.
- **Desfazer devolução** (`POST /{id}/devolucoes/{devolucaoId}/estorno`): movimento de sinal oposto com `estorna_id` (único) e o estoque central baixa de novo; se o central não tiver as peças, 409 com texto simples.
- **Leitura**: `GET /{id}/maleta` (por peça: enviada, devolvida, saldo; valor em aberto a preço congelado; movimentos) e `GET /em-aberto?vendedorId=` (valor em aberto por maleta, total, limite e quanto sobra).
- **Conciliação** (`GET /api/dona/estoque/conciliacao`, a que já existia, sem rota nova): além de `divergencias`, traz `diferencasComMaletas` = estoque central + saldo das maletas enviadas contra o que entrou e saiu fora da maleta (compras, entradas, saídas, perdas, ajustes e estornos). Os movimentos da maleta só trocam peça de lugar e ficam de fora do lado direito.
- Testes primeiro (22 em `DevolucaoDaMaletaTest`): saldo, parcial e total, idempotência 201/200/409, cancelamentos, estorno, permissão (vendedor 403), limite que libera depois da devolução, concorrência (devoluções, devolução x cancelamento, central sob saída simultânea) e conciliação depois de compra, maleta, devolução, estorno, cancelamento, perda e inventário, inclusive com operações simultâneas.
- `operationId` explícito em todas as rotas novas; clientes regerados e `npm run typecheck` passando.

**Decisões que tomei**

- O id da devolução do aplicativo é também o id do movimento no estoque central: o reenvio é idempotente nos dois lados e o estorno acha o movimento certo.
- "Movimento ativo" (que impede cancelar maleta enviada) = movimento da maleta que não é estorno e não foi estornado: devolução desfeita deixa cancelar.
- Idempotência vem antes da conferência de status: reenviar uma devolução já gravada dá 200 mesmo que a maleta tenha sido cancelada depois (porque a devolução foi desfeita).
- A mesma peça não pode aparecer duas vezes na mesma chamada de devolução.
- Maleta cancelada ou em rascunho mostra saldo 0 e valor em aberto 0 na leitura. A lista de "em aberto" inclui todas as maletas enviadas, mesmo com saldo 0 (maleta toda devolvida segue enviada até as Fases 4 e 7).
- Todo movimento da maleta trava primeiro a linha da remessa e depois os produtos (ordem de id): a Fase 4 (vendas) deve seguir a mesma ordem.
- Motivo de cancelamento e de estorno: até 400 caracteres.

**Dúvidas abertas:** as do ADR-0010. Nova: depois de devolver tudo, a maleta deve virar "encerrada" sozinha? Hoje continua enviada.

**Não feito:** PDF, relatórios, telas e vendas.


### Tarefa 3.4: backend: dados da nota de remessa e relatórios da maleta

**Mesclada.** Sem migração e sem escrita: só leitura, sobre os registros da 3.2 e da 3.3. Rotas só ADMIN; nenhuma tela nem PDF.

**O que foi feito**

- **Nota** (`GET /api/dona/remessas/{id}/nota`, `notaDaRemessa`): loja, número legível, vendedor, praça, semana, tipo, data do envio, peças (código, nome, quantidade, preço congelado, subtotal), totais e a cotação do dia do envio com o total em Dólar e em Guarani. Rascunho: 409 "A nota só fica pronta depois que a maleta for enviada. Envie a maleta e tente de novo."; cancelada vem com `cancelada = true` e o motivo (cancelada antes do envio, que nunca teve nota, cai na mesma recusa do rascunho). Sem custo em nenhum campo.
- **Cinco relatórios** em `/api/dona/relatorios/maleta/` (mesmo motor, forma, filtros, paginação e `recorte` dos da 2.5): `remessas` (por vendedor e semana), `reposicoes`, `saldo` (peça a peça: enviada, devolvida, saldo, valor), `mercadoria-por-vendedor` (peças, valor, limite e quanto sobra) e `em-aberto` (em aberto e atrasadas). `operationId` explícito em todas as rotas.
- **Três moedas:** cada linha traz Real, Dólar, Guarani e `dataDaCotacao`, pela cotação do dia do **envio** da maleta (maior data menor ou igual ao dia do envio em Brasília, ADR-0006). O rodapé soma as linhas já convertidas (`Total` ganhou `valorEmDolar` e `valorEmGuarani`, opcionais; os relatórios antigos não mudam).
- **Exportação:** como na 2.5, o servidor entrega as páginas com os mesmos filtros e o Corona Gestão monta PDF, Excel e CSV (as colunas de Real/Dólar/Guarani e a data da cotação já vêm em cada linha).
- Glossário: nomes de tela, filtros, totais e texto da nota (parte da tarefa 3.4).
- Testes (14, `RelatoriosDeMaletaTest`): maleta enviada, com devolução, cancelada, reposição e dois vendedores; cotação do dia de cada envio (nunca a de hoje); rodapé igual à soma das linhas; rascunho e cancelada fora dos saldos; filtros; atraso; preço congelado depois de mudar o preço da peça; vendedor 403; nota de rascunho recusada.

**Decisões que tomei**

- **Atrasada** = mais de **7 dias** desde o envio (calendário de Brasília) e ainda com peça com o vendedor. O prazo é o parâmetro `diasDeAtraso` (0 a 365, padrão 7), não uma configuração gravada. Dúvida 5 do ADR-0010 continua aberta para a dona.
- **Número legível da maleta** sem coluna nova: `S{semana}-V{código do vendedor}-{ordem do envio daquele vendedor naquela semana}` (ex.: S12-V7-1), calculado pela ordem de envio, que não muda depois de enviada. Uma coluna sequencial real exigiria migração e mexeria nas colunas sincronizáveis; fica para quando a dona disser se quer outro formato.
- **Loja** na nota = "Corona Semijoias" (a marca; não há cadastro do nome da loja).
- Relatórios mostram só maletas **enviadas** (ou adiante); rascunho nunca. "Registradas" (padrão) deixa de fora as canceladas, que ficam marcadas e **fora do total** com `situacao=TODAS`.
- O saldo e o em aberto contam só maletas com o vendedor (`ENVIADA`, `CONFERIDA`, `EM_ACERTO`); o saldo é `qtd_enviada` + movimentos, como na 3.3.
- A **sobra do limite** do relatório por vendedor conta tudo o que ele tem em aberto, mesmo quando os filtros mostram só parte. Limite 0 no cadastro = sem limite (sobra vazia), como na 3.2.
- Maleta enviada antes da primeira cotação do sistema fica com Dólar e Guarani vazios (a nota traz `cotacao` vazia); o rodapé ignora essas linhas.
- Praça = praça da maleta (a que foi gravada na montagem), não a atual do vendedor.
- Peça e categoria nos filtros: nos relatórios por maleta, a maleta só entra se tiver a peça; no saldo e no em aberto, só as peças filtradas contam.

**Dúvidas abertas:** as do ADR-0010 (a 5, prazo de atraso, com o padrão acima). Nova: a dona quer um número de maleta sequencial próprio (1, 2, 3...) em vez de S12-V7-1?

**Não feito:** telas (3.6), geração do PDF da nota (app da dona, 3.5/3.6), conferência e vendas. O gerador de PDF à mão aguentar a nota (várias páginas, assinatura) é verificação da tarefa do app, não dos dados.

### Tarefa 3.5: Corona Gestão: montar a maleta por leitor

**Mesclada.** Só tela e ponte do app da dona; nenhuma mudança no servidor. Menu novo **Maletas → Montar maleta**.

**O que foi feito**

- **Nova maleta:** vendedor, semana (só as abertas), praça (padrão: a do vendedor) e tipo (**Maleta nova** ou **Reposição**). O id (UUID) nasce na tela e só muda depois de gravar. Vendedor com restrição que impede ou outro bloqueio: aparece a mensagem do servidor e a pessoa não segue.
- **Montagem pelo leitor:** campo sempre em foco; cada leitura grava na hora no rascunho (soma 1; ler de novo soma mais 1). Painel da última peça com **foto grande**, nome, código, saldo no estoque, quantidade já na maleta e preço. Lista com foto pequena, quantidade editável (com **Atualizar**), preço e subtotal, e **Tirar**. Peça sem foto segue normal.
- **Recusas na tela, sem chamar o servidor:** código que não existe (**Peça não encontrada**, nunca cadastra, ADR-0010 item 16), peça desativada, sem estoque e quantidade acima do estoque.
- **Limite:** ao lado dos totais, **Limite do vendedor / Já está com ele / Esta maleta / Sobra** (Real, Dólar e Guarani pelos componentes de moeda; ADR-0006). Vendedor sem limite cadastrado: **Sem limite cadastrado**, sem sobra.
- **Enviar maleta:** confirmação com vendedor, peças e valor. Recusas do servidor (limite, saldo de uma peça, restrição) aparecem com o texto dele, e a pessoa continua montando. Resposta 200 mostra **Esta maleta já estava enviada**. Depois do envio, ficha só para ler.
- **Rascunhos:** lista (**Continuar montando**, **Excluir** com confirmação; exclusão lógica, estoque intacto).
- **Ponte:** oito operações novas (`maletas.*`) com conferência de parâmetros no processo principal (UUIDs, quantidade inteira de 1 a 100.000, tipo e situação conhecidos; nenhum campo de preço aceito) e testes de rota.
- **Testes:** 17 de tela (`maletas.test.tsx`), 4 de conferência e 1 de rotas da ponte. Lint, typecheck, format, testes do owner-app (560) e barreira do Corona Vendas passando.

**Decisões que tomei**

- **Uma única tela** (Montar maleta) com nova maleta e rascunhos juntos, sem rota separada para rascunhos.
- **Cada leitura grava no servidor** (o rascunho já fica guardado) e as chamadas vão em fila, para a versão nunca ficar para trás; "outra pessoa mudou" mostra o botão **Atualizar**.
- **Sem som:** o app ainda não tem padrão de som; a leitura aceita ou recusada aparece em aviso colorido com texto.
- **Passou do limite não bloqueia o botão:** a tela só avisa e quanto passou; o servidor tem a palavra final e a mensagem dele aparece.
- **Quantidade maior que o estoque** é recusada na leitura (usando o saldo da busca); ao digitar a quantidade na lista quem confere é o servidor.
- **Imprimir nota:** botão escondido até a 3.7 (`NOTA_DA_MALETA_PRONTA` e `imprimirNotaDaMaleta` em `nucleo/nota-da-maleta.ts`; a 3.7 completa e liga). Nenhum PDF aqui.
- Busca por nome (se o código não existe) vale como na compra: um achado entra, vários pedem escolha.

**Dúvidas abertas:** as do ADR-0010. Nova: o botão **Imprimir nota** deve aparecer já na ficha da 3.5 ou só quando a 3.7 existir? (Está escondido.)

**Não feito:** lista geral e ficha completa da maleta enviada, devolução, cancelamento, reposição guiada (3.6), PDF e relatórios (3.7), qualquer coisa do vendedor.

### Tarefa 3.6: Corona Gestão: gerir a maleta

**Mesclada.** Só tela e ponte do app da dona; nenhuma mudança no servidor. Menu novo **Maletas → Maletas** (a tela **Montar maleta** da 3.5 continua).

**O que foi feito**

- **Lista** com filtros (**Vendedor**, **Semana**, **Situação**: em montagem, enviada, cancelada) e colunas vendedor, semana, tipo, situação, peças, valor (3 moedas) e data do envio; **Abrir** leva à ficha, e maleta em montagem abre a montagem da 3.5. Visão **Por vendedor**: quanto tem em aberto, limite e quanto sobra (**Passou X** quando excede), com **Nova reposição**.
- **Ficha da maleta enviada:** foto, enviada, devolvida, saldo e preço congelado de cada peça; movimentos em ordem (**Devolvida à dona**, **Devolução desfeita**); situação e, se cancelada, o motivo. **Imprimir nota** segue atrás do gancho da 3.5 (a 3.7 liga).
- **Reposição** (**Repor esta maleta** na ficha ou **Nova reposição** por vendedor): cria rascunho tipo reposição do mesmo vendedor, semana e praça, sem copiar peças, mostrando o que ele já tem e o limite; abre a montagem da 3.5.
- **Devolução à dona:** leitor com foto (cada leitura soma 1) ou quantidade digitada, nunca acima do saldo (a tela mostra o saldo; o servidor confere de novo). O id de cada linha nasce na tela; **Esta devolução já estava registrada** quando o servidor responde 200. **Desfazer devolução** pede o motivo e mostra a mensagem do servidor se as peças já saíram do estoque.
- **Cancelar maleta** (rascunho ou enviada): motivo obrigatório e aviso "As peças voltam para o estoque"; se o servidor recusa por já ter movimento, mostra a mensagem dele e sugere devolver as peças.
- **Ponte:** quatro operações novas (`maletas.saldo`, `devolver`, `desfazerDevolucao`, `cancelar`) com conferência de parâmetros e testes de rota.
- **Testes:** 12 de tela (`gerir-maletas.test.tsx`), 2 de conferência e 1 de rotas. Lint, typecheck, format e testes do owner-app (573) passando.

**Decisões que tomei**

- Cada linha de devolução tem id próprio; mudar a quantidade de uma linha gera id novo (o servidor recusa o mesmo id com outro valor). O id do "desfazer" vale por movimento e motivo, e tentar de novo com o mesmo motivo reenvia o mesmo pedido.
- A visão **Por vendedor** busca a rota de maletas em aberto de cada vendedor ativo (uma chamada por vendedor); basta para o tamanho da loja.
- Cancelar rascunho fica na própria tela de montagem (botão **Cancelar maleta**); **Excluir** (3.5) continua na lista de rascunhos de Montar maleta.
- Ações da ficha (devolver, repor, cancelar, desfazer) só aparecem em maleta **Enviada**; as fases seguintes (conferida, em acerto) ganham regras próprias.

**Dúvidas abertas:** as do ADR-0010. Nenhuma rota faltou. Reforço: depois de devolver tudo a maleta deve "encerrar" sozinha? Hoje continua enviada (dúvida da 3.3).

**Não feito:** PDF da nota e relatórios (3.7), conferência e venda (app do vendedor).

### Tarefa 3.7: Corona Gestão: relatórios da maleta e nota de remessa

**Mesclada.** Só tela e ponte do app da dona; nenhuma mudança no servidor. Menu novo **Relatórios da maleta** com os cinco relatórios da 3.4, e o botão **Imprimir nota** ligado.

**O que foi feito**

- **Cinco relatórios** (**Maletas por vendedor e semana**, **O que ainda está com o vendedor, peça a peça**, **Mercadoria com cada vendedor**, **Maletas em aberto e atrasadas**, **Reposições**) na mesma `TelaDeRelatorio` da 2.8: filtros (busca, semana, vendedor, praça, período, categoria, tipo, situação, **Prazo para atrasar (dias)** já em 7), rodapé com **Ver os registros**, exportação em PDF, Excel e CSV com os mesmos filtros. **Ver a maleta** em cada linha abre a ficha na tela Maletas. "Atrasada" é escrita na coluna Situação (não só cor); vendedor acima do limite ou com maleta atrasada também sai em palavras.
- **Três moedas pela cotação do envio:** Dólar e Guarani de cada linha e do rodapé vêm prontos do servidor (cotação do dia do envio da maleta); a tela e os arquivos usam esses valores e a data da cotação de cada linha, sem recalcular com a cotação de hoje. Limite e sobra do limite saem só em Real.
- **Nota de remessa (PDF):** ponte nova `maletas.nota` (rota da 3.4) e `gerarPdfDaNota` no gerador à mão da 2.8 (agora com `montarArquivoPdf` compartilhado): A4 em pé, Corona Semijoias, número, vendedor, praça, semana, tipo, data do envio, tabela (código, peça, quantidade, preço, subtotal) com nomes longos quebrados em linhas, totais, Real e Dólar/Guarani do dia do envio com o aviso "Cotação do Banco Central do dia do envio", marca **CANCELADA** (com o motivo) e linhas de assinatura da dona e do vendedor. Maleta grande vira várias páginas: cabeçalho curto e títulos da tabela repetidos; totais e assinatura só na última (se não couberem, ganham uma página). Sem foto.
- **Onde aparece:** botão **Imprimir nota** na lista (maleta enviada ou cancelada depois de enviada), na ficha da 3.5 e na da 3.6; rascunho não tem botão. O arquivo sai pelo mesmo `entregarArquivo` da 2.8 (o Windows pergunta onde guardar); a tela não escolhe caminho.
- **Testes:** 6 do gerador (páginas, texto, assinatura só na última, cancelada, sem cotação, leitura da rota), 5 de relatórios (menu, Atrasada, Dólar do envio, prazo, ficha, arquivo, limite), 3 do botão da nota e 1 de rota da ponte. Lint, typecheck, format e testes do owner-app (587) passando.

**Decisões que tomei**

- O gerador à mão aguentou a nota (várias páginas, quebra de linha, assinatura): sem troca de biblioteca, portanto sem ADR novo.
- O total em Dólar/Guarani do rodapé é o do servidor (soma das linhas já convertidas); a soma local da 2.8 não roda nos relatórios de maleta.
- O número da maleta no nome do arquivo (`nota-da-maleta-S12-V7-1.pdf`) é o do servidor, limpo de símbolos.
- Maleta cancelada depois do envio imprime a nota com a marca CANCELADA; cancelada antes do envio não tem nota (o servidor recusa e a tela mostra a mensagem dele).
- O filtro **Produto** do glossário não entrou; **Buscar** já acha por peça e código nos cinco relatórios.

**Dúvidas abertas:** as do ADR-0010 (a 5, prazo de atraso: hoje 7 dias, editável na tela). Foto no PDF segue fora (miniatura é dúvida aberta). A dona quer um número de maleta sequencial próprio em vez de S12-V7-1 (dúvida da 3.4)?

**Não feito:** foto na nota, filtro por produto específico, conferência e venda (app do vendedor).


### Tarefa 3.8: fechamento da Fase 3

**Mesclada.** Revisão pós-fase (como a 2.9): leitura do backend das remessas e da maleta, das consultas e das telas, procurando erro real de estoque e de dinheiro. Dois erros achados e corrigidos com teste; o resto foi conferido e registrado.

**O que foi feito**

- **Erro 1, peça excluída com a peça ainda com o vendedor** (teste primeiro, falhava com 204 no lugar de 409): excluir a peça pelo cadastro tirava o produto do estoque, e a devolução e o cancelamento da maleta passavam a responder "não encontrado" (o serviço de estoque trata produto excluído como inexistente), deixando as peças presas. Agora excluir uma peça que tem saldo numa maleta enviada é recusado (409, `PECA_ESTA_EM_MALETA`: "Esta peça ainda está com um vendedor numa maleta e não pode ser excluída. Receba a devolução das peças antes."). Depois de devolver, a exclusão passa.
- **Erro 2, tela Conferência do estoque sem as maletas**: a rota `/estoque/conciliacao` já trazia `diferencasComMaletas`, mas a tela usa o relatório `/relatorios/estoque/conciliacao`, que só olhava o saldo contra os movimentos. O relatório agora também lista a peça quando "estoque da loja + peças nas maletas" não fecha com o que entrou e saiu fora da maleta, e traz `nasMaletas` e `diferencaComMaletas`; a tela ganhou as colunas **Com os vendedores (maletas)** e **Diferença contando as maletas**. Teste primeiro (provocando a diferença no banco). Clientes da API regerados.
- **Maleta com tudo devolvido** (decisão abaixo): a ficha mostra o aviso **Sem peças com o vendedor** e a visão **Por vendedor** só conta como maleta a que ainda tem peça. Testes de tela.
- **Conciliação de ponta a ponta:** o teste da 3.3 (`conciliacaoComMaletasFechaDepoisDeCompraRemessaDevolucaoCancelamentoEstornoEInventario`) já cobre compra, remessa, devolução, cancelamento, estorno e inventário e foi mantido; a 3.8 só acrescentou o relatório da tela.
- Documentos: tabela da Fase 3 com os PRs, "Fase 3 concluída", pendências, ADR-0010 **Aceito**.

**Conferido sem achar erro (já com teste ou lido no código):** envio duplo (200 sem baixar de novo, trava por vendedor), devolução repetida (200/409 pelo id), cancelamento x devolução simultâneos (trava a remessa antes dos produtos), desfazer devolução com o estoque central sem saldo (409 com texto simples), limite (exato passa, um centavo a mais não, devolução libera, cancelada e rascunho não contam, 0 = sem limite), peça inativada ou excluída antes do envio (envio recusado, rascunho continua legível e a peça pode ser tirada), preço congelado só no envio (o total do rascunho usa o preço de hoje e é recalculado no envio), relatórios (cotação do dia do envio, rascunho fora, cancelada marcada e fora do total), `operationId` explícito em todas as rotas de maleta e permissão (vendedor 403 e sem login 401 nas rotas de remessa, maleta, nota e relatórios; teste existente).

**Decisões que tomei**

- **Maleta com saldo zero continua ENVIADA:** quem encerra é o acerto (Fase 7). Responde a dúvida da 3.3 e da 3.6.
- Peça com saldo numa maleta enviada não pode ser excluída (regra nova, no servidor); inativar continua permitido.
- `diferencaComMaletas` = (o que entrou e saiu fora da maleta) menos (estoque da loja + peças nas maletas): positivo = faltam peças.

**Dúvidas abertas:** as seis do ADR-0010 (preço de venda x custo no limite, reposição no limite, peça repetida na maleta, foto na nota, prazo de atraso de 7 dias, vendedor sem limite) e o formato do número da maleta (hoje S12-V7-1).

**Não feito / fica para depois:** teste com leitor físico e app instalado (com o responsável); conferência, dispositivo único e leitura da própria maleta pelo vendedor (Fase 4); excluir peça que só está em rascunho não foi bloqueado de propósito (o envio já recusa e a pessoa tira a peça). Do que a revisão pediu para as telas (código desconhecido, leitor em foco, erro do servidor, 200 x 201, estado após cancelar e devolver), só li os testes existentes da 3.5 e da 3.6, que cobrem esses casos; não abri o app na mão.
