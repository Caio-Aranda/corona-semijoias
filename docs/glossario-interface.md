# Glossário da interface

Referência única das palavras, dos textos padrão e das mensagens que aparecem nas telas dos dois apps (app da dona e app do vendedor). Vale a regra da §19.1 da especificação: a tela fala a língua da loja, não a do programador. Quem usa é uma pessoa leiga em tecnologia.

Como usar:

- Antes de escrever um texto de tela, procure o termo aqui. Se não existir, acrescente primeiro neste arquivo.
- A especificação (`docs/ESPECIFICACAO.md`) manda. Se este glossário e a especificação divergirem, vale a especificação e a divergência vira dúvida (parte 10).
- Português simples em todo o arquivo, exceto na coluna "Equivalente técnico", que existe só para a equipe de desenvolvimento e **nunca vai para a tela**.

## Decisões tomadas

Em 09/11/2026 o responsável resolveu as dúvidas que estavam abertas neste glossário. A da "Entrada" (parte 10) foi resolvida em 13/11/2026: no sistema novo **não existe entrada nem cheque**, e no acerto o vendedor paga em **Dinheiro**, **Transferência** ou **Boleto** (ADR-0012, aceito em 13/11/2026).

- Na tela, "vendedor" até 18/11/2026. **Desde 18/11/2026 (§23.1, item 4) o programa fala em "Representante" (quem usa o Corona Vendas) e "Cliente" (quem leva peças em consignado), nos dois aplicativos e nos dois idiomas; ver a parte 22.**
- Cancelar venda segue a §19.5: "Cancelar venda", com a explicação de que é um novo lançamento. A palavra "anular" não é usada.
- Na tela, sempre "ler o código" ("Leia o código de novo"). "Bipar" e "bipe" nunca aparecem na tela.
- **Tam./Ton.** (es: Talle/Tono): tamanho ou tom da peça, campo opcional do produto (9.8). **Dt. Acerto** (es: Fecha de cobranza): a data do acerto na Cobrança. **R 0000065-01**: o recibo, com o prefixo R (a nota não tem prefixo).
- Nomes dos aplicativos: **Corona Gestão** (app da dona) e **Corona Vendas** (app do vendedor), com ícones diferentes (§18).
- "Produto" no cadastro, na consulta e nos relatórios. "Peça" na maleta, na venda, na troca, na devolução e na leitura do código.
- Senha nova: pelo menos 8 caracteres, com letras e números, diferente da senha temporária.
- Senha da própria dona: outro admin redefine. Sem outro admin, vale um procedimento documentado no servidor, sem e-mail. Na tela da dona: "Se esqueceu a senha, fale com o suporte do sistema."
- Prazo offline (10 dias) esgotado: as vendas guardadas nunca se perdem. O Corona Vendas bloqueia vendas novas, pede para conectar e envia tudo assim que houver internet.
- Envios esperando: "N vendas aguardando envio" quando só houver vendas; "N itens aguardando envio" quando houver outros registros (clientes, devoluções etc.).
- Bloqueio por tentativas: 15 minutos depois de 5 erros seguidos. Os dois números são configuráveis.
- Os nomes de relatório da §12 são descrição interna. O nome de tela de cada relatório segue este glossário e é definido na tarefa daquele relatório.
- Três moedas (09/11/2026): tudo é gravado em Real; todo valor da tela aparece também em Dólar e em Guarani, pela cotação do Banco Central do dia de cada lançamento (§7.5, ADR-0006). Formatos na parte 8; como mostrar e textos na parte 11.

## 1. Termos da loja

| Na tela | Equivalente técnico | Observação |
| --- | --- | --- |
| dona | perfil `ADMIN` | Quem é dona da loja e usa o app da dona. |
| vendedor | `vendedores`, perfil `VENDEDOR` | Quem leva a maleta e vende. Na tela, sempre "vendedor". |
| cliente | `clientes` | Quem compra do vendedor. |
| maleta | remessa (`remessas`) | Conjunto de peças entregue ao vendedor, com quantidade e preço fixados. A palavra "remessa" só aparece no PDF "Nota de remessa" (§19.1). |
| reposição | remessa do tipo `REPOSICAO` | Maleta complementar, sempre criada pela dona. |
| acerto | `acertos` | Encontro semanal em que o vendedor devolve o que sobrou, repassa o dinheiro e recebe a comissão. |
| pedir acerto | `POST /api/vendedor/acertos` | Botão do vendedor no Corona Vendas (tarefa 8.10): as maletas dele passam a ficar em acerto. Só com internet; só a dona fecha ou desiste. |
| contar as sobras | `leituras_acerto` | O vendedor lê o código das peças que sobraram, como **Boa** ou **Com defeito**, e pode desfazer uma leitura (tarefa 8.10). Na tela, "ler o código", nunca "bipar". |
| semana | `semanas` | Período de controle: maleta, cobrança e acerto são semanais. Como numerar as semanas: a confirmar (parte 9). |
| peça | produto / item de maleta, venda ou troca | Usar na maleta, na venda, na troca, na devolução e na leitura do código. |
| produto | `produtos` | Usar no cadastro, na consulta e nos relatórios (por exemplo, o botão "Cadastrar produto"). |
| código de barras | `produto_codigos` | Pode haver mais de um por peça. Mostrado sem máscara, como texto. Quando a tela precisa distinguir, usar "código da loja" ou "código do fornecedor". |
| código da loja | `produto_codigos`, tipo `LOJA` (COD) | O código que a loja imprime na própria etiqueta. Sequencial, interno. |
| código do fornecedor | `produto_codigos`, tipo `FORNECEDOR` (COD Fábrica) | O código que o fornecedor deu à peça (letras e números). Pode haver o mesmo em fornecedores diferentes: a tela avisa e a dona decide. |
| ler o código | bipar | O leitor "digita" o código. Na tela, sempre "ler o código" ("Leia o código de novo"). |
| Corona Gestão | app da dona (`owner-app`) | Nome do aplicativo da dona. Ícone diferente do Corona Vendas (§18). |
| Corona Vendas | app do vendedor (`seller-app`) | Nome do aplicativo do vendedor. Ícone diferente do Corona Gestão (§18). |
| praça | `pracas` | Região ou rota atendida pelo vendedor. |
| garantia | prazos de arrependimento e de defeito | Prazo em que o cliente pode devolver ou trocar a peça. |
| troca | `trocas` | O cliente devolve a peça A e leva a peça B. |
| devolução | `devolucoes` | O cliente devolve uma peça. Também vale para o vendedor que devolve peças à dona no acerto. |
| devolver o dinheiro | reembolso | O cliente recebe de volta o valor pago. |
| peça para a loja | `itens_devolucao.destino_peca = LOJA` | Peça devolvida pelo cliente que não volta à maleta (com defeito, ou boa de maleta já acertada). O vendedor a entrega à dona no próximo acerto (ADR-0014, item 7). |
| crédito do cliente | `creditos_cliente` | Valor que sobra de uma troca (ou que volta da devolução de uma compra paga com crédito) e que o cliente usa numa compra futura, com o mesmo vendedor. |
| conferência | `CONFERIDA`, conferência da maleta | O vendedor confere as peças recebidas contra a lista da maleta. |
| painel da semana | `GET /api/dona/painel-semanal` | A tela da dona com o que foi enviado, devolvido e vendido na semana, vendedor por vendedor (aba **Maletas**), e os totais (aba **Totais**). Abre pelo botão grande **Painel da semana** da tela inicial. Atalhos no topo: **Nova maleta**, **Acerto**, **Reposição**, **Troca**, **Vendedores**, **Estoque**. Filtros: **Semana** (padrão **Semana de hoje**), **Vendedor**, **Praça** e **Situação** (**Maleta em aberto**, **Acerto em andamento**, **Acertado**). Linha sem acerto fechado leva a marca **Prévia** ("Prévia: ainda não é o acerto. Os números podem mudar até o acerto ser fechado."). Clicar no vendedor abre o acerto ou a maleta; **Ver os registros** abre o relatório de onde o total saiu. Guardar em **PDF**, **Excel** ou **CSV**. Em Real, com Dólar e Guarani abaixo, pela cotação do dia de cada lançamento; sem cotação, "Sem cotação" e só o Real. |
| conferência de hoje | `rodadas_conferencia` | O resultado da conferência que o sistema faz sozinho todo dia de madrugada (e quando a dona pede): se o dinheiro, as contas, o estoque e as devoluções batem. A dona vê na tela **Conferência de hoje** e em **O que precisa da sua atenção**. |
| Conferir agora | pedir uma rodada | Botão da dona que faz a conferência na hora, sem esperar a da madrugada. |
| diferença | `diferencas_conferencia` | O que a conferência achou que não bate, mostrado como "Devia ser X; está Y.". Nada é corrigido sozinho. |
| cobrança | cobrança | Recebimento do cliente pelo vendedor e repasse do vendedor à dona. |
| repasse | repasse no acerto | Dinheiro que o vendedor entrega à dona no acerto. |
| comissão | `comissoes` | Parte da venda que fica com o vendedor. |
| pendência | `pendencias_acerto` | Peça que faltou ou valor que ficou em aberto no acerto. |
| pedido de autorização | `autorizacoes_excecao` | O vendedor pede à dona para aceitar uma devolução fora do prazo. |
| limite de consignação | `limite_consignacao`, `limite_consignacao_ilimitado` | Valor máximo, em reais e a preço de venda, das peças que o vendedor pode ter em mãos. É obrigatório: a dona preenche o valor ou marca **Sem limite**. Zero vale zero (não é "sem limite"). Sem uma das duas escolhas, o vendedor não recebe maleta. Na lista de vendedores, a coluna **Limite de consignação** mostra o valor em reais, **Sem limite** ou **Limite não definido**. |
| foto | `produtos_fotos` | Uma foto só por peça, para reconhecer a peça. Aparece junto com a peça sempre que ela é lida ou pesquisada (§20). Na tela da dona, o botão é **Trocar foto** (a nova substitui a anterior); não existe "foto principal" nem ordem das fotos. |
| estoque | `produtos.estoque_atual`, estoque central | Quantidade de cada produto guardada na loja (fora das maletas). Ver parte 12. |
| entrada de compra | `compras`, `itens_compra` | Registro das peças que chegaram de um fornecedor. Na tela, "Receber compra". Ver parte 12. |
| entradas e saídas | `movimentacoes_estoque` | Histórico de tudo o que aumentou ou diminuiu o estoque. A palavra "movimentação" nunca aparece na tela (parte 2). |
| ajuste | movimento `AJUSTE` | Correção do estoque feita pela dona, sempre com o motivo. |
| contagem do estoque | `inventarios` | Conferir peça por peça o que há na loja, lendo os códigos. A palavra "inventário" não aparece na tela. |
| diferença | divergência, `itens_inventario` | Quando o que foi contado não bate com o que o sistema esperava. |
| estoque mínimo | `produtos.estoque_minimo` | Quantidade abaixo da qual o sistema avisa que está acabando. |
| produtos parados | relatório de produtos parados | Produtos que não saem do estoque há muito tempo. |
| sem foto | imagem padrão "sem foto" | Imagem com a marca, nunca um espaço quebrado. A falta de foto nunca trava a venda. |
| cliente (do vendedor) | diretório de clientes da loja | O vendedor não tem "os clientes dele": o cliente é da loja e qualquer vendedor vende para ele. Vendas, fiado e pagamentos continuam só de quem vendeu (ADR-0011, item 13). |
| Procurar pelo nome | busca na própria maleta (`/pecas/busca`) | Corona Vendas, nas telas Vender e Conferir maleta: "A etiqueta não lê? Procure pelo nome." Procura só entre as peças da maleta do vendedor (nome, referência ou parte do código); mostra miniatura, nome, código e "Na maleta: N"; escolher age como ler o código. |
| COD Fábrica | código do tipo `FORNECEDOR` | O código que o fornecedor deu à peça. Aparece no painel da peça lida, junto com a categoria e "Na maleta: N" (na conferência, "Esperado: 3 · Contadas: 2"), quando existir. |
| Acabou · Está em maleta | `estoque_atual` e saldo das maletas | Estoque da loja em zero **sem** peça em maleta: **Acabou** (destaque). Em zero **com** peças nas maletas dos vendedores: **0 na loja** e "**Está em maleta: 2 (Maria, João)**" (até três nomes; com mais, "e mais N"). Só no Corona Gestão. Na montagem da maleta: "Sem estoque na loja. Está na maleta de Maria." |
| cotação | taxa PTAX de venda do Banco Central, tabela `cotacoes` (ADR-0006) | Quanto valem o Dólar e o Guarani em reais num dia, segundo o Banco Central. Sempre com a data: "Cotação do Banco Central de 06/11/2026". Ver parte 11. |
| O que precisa da sua atenção | bloco da tela inicial da dona | Acertos pendentes, devoluções para autorizar, estoque baixo, maletas atrasadas, envios com problema (§19.3). |

Botões da tela inicial (§19.3 e §19.7):

- App da dona: **Montar maleta**, **Receber compra**, **Fazer acerto**, **Cadastrar produto**, **Relatórios**.
- App do vendedor: **Vender**, **Minha maleta**, **Clientes**, **Meus números** e, desde 13/11/2026 (tarefa 6.8), **Devolução e troca**.

- **Clientes (11/11/2026):** os clientes são **da loja**. O vendedor busca e vende para qualquer cliente; vê só nome, CPF, telefone e cidade dos que outro vendedor cadastrou (nascimento, WhatsApp e observações só dos que ele cadastrou). CPF repetido: "**Este cliente já está cadastrado: {nome}.**" com o botão **Usar este cliente**.

## 2. Proibido na tela

Nunca aparecem em tela: as palavras dos dois blocos abaixo, mensagens em inglês e códigos de erro (como "401" ou "500"). Códigos de erro não entram na lista automática porque números dariam falsos alertas; a regra vale por escrito: **nada de código de erro na tela**.

Uma tarefa futura vai ler os dois blocos para conferir as telas automaticamente. Por isso cada bloco tem **uma palavra ou expressão por linha**, entre marcadores. Não mude os marcadores nem junte linhas.

**Bloco A:** verificação **sem diferenciar maiúsculas de minúsculas**. A verificação deve considerar também plural e outras formas da palavra (por exemplo "movimentações", "remessas", "sincronizar"). Exceção: "remessa" é permitida no PDF da nota de remessa (§19.1) e em nenhuma outra tela.

<!-- PROIBIDAS-A:INICIO -->
```
outbox
sync
sincronização
fila de sincronização
payload
UUID
token
refresh
error
failed
invalid
unauthorized
forbidden
timeout
null
undefined
Exception
stack trace
movimentação
remessa
bipar
bipe
PTAX
```
<!-- PROIBIDAS-A:FIM -->

**Bloco B:** verificação **só da forma exata, em maiúsculas**. São os identificadores técnicos de situação. Os mesmos textos em letras normais ("Aberta", "Boa", "Cancelada") são permitidos e são os que a tela mostra (parte 3).

<!-- PROIBIDAS-B:INICIO -->
```
RASCUNHO
ENVIADA
CONFERIDA
EM_ACERTO
ACERTADA
CANCELADA
ABERTA
FECHADA
BOA
DEFEITO
A_COMPENSAR
COMPENSADO
DEVOLVIDO
```
<!-- PROIBIDAS-B:FIM -->

## 3. Situações na tela

A tela mostra o texto da coluna "Na tela". A coluna "Equivalente técnico" é só para a equipe.

| Assunto | Equivalente técnico | Na tela | Observação |
| --- | --- | --- | --- |
| Maleta | `RASCUNHO` | Em montagem | A dona ainda está montando. Ainda não foi enviada. |
| Maleta | `ENVIADA` | Enviada | No app do vendedor, o botão dessa maleta é "Conferir maleta". |
| Maleta | `CONFERIDA` | Conferida | O vendedor já conferiu as peças recebidas. |
| Maleta | `EM_ACERTO` | Em acerto | O acerto da semana está acontecendo. |
| Maleta | `ACERTADA` | Acertada | Acerto concluído. |
| Maleta | `CANCELADA` | Cancelada | Maleta que não seguirá adiante. Continua registrada. |
| Semana | `ABERTA` | Aberta | Aceita lançamentos. |
| Semana | `FECHADA` | Fechada | Não aceita mais alteração (§7.4). |
| Estado da peça | `BOA` | Boa | Volta a ser vendida. |
| Estado da peça | `DEFEITO` | Com defeito | Fica separada e vai para a dona no acerto. Nunca volta a ser vendida. |
| Forma de pagamento | dinheiro | Dinheiro | §7.4. |
| Forma de pagamento do repasse do vendedor | Pix | Transferência (PIX) | Tarefa 7.6: nas telas do recebimento do vendedor à loja (acerto e recebimento avulso) a forma aparece como **Transferência (PIX)** ao lado de **Dinheiro** e **Boleto**; gravada como `PIX`. |
| Forma de pagamento | Pix | Transferência | §7.4. Gravado como `PIX`; na tela, sempre **Transferência**, também no acerto (repasse do vendedor à loja) e no pagamento a fornecedor (ADR-0012, aceito em 13/11/2026). Nas telas do repasse do vendedor, **Transferência (PIX)** (linha acima); nas demais, só **Transferência**. |
| Forma de pagamento do cliente (venda, troca, devolução) | Transferência | Transferência | O cliente paga ou recebe por transferência; gravado como PIX. Nessas telas só **Dinheiro** e **Transferência** (a dona respondeu em 13/11/2026; v1.13). Cartão e Cheque saem das telas, mas pagamentos já gravados continuam aparecendo como "Cartão" e "Cheque". |
| Forma de pagamento | cheque | Cheque (só em pagamento antigo) | No sistema novo **não existe cheque** (ADR-0012, aceito em 13/11/2026): não há tela, situação nem controle de cheque. A palavra só aparece em pagamento de cliente já gravado. |
| Forma de pagamento | boleto | Boleto | §7.4. |
| Forma de pagamento | crédito do cliente | Crédito do cliente | §7.3 (tarefa 6.5). Na lista curta de formas pode aparecer só **Crédito**. Nunca "CREDITO". |
| Crédito do cliente | `ABERTO` | Em aberto | Ainda tem saldo para usar. |
| Crédito do cliente | `UTILIZADO` | Usado | O saldo já foi todo usado (ou transferido pela dona para outro vendedor). |
| Crédito do cliente | `CANCELADO` | Cancelado | A troca ou a devolução que o gerou foi cancelada. |
| Troca | `ATIVA` | Feita | A troca vale. |
| Troca | `CANCELADA` | Cancelada | Desfeita pelo "Cancelar troca". Continua registrada. |
| Forma de pagamento | entrada | (não usar) | No sistema novo **não existe entrada** (ADR-0012, aceito em 13/11/2026). Não aparece em nenhuma tela. |

Pagamento do cliente na venda (§7.2): **À vista** ou **A prazo** (parcelado).

## 4. Botões e posição

### Nomes fixos

| Botão | Quando usar |
| --- | --- |
| Salvar | Guarda o que a pessoa digitou na tela. |
| Cancelar | **Desistir sem salvar.** Fecha a tela e descarta o que foi digitado. Nunca faz nada além disso. |
| Voltar | Volta um passo, sem perder o que já foi preenchido. |
| Continuar | Avança para o passo seguinte nos fluxos guiados (§19.4). |
| Concluir | Termina um fluxo guiado depois da tela de conferência. |
| Fechar | Fecha um aviso ou uma janela só de leitura. |
| Tentar de novo | Repete uma ação que falhou, por exemplo por falta de internet. |
| Atualizar agora | Instala a versão nova do aplicativo. **Não existe no Corona Gestão** (decisão da Fase 9 mínima): a atualização é automática e entra quando a pessoa fecha o programa. O aviso é a faixa "Há uma versão nova. Ela será instalada quando você fechar o programa." (sem botão). |
| Entrar / Sair | Abre e encerra a sessão da pessoa. |
| Mais opções | Mostra os campos raros (§19.2). |
| Exportar | Gera PDF, Excel ou CSV em um clique (§19.10). |

### Ações sem volta têm o nome da ação

Botões de ações sem volta nomeiam o que vai acontecer (§19.5): **Enviar maleta**, **Fechar semana**, **Aprovar reembolso**. Nunca um genérico "OK" ou "Confirmar".

### Cancelar não é apagar (§19.5)

Venda, pagamento e movimento de maleta ou de estoque nunca são editados nem apagados. Corrigir é fazer um **novo lançamento**.

- O botão genérico **Cancelar** é só "desistir sem salvar".
- Cancelar uma venda, um pagamento ou uma devolução é uma ação **nomeada com o objeto**: **Cancelar venda**, **Cancelar pagamento**, **Cancelar devolução**.
- Essa ação sempre pede confirmação, e a confirmação diz que nada é apagado e que o cancelamento fica registrado como um novo lançamento. Modelo:

> **Cancelar esta venda?**
> Nada é apagado. A venda continua registrada e o cancelamento entra como um novo lançamento.
> Botões: **Voltar** e **Cancelar venda**.

Na confirmação, a saída é **Voltar**, nunca "Cancelar", para ninguém confundir as duas coisas.

### Posição (igual em todas as telas, §19.12)

- **Voltar:** canto superior esquerdo da tela, ao lado do título.
- **Rodapé da tela ou da janela, da esquerda para a direita:** **Cancelar**, depois o botão principal (**Salvar**, **Continuar**, **Concluir** ou a ação nomeada).
- O botão principal fica sempre no canto direito, em destaque; **Cancelar** nunca fica no canto direito.
- Em janelas de aviso com um só botão, o botão fica no canto direito.

## 5. Modelo de mensagem

Toda mensagem diz **o que aconteceu** e **o que fazer** (§19.6):

- frases curtas, de uma ideia só;
- trata a pessoa por **você**;
- diz o que fazer, em verbo, como "confira", "escolha", "fale com a dona";
- sem culpar a pessoa;
- sem código técnico e sem palavra em inglês.

**Exemplo da especificação (§19.6):**

> Esta peça não está na maleta da Maria. Confira o código ou escolha outra peça.

### Exemplos bons

1. O desconto passa do limite da loja. Diminua o desconto ou peça autorização à dona.
2. Esta peça acabou na sua maleta. Escolha outra peça ou fale com a dona.
3. Esta semana já está fechada e não aceita mais alterações. Se precisar corrigir algo, fale com a dona.

### Exemplos ruins

| Mensagem ruim | Por que é ruim | Como fica |
| --- | --- | --- |
| Erro 500: falha ao processar a requisição. | Código de erro e linguagem técnica. Não diz o que fazer. | Não foi possível salvar agora. Tente de novo. Se continuar, fale com a dona. |
| Product not found. | Em inglês e sem orientação. | Não encontramos esta peça. Leia o código de novo ou procure pelo nome. |
| Operação inválida para o status ENVIADA. | Termo técnico e identificador em maiúsculas. Não diz o que fazer. | Esta maleta já foi enviada e não pode mais ser alterada. |
| Falha na sincronização da fila. | Usa palavras proibidas (parte 2). | Suas vendas estão salvas e serão enviadas quando você conectar. |

## 6. Mensagens padrão da Fase 0

Textos completos. `{minutos}` é preenchido pelo sistema. Os textos de login não dizem se o erro foi no usuário ou na senha, de propósito, por segurança.

**Login ou senha incorretos**

> **Não foi possível entrar.**
> Usuário ou senha incorretos. Confira o que você digitou e tente de novo.
> Se esqueceu a senha, peça uma nova à dona da loja.

No Corona Gestão (app da dona), a última frase é:

> Se esqueceu a senha, fale com o suporte do sistema.

Quem monta o texto é sempre o servidor (os aplicativos nunca montam texto de erro). Com o cabeçalho de tipo de app (tarefa 12), o próprio servidor acrescenta a última frase certa de cada aplicativo (código `LOGIN_INVALIDO`).

**Bloqueio temporário por tentativas**

> **Acesso bloqueado por alguns minutos.**
> Você errou a senha várias vezes. Por segurança, espere {minutos} minutos e tente de novo.

`{minutos}` é o tempo que **falta** do bloqueio, em minutos inteiros arredondados para cima, no mínimo 1 (quem tenta de novo aos 10 minutos lê "espere 5 minutos", não "espere 15 minutos"). Com 1, o servidor escreve "espere 1 minuto", no singular. O bloqueio padrão é de 15 minutos, depois de 5 erros seguidos no mesmo usuário vindos do mesmo endereço de internet. O mesmo texto vale também quando o bloqueio é do endereço inteiro (muitos erros com usuários diferentes) e quando o servidor recusa por excesso de tentativas ao mesmo tempo. A resposta leva ainda o tempo exato de espera, para o aplicativo (não aparece na tela).

**Usuário desativado**

> **Seu acesso foi desativado.**
> Fale com a dona da loja para voltar a usar o sistema.

**Senha temporária (troca obrigatória no primeiro acesso)**

> **Crie sua senha nova.**
> A senha que você recebeu é temporária. Para continuar, crie uma senha nova que só você saiba.
> Campos: **Senha nova** e **Repita a senha nova**. Botão: **Salvar senha nova**.

**Regras da nova senha**

> Sua senha nova precisa ter:
> - pelo menos 8 caracteres;
> - letras e números;
> - uma senha diferente da temporária.

Avisos no campo:

> A senha nova ainda não está boa. Ela precisa ter pelo menos 8 caracteres, com letras e números.

> As duas senhas não são iguais. Digite a mesma senha nos dois campos.

> A senha nova precisa ser diferente da temporária. Escolha outra.

Senha com mais de 72 bytes (o limite do BCrypt, ADR-0004, item 6). Na tela, nunca aparecem "72" nem "bytes": o texto fala em caracteres, e 36 é a conta de letras com acento, que ocupam o dobro do espaço:

> **Esta senha é longa demais.**
> Escolha uma senha mais curta, com no máximo 36 caracteres.

**Troca de senha obrigatória (resposta do servidor)**

Quem entra com a senha temporária só consegue criar a senha nova. Qualquer outra tela ou chamada recebe o mesmo texto de "Crie sua senha nova", acima. A mensagem que o servidor devolve:

> **Crie sua senha nova.**
> A senha que você recebeu é temporária. Para continuar, crie uma senha nova que só você saiba.

**Senha temporária vencida**

A senha temporária vale por alguns dias (7, na configuração do servidor). Depois disso não entra mais, e o texto só aparece quando a senha estava certa, para não revelar quais usuários existem. Quem monta o texto é sempre o servidor (os aplicativos nunca montam texto de erro). Com o cabeçalho de tipo de app (tarefa 12), o servidor acrescenta (código `SENHA_TEMPORARIA_VENCIDA`) a última frase certa de cada aplicativo, como no "Não foi possível entrar". A dona também pode receber este aviso: a senha temporária dela, gerada pelo comando do servidor ou por outro administrador, vence do mesmo jeito.

> **Sua senha temporária venceu.**
> A senha que você recebeu só vale por alguns dias, e esse prazo acabou.

Última frase, por aplicativo:

- Corona Vendas: "Peça uma senha nova à dona da loja."
- Corona Gestão: "Peça uma senha nova a outra pessoa administradora ou fale com o suporte do sistema."

**Cadastro de usuário pela dona**

Login já usado:

> **Este usuário já existe.**
> Escolha outro, por exemplo juntando o sobrenome.

Na tela, o campo do login se chama **Usuário**. Usuário fora do formato (o servidor tira os espaços das pontas e passa as letras para minúsculas antes de conferir, então maiúsculas não são problema):

> **Este usuário não pode ser usado.**
> Use de 3 a 40 letras sem acento, números ou ponto, sem espaços. Por exemplo: maria.silva.

Usuário que não existe (ao redefinir a senha):

> **Não encontramos esta pessoa no cadastro.**
> Atualize a tela e tente de novo.

A dona não redefine a própria senha pela tela, para não ficar sem acesso:

> **Você não pode redefinir a sua própria senha por aqui.**
> Peça a outra pessoa administradora ou fale com o suporte do sistema.

Inativar o próprio acesso (a dona não se inativa, para não ficar sem acesso):

> **Você não pode inativar o seu próprio acesso.**
> Peça a outra pessoa administradora para fazer isso.

Mudar o próprio tipo de acesso (a dona não se rebaixa):

> **Você não pode mudar o seu próprio tipo de acesso.**
> Peça a outra pessoa administradora para fazer isso.

Inativar ou rebaixar a última pessoa administradora ativa:

> **Precisa continuar existindo pelo menos uma pessoa administradora com acesso.**
> Dê o acesso de administradora a outra pessoa antes de fazer esta mudança.

Editar um usuário que outra pessoa já alterou: vale o texto de cadastro alterado (parte 6, cadastros da dona).

Se a dona esquece a própria senha e não há outro administrador, vale o comando do servidor `redefinir-senha-admin`, que quem opera o servidor roda (passo a passo em `docs/redefinir-senha-da-dona.md`). Ele mostra a senha temporária no terminal, sem tela.

Quando a dona cria o usuário ou redefine a senha, a senha temporária aparece **uma única vez**. Texto da tela (a tela vem na Fase 1): "Anote esta senha agora. Ela não aparece de novo."

**Aplicativo errado para o perfil**

Dona no app do vendedor:

> **Este é o Corona Vendas.**
> Seu acesso é de dona da loja. Para entrar, abra o Corona Gestão.

Vendedor no app da dona:

> **Este é o Corona Gestão.**
> Seu acesso é de vendedor. Para entrar, abra o Corona Vendas.

**Aplicativo desatualizado**

> **Seu aplicativo está desatualizado.**
> Atualize para continuar usando.

No Corona Gestão não há botão **Atualizar agora**: a versão nova é baixada em segundo plano e instalada quando a pessoa fecha o programa; enquanto isso, uma faixa avisa "Há uma versão nova. Ela será instalada quando você fechar o programa." (Fase 9 mínima).

**Sessão expirada**

> **Sua sessão terminou.**
> Entre de novo com seu usuário e sua senha para continuar.

Quando passa o prazo máximo sem internet (Corona Vendas, §11):

> **Este computador ficou muitos dias sem internet.**
> Suas vendas estão salvas e serão enviadas assim que você conectar. Para fazer vendas novas, conecte-se à internet.

**Sem conexão ao entrar no app do vendedor** (nesta etapa o Corona Vendas só entra com internet; o modo sem internet vem na Fase 5)

> **Sem internet.**
> Para entrar no Corona Vendas é preciso internet. Conecte-se e tente de novo.
> Botão: **Tentar de novo**.

**Sem conexão no app da dona** (o app da dona só funciona com internet, §4)

> **Sem internet.**
> O Corona Gestão só funciona com internet. Conecte-se e tente de novo.
> Botão: **Tentar de novo**.

**Erros gerais da API**

Textos que o servidor devolve quando o problema não é de um assunto específico (tarefa 6). O servidor não sabe qual é a tela, então os textos servem para qualquer uma, inclusive consultas. Cada um vai na resposta como título (a frase em negrito) e mensagem (o texto inteiro, que repete o título).

Algo inesperado no servidor:

> **Algo deu errado agora.**
> Algo deu errado agora. Tente de novo. Se continuar, fale com o suporte do sistema.

Endereço que o servidor não conhece (em geral, aplicativo desatualizado):

> **Não encontramos o que você pediu.**
> Não encontramos o que você pediu. Atualize o aplicativo e tente de novo. Se continuar, fale com o suporte do sistema.

Perfil sem acesso à parte pedida (por exemplo, o vendedor numa função da dona; em uso normal, só um aplicativo desatualizado ou errado chega aqui):

> **Você não tem acesso a esta parte do sistema.**
> Você não tem acesso a esta parte do sistema. Atualize o aplicativo e tente de novo. Se continuar, fale com o suporte do sistema.

Dados que não passaram na conferência:

> **Alguns dados não estão corretos.**
> Alguns dados não estão corretos. Confira o que você preencheu e tente de novo.

Campo sem texto próprio (aviso ao lado do campo):

> Confira este campo e tente de novo.

Pedido que o servidor não entendeu (em geral, aplicativo desatualizado):

> **Não foi possível entender o pedido.**
> Não foi possível entender o pedido. Atualize o aplicativo e tente de novo. Se continuar, fale com o suporte do sistema.

**Tela inicial da dona (Corona Gestão)**

Os cinco botões grandes são os da parte 1. Enquanto uma função não existe (Fase 0), o botão mostra:

> **Em breve.**
> {Nome do botão} ainda está sendo preparado. Logo você poderá usar por aqui.

Bloco "O que precisa da sua atenção" quando não há nada a mostrar:

> Tudo em dia por aqui. Quando algo precisar de você, aparece neste espaço.

**Tela inicial do vendedor (Corona Vendas)**

Os quatro botões grandes são os da parte 1 (**Vender**, **Minha maleta**, **Clientes**, **Meus números**) e, no canto, **Sair**. Enquanto uma função não existe (Fase 0), o botão mostra o mesmo texto "Em breve." da tela inicial da dona, com o nome do botão no lugar de {Nome do botão}.

Desde a Fase 4 os quatro botões funcionam (o "Em breve." só vale para função que ainda não existe).

13/11/2026 (tarefa 6.8): a tela inicial ganhou o quinto botão, **Devolução e troca** (parte 6, "Devolução, troca e crédito no Corona Vendas").

**Telas do vendedor (Corona Vendas, Fase 4)**

Os textos moram em `apps/seller-app/src/renderer/textos-pt.ts (e textos-es.ts, em espanhol)`. Termos: **Minha maleta** (peças com saldo, preço e foto), **Conferir maleta** (o vendedor lê o código de cada peça recebida; ação sem volta: **Concluir conferência**), **Vender**, **Concluir venda** (nunca "OK"), **Estornar venda** (o vendedor só estorna no mesmo dia; depois, a dona), **Meus clientes**, **Cadastrar cliente**, **Meus números** (vendas por cliente, peça ou dia; recebimentos por forma; o que os clientes ainda devem). Forma de pagamento do cliente: **Dinheiro**, **Transferência** ou **A prazo (fiado)** (pagamentos antigos ainda mostram Cartão e Cheque). Frases fixas:

> Esta peça não consta na sua maleta. (peça de fora, no leitor)
> Esta peça acabou na sua maleta. Escolha outra peça ou fale com a dona. (saldo zero)
> Esta maleta já está em outro aparelho. Peça à dona para liberar este aparelho.
> O desconto passa do limite que a dona definiu. Diminua o desconto.
> Sem internet. Conecte-se e tente de novo.

Na ficha da maleta, no Corona Gestão: **Conferência do vendedor** mostra "Na maleta", "Ele contou" e "Diferença" por peça; os movimentos aparecem como "Vendida pelo vendedor" e "Venda desfeita (estorno)".

**Banco local do Corona Vendas (Fase 5, tarefa 5.2; ADR-0005 e ADR-0013, itens 15 e 16).** Os dados ficam guardados no computador, protegidos pela senha do vendedor. Textos (o processo principal os devolve; a tela nunca mostra o erro técnico):

> Não conseguimos abrir os dados deste computador. Conecte à internet para baixar tudo de novo. Se havia vendas ainda não enviadas, avise a dona. (dados ilegíveis)

> Este Corona Vendas é mais antigo que os dados deste computador. Atualize o Corona Vendas para continuar. (dados de versão mais nova)

> Não foi possível preparar os dados deste computador. Feche e abra o Corona Vendas de novo. Se continuar, avise a dona. (atualização dos dados falhou; nada se perdeu)

> O computador está sem espaço. Libere espaço e tente de novo. (disco cheio: a venda não foi gravada pela metade)

> O computador está com pouco espaço. Libere espaço para o Corona Vendas continuar guardando as vendas. (aviso ao abrir, com menos de 500 MB livres)

**Operações feitas sem internet no Corona Vendas (Fase 5, tarefa 5.6; ADR-0013, itens 2, 7 e 19).** A venda, o estorno, o cliente e a conferência ficam guardados no computador e são enviados sozinhos. Textos quando a operação não pode ser guardada (o processo principal os devolve):

> Esta peça não consta na sua maleta. (peça fora da maleta)

> Não há mais peças desta na sua maleta. Confira a quantidade. (sem saldo)

> O desconto é maior que o valor da peça.

> O desconto passa do limite que a dona definiu. Diminua o desconto.

> O valor pago passa do total da venda. Confira os pagamentos.

> O valor do pagamento precisa ser maior que zero. (pagamento com valor zero ou negativo)

> Esta venda já foi estornada.

> Este cliente já está cadastrado: {nome}. (CPF repetido no diretório do computador, com o botão **Usar este cliente**)

> Não encontramos esta venda neste computador. Atualize a tela e tente de novo. (o mesmo para cliente e maleta)

Quando uma operação não consegue ser enviada 5 vezes seguidas (o envio continua tentando, sem pular):

> Uma venda não conseguiu ser enviada. Avise a dona.

**Telas do Corona Vendas no banco do computador (Fase 5, tarefa 5.7; ADR-0013, item 18).** Vender, Minha maleta, Clientes e Meus números funcionam sem internet. O indicador fica no alto de todas as telas de trabalho e usa os textos da parte 7 ("Com internet. Tudo enviado." etc.). Textos novos:

> Dados até 09/11 às 14:30. (nos relatórios e na lista de clientes; antes do primeiro recebimento: "Ainda não recebemos os dados da loja neste computador. Conecte-se à internet.")

> 1 venda não foi aceita. A dona já foi avisada. / 3 vendas não foram aceitas. A dona já foi avisada. (quando for outro tipo de registro: "1 item não foi aceito. A dona já foi avisada." / "3 itens não foram aceitos. A dona já foi avisada.")

> **Envios que não foram aceitos** (título da lista, aberta pelo aviso). Cada linha diz o que foi ("Venda para {cliente}", "Estorno de venda", "Cliente novo: {nome}", "Mudança no cadastro de {nome}", "Conferência da maleta"), o dia e a hora em que foi feito neste computador e o motivo em palavras simples (o texto que o servidor devolveu). Botão **Entendi**: a linha sai da lista e nada mais é apagado.

> Nenhum envio com problema. (lista vazia)

> Sem motivo informado. Fale com a dona. (quando o servidor não explicou)

> Cotação de 05/11/2026, a última que este computador recebeu. Conecte-se à internet para receber a mais nova. (cotação do dia anterior; a de hoje: "Cotação do Dólar e do Guarani de hoje, 06/11/2026.")

> Este computador ainda não está pronto para guardar as suas vendas. Conecte-se à internet e entre de novo. (o banco do computador não abriu)

> **Editar cliente** · Salvar alterações · Cliente atualizado. · "Só o vendedor que atende o cliente pode mudar o cadastro dele." · Sem mudança: "Você não mudou nada neste cadastro."

Ao abrir **sem internet**, o Corona Vendas pede a senha (decisão de 11/11/2026). Senha errada: o mesmo "Usuário ou senha incorretos. Confira o que você digitou e tente de novo." do login. Depois de 5 erros seguidos:

> A senha foi digitada errada muitas vezes. Conecte-se à internet e entre de novo para continuar.

Sem internet e sem dados neste computador (primeira vez):

> Para entrar no Corona Vendas pela primeira vez é preciso internet. Conecte-se e tente de novo.

**Abrir sem internet, prazo e computador desligado (Fase 5, tarefa 5.8; ADR-0013, itens 12 e 14).** Na tela é sempre **computador** e **internet**; nunca "offline", "sincronização", "fila", "pendente", "quarentena", "token" ou "sessão".

> **Entrar sem internet** (título da tela de senha quando não há internet e este computador já tem as vendas e os dados guardados)
> Digite a senha do seu usuário para abrir o Corona Vendas sem internet. Suas vendas continuam salvas.
> Botão: **Entrar**.

> **Conecte este computador à internet em breve.** (aviso no alto das telas, nos últimos 3 dias do prazo)
> Faltam {N} dias para este computador precisar de internet. Suas vendas estão salvas e serão enviadas assim que você conectar. (com 1 dia: "Falta 1 dia para…"; no último dia: "Hoje este computador precisa de internet…")

> **Este computador ficou muitos dias sem internet.** (prazo acabou; texto da parte 6)
> Suas vendas estão salvas e serão enviadas assim que você conectar. Para fazer vendas novas, conecte-se à internet.

> **A data deste computador parece errada.** (relógio voltado para trás ou mudado)
> Confira a data e a hora do computador e conecte-se à internet para continuar vendendo. Suas vendas estão salvas e serão enviadas assim que você conectar.

> Este computador ficou muitos dias sem internet. Conecte-se à internet para fazer vendas novas. (quando a pessoa tenta vender, estornar, conferir ou cadastrar com o prazo acabado)

> A data deste computador parece errada. Confira a data e a hora do computador e conecte-se à internet para continuar. (a mesma tentativa, quando o motivo é o relógio)

> **Estamos enviando as suas vendas.** (computador desligado pela dona, antes de apagar)
> A dona desligou este computador. Mantenha o computador ligado e conectado à internet enquanto enviamos as suas vendas. Depois, os dados serão apagados.

> **Este computador foi desligado da loja.** (depois de apagar)
> Os dados deste computador foram apagados. Fale com a dona.

> Este computador não pode mais registrar vendas. Fale com a dona. (quando a pessoa tenta vender, estornar, conferir ou cadastrar depois que a dona trocou ou bloqueou este computador, enquanto as vendas são enviadas e antes de os dados serem apagados; correção da troca de computador, issue #127, 13/11/2026)

**Devolução do cliente (Fase 6, tarefa 6.3; ADR-0014, itens 1 a 7 e 11).** O cliente devolve peça de uma venda à vista; a venda não muda e o dinheiro volta na forma escolhida. O servidor calcula o valor; a tela nunca o decide. As telas são das tarefas 6.8 (Corona Vendas) e 6.9 (Corona Gestão); estes textos valem desde já para o que o servidor devolve.

- Motivo (obrigatório): **Desistiu** (arrependimento), **Defeito**, **Não serviu** (tamanho) e **Outro**. Com **Outro**, o campo **Observação** é obrigatório. Com **Defeito**, todas as peças são marcadas **Com defeito**.
- Estado da peça: **Boa** ou **Com defeito** (parte 3).
- Erro raro (issue 208): "Os valores desta devolução não fecharam e nada foi gravado. Tente de novo; se continuar, fale com a dona."
- Para onde vai a peça: **Voltou para a maleta** (boa, de maleta ainda com o vendedor) ou **Peça para a loja** (com defeito, ou boa de maleta já acertada: não volta à maleta e o vendedor a entrega à dona no próximo acerto).
- Situação da devolução: **Aceita**, **Para revisão da dona** (o servidor achou algo que a dona precisa olhar; os motivos estão na parte "Autorização, revisão e antifraude" logo abaixo), **Mantida**, **Não aceita** e **Cancelada**.
- Ler o código na devolução mostra **as vendas com esta peça** (as do próprio vendedor, da mais nova para a mais antiga), com o cliente, o dia, **Vendidas**, **Já devolvidas**, **Ainda podem voltar** e até que dia vale a garantia ("Desistência até 19/11", "Defeito até 12/12"). Venda a prazo aparece marcada e não pode ser escolhida ainda.
- Botões: **Registrar devolução** (ação sem volta) e **Cancelar devolução** (o vendedor só no mesmo dia; depois, a dona).

Textos que o servidor devolve:

> **Esta quantidade passa do que o cliente comprou.**
> Desta venda ainda podem voltar só {N} peça(s) de "{nome}". Confira a quantidade. (com {N} igual a zero: "Todas as peças de "{nome}" desta venda já foram devolvidas.")

> **Passou do prazo da garantia.**
> Passou do prazo da garantia. Peça autorização à dona.

> **Esta venda já tem devolução.**
> Esta venda já tem devolução. Cancele a devolução antes de estornar.

> **Esta maleta está em acerto.**
> O acerto desta maleta está acontecendo. Espere o acerto terminar e registre a devolução de novo.

> **Esta devolução só pode ser cancelada pela dona.**
> Só dá para cancelar a devolução no mesmo dia. Peça à dona para cancelar.

> **Esta devolução já foi cancelada.**
> Esta devolução já foi cancelada e não pode mudar.

> **Esta devolução não pode ser cancelada.**
> A dona já decidiu sobre esta devolução e ela não pode mais ser cancelada. (quando a maleta da peça já não está com o vendedor: "A maleta desta devolução já não está com o vendedor. Fale com a dona.")

> **A peça desta devolução já foi vendida de novo.**
> A peça desta devolução já foi vendida de novo e a devolução não pode ser cancelada. Fale com a dona.

> **Esta devolução já foi registrada com outros dados.**
> Esta devolução já foi registrada com outros dados. Confira as devoluções antes de lançar de novo.

> **Não encontramos esta devolução.**
> Não encontramos esta devolução. Atualize a tela e tente de novo.

Avisos junto ao campo: "Escolha o motivo da devolução: desistência, defeito, não serviu ou outro.", "Conte o motivo da devolução no campo de observação." (motivo **Outro** sem observação), "Com o motivo defeito, marque as peças como com defeito.", "Diga se a peça voltou boa ou com defeito.", "Escolha como o dinheiro foi devolvido: dinheiro, Pix, cartão ou cheque." (mensagem do servidor, que ainda aceita as quatro), "Esta peça não consta nesta venda.", "A mesma peça aparece mais de uma vez com o mesmo estado. Junte as quantidades numa linha só." e "Diga o motivo de cancelar a devolução.".

**Autorização, revisão e antifraude (Fase 6, tarefa 6.4; ADR-0014, itens 5, 6, 10 e 12).** Fora do prazo, ou acima do valor que a dona aprova antes, o vendedor pede autorização à dona; a dona aprova ou nega. Devolução que o servidor acha estranha entra **Para revisão da dona**, e a dona escolhe **Manter** ou **Não aceitar**. Nenhuma das duas decisões mexe na maleta nem no dinheiro já devolvido. As telas são das tarefas 6.8 e 6.9; estes textos valem desde já.

- Situação do pedido de autorização: **Esperando a dona**, **Aprovado (vale até dd/mm)**, **Negado**, **Usado** e **Vencido**. A autorização aprovada vale até o 7º dia depois da aprovação (dias de Brasília) e serve para uma devolução só, da mesma venda, com o mesmo motivo e no máximo as peças pedidas.
- Botões: **Pedir autorização à dona** (vendedor), **Aprovar reembolso** e **Negar** (dona; negar pede o motivo), **Manter** e **Não aceitar** (dona, na devolução para revisão; não aceitar pede a observação).
- Por que a devolução está para revisão (pode haver mais de um): **passou do prazo**, **passou do limite da semana**, **passou do valor que a dona aprova antes**, **autorização que não vale** e **devolveu mais do que o cliente pagou**.
- **Não aceita**: o valor devolvido fica como pendência do vendedor, cobrada no acerto.

Avisos de "O que precisa da sua atenção":

> **Há 1 pedido de autorização de devolução esperando você.** (plural: "Há {N} pedidos de autorização de devolução esperando você.")
> Abra Devoluções para autorizar e escolha Aprovar ou Negar.

> **Há 1 devolução para revisar.** (plural: "Há {N} devoluções para revisar.")
> Abra Devoluções para revisar e escolha Manter ou Não aceitar.

> **Muitas devoluções de {vendedor}.** (um aviso por vendedor)
> Nas últimas 4 semanas, {vendedor} devolveu {P}% do que vendeu. Confira as devoluções dele. (só a porcentagem, sem valor; com devolução e nenhuma venda nas 4 semanas: "Nas últimas 4 semanas, {vendedor} teve devoluções e nenhuma venda. Confira as devoluções dele.")

Textos que o servidor devolve:

> **Não encontramos este pedido de autorização.**
> Não encontramos este pedido de autorização. Atualize a tela e tente de novo.

> **Este pedido de autorização já foi feito com outros dados.**
> Este pedido de autorização já foi feito com outros dados. Confira os pedidos antes de pedir de novo.

> **Esta devolução não precisa de autorização.**
> Esta devolução está no prazo. Registre a devolução direto.

> **Já há um pedido de autorização para esta venda.**
> Já há um pedido de autorização para esta venda. Espere a resposta da dona ou use a autorização aprovada.

> **A dona já decidiu este pedido.**
> A dona já decidiu este pedido de autorização e a decisão não muda.

> **A dona ainda não aprovou esta devolução.**
> Este pedido de autorização ainda não foi aprovado ou foi negado. Espere a resposta da dona.

> **Esta autorização já foi usada.**
> Esta autorização já foi usada numa devolução. Peça outra autorização à dona.

> **Esta autorização venceu.**
> A autorização vale 7 dias e já venceu. Peça outra autorização à dona.

> **Esta devolução não é a que a dona autorizou.**
> Use a mesma venda, o mesmo motivo e no máximo as peças que a dona autorizou.

> **Esta devolução precisa da autorização da dona.**
> Esta devolução passa do valor que a dona aprova antes. Peça autorização à dona.

> **Esta devolução não está para revisão.**
> Só a devolução para revisão da dona pode ser mantida ou não aceita.

> **Esta devolução já foi revisada.**
> A dona já decidiu sobre esta devolução e a decisão não muda.

Avisos junto ao campo: "Diga o motivo de negar o pedido." e "Diga por que a devolução não foi aceita.".

**Troca e crédito do cliente (Fase 6, tarefa 6.5; ADR-0014, itens 8, 9 e 11).** Na troca o cliente devolve peças de uma venda (A) e leva outras (B), numa operação só. O valor de A vira **crédito do cliente**, que paga B na mesma hora. Se B vale mais, aparece a **diferença a pagar** (à vista, a prazo ou com outro crédito do cliente). Se B vale menos, a sobra **fica de crédito** para uma compra futura do mesmo cliente. O servidor calcula os valores; a tela só mostra a prévia. As telas são das tarefas 6.8 e 6.9; estes textos valem desde já.

- Na tela: "Diferença a pagar: R$ 50,00" (B mais cara), "Fica de crédito: R$ 40,00" (B mais barata) e "Sem diferença" (iguais).
- Situação da troca: **Feita** e **Cancelada**. Situação do crédito: **Em aberto**, **Usado** e **Cancelado** (parte 3).
- De onde veio o crédito: **Troca**, **Devolução** (venda paga com crédito que foi devolvida: o crédito volta primeiro, o resto em dinheiro) e **Transferido pela dona**.
- Botões: **Registrar troca** (ação sem volta), **Cancelar troca** (o vendedor só no mesmo dia; depois, a dona), **Usar crédito** (na venda, escolhe o crédito do cliente como forma de pagamento) e **Transferir crédito** (só a dona: passa o saldo inteiro para outro vendedor).
- O crédito só é usado pelo vendedor dono dele e só na venda do mesmo cliente. O estorno de uma venda paga com crédito devolve o crédito.

Textos que o servidor devolve:

> **Não encontramos esta troca.**
> Não encontramos esta troca. Atualize a tela e tente de novo.

> **Esta troca já foi registrada com outros dados.**
> Esta troca já foi registrada com outros dados. Confira as trocas antes de lançar de novo.

> **Esta troca já foi cancelada.**
> Esta troca já foi cancelada e não pode mudar.

> **Esta troca não pode ser cancelada.**
> A dona já decidiu sobre esta troca e ela não pode mais ser cancelada. (quando uma das maletas já não está com o vendedor: "Uma das maletas desta troca já não está com o vendedor. Fale com a dona."; quando a peça que voltou já foi vendida de novo: "A peça que voltou nesta troca já foi vendida de novo e a troca não pode ser cancelada. Fale com a dona.")

> **Esta troca só pode ser cancelada pela dona.**
> Só dá para cancelar a troca no mesmo dia. Peça à dona para cancelar.

> **O crédito desta troca já foi usado.**
> O crédito que sobrou desta troca já foi usado em outra compra e a troca não pode ser cancelada. Fale com a dona.

> **Esta venda é de uma troca.**
> Esta venda faz parte de uma troca. Use Cancelar troca.

> **Esta devolução é de uma troca.**
> Esta devolução faz parte de uma troca. Use Cancelar troca.

> **Não encontramos este crédito.**
> Não encontramos este crédito do cliente. Atualize a tela e tente de novo.

> **Este crédito é de outro cliente.**
> Este crédito é de outro cliente. Use só o crédito do cliente desta venda.

> **Este crédito não tem mais saldo.**
> Este crédito já foi todo usado ou foi cancelado. Escolha outra forma de pagamento.

> **O valor passa do saldo do crédito.**
> O valor passa do que sobra neste crédito. Diminua o valor e pague o resto de outra forma.

> **O crédito desta devolução já foi usado.**
> O crédito desta devolução já foi usado em outra compra e a devolução não pode ser cancelada. Fale com a dona.

> **Esta transferência já foi feita com outros dados.**
> Esta transferência de crédito já foi feita com outros dados. Confira os créditos antes de transferir de novo.

Avisos junto ao campo: "Escolha o crédito do cliente usado no pagamento." (forma crédito sem o crédito), "O crédito só vale com a forma de pagamento crédito do cliente." (crédito com outra forma), "O mesmo crédito aparece mais de uma vez. Use o crédito uma vez só.", "Escolha como o cliente pagou: dinheiro, Pix, cartão, cheque ou crédito do cliente.", "Informe as peças que o cliente devolveu.", "Informe as peças que o cliente levou.", "A peça nova vale menos que a devolvida: não há diferença a pagar. A sobra fica de crédito.", "O crédito da troca não é escolhido na venda: ele entra sozinho.", "Escolha o vendedor que vai receber o crédito.", "O crédito já é deste vendedor. Escolha outro vendedor." e "Este vendedor está desligado. Escolha um vendedor ativo.".

**Devolução, troca e crédito pelo envio do computador (Fase 6, tarefa 6.7; ADR-0014, itens 5, 9 e 14).** O que o vendedor fez sem internet chega pelo envio e o servidor confere de novo. O que aconteceu no balcão **nunca é recusado por prazo ou por saldo**: vai para a dona olhar. As telas do vendedor são da tarefa 6.8 e a da dona é da 6.9; estes textos valem desde já.

- Devolução ou troca que o servidor achou fora do prazo, acima do limite da semana ou acima do valor que a dona aprova antes (vendedor): "A dona vai olhar esta devolução."
- Venda paga com crédito do cliente que, quando chegou, já tinha acabado (vendedor): "O crédito do cliente já tinha acabado. A dona vai olhar esta venda." O crédito paga só o que sobrava nele; o resto fica **a receber do cliente** na venda.
- Botões da dona na venda com crédito que já tinha acabado: **Manter** (o cliente continua devendo o resto) e **Não aceitar** (pede a observação; o valor fica como pendência do vendedor, cobrada no acerto). Nenhum dos dois mexe na venda nem no dinheiro.

Aviso de "O que precisa da sua atenção":

> **Há 1 venda paga com crédito que já tinha acabado.** (plural: "Há {N} vendas pagas com crédito que já tinha acabado.")
> Abra Créditos dos clientes para revisar e escolha Manter ou Não aceitar.

Textos que o servidor devolve:

> **Não encontramos esta venda com crédito para revisar.**
> Não encontramos esta venda com crédito para revisar. Atualize a tela e tente de novo.

> **Esta venda com crédito não está para revisão.**
> A dona já decidiu sobre esta venda com crédito e a decisão não muda.

Aviso junto ao campo: "Diga por que a venda com crédito não foi aceita.".

**Devoluções e créditos no Corona Gestão (Fase 6, tarefa 6.9; ADR-0014, itens 5, 6, 9, 11 e 13; §7.3 e §19).** Desenho **ainda não aprovado pela dona**: segue os padrões das telas da Fase 5. A dona é tratada por "você". No menu, o grupo **Devoluções e créditos** (depois de "Computadores dos vendedores") tem seis telas: **Devoluções para autorizar**, **Devoluções para revisar**, **Devoluções**, **Trocas**, **Créditos dos clientes** e **Garantia e limites**. Os avisos da tela inicial ("Há N pedidos de autorização...", "Há N devoluções para revisar.", "Muitas devoluções de {vendedor}.") ganham o botão **Abrir**, que leva à tela certa (os dois primeiros às duas primeiras telas; o terceiro, a **Devoluções**).

- **Devoluções para autorizar.** Ajuda: "O vendedor pediu autorização para devolver uma peça que passou do prazo da garantia ou do valor que você aprova antes. Aprovando, ele tem 7 dias para registrar a devolução." Filtro **Situação**: **Esperando você** (já vem), **Aprovados**, **Negados**, **Usados**, **Vencidos**. Colunas: **Pedido em**, **Vendedor**, **Cliente**, **Peças**, **Valor**, **Por que precisa de autorização** (**Passou do prazo da garantia** ou **Passou do valor que você aprova antes**), **Motivo da devolução**, **Garantia até** e **O que fazer**. Botões: **Aprovar reembolso** (nunca "OK") e **Negar**. Aprovar pergunta "Aprovar este reembolso?" ("O vendedor poderá registrar esta devolução em até 7 dias. A autorização vale para uma devolução só.") e depois diz "Reembolso aprovado. O vendedor pode registrar a devolução até {dd/mm/aaaa}."; Negar pede o motivo ("Diga o motivo de negar o pedido.") e depois diz "Pedido negado." Na lista dos aprovados aparece **Vale até {dd/mm/aaaa}**.
- **Devoluções para revisar.** Ajuda: "Devoluções que o sistema achou estranhas. O dinheiro já voltou para o cliente. Escolha Manter ou Não aceitar." Colunas: **Devolvida em**, **Vendedor**, **Cliente**, **Valor devolvido**, **Por que está para revisão** e **O que fazer**. Os motivos aparecem, juntos se houver mais de um: **Passou do prazo**, **Passou do limite da semana**, **Passou do valor que você aprova antes**, **Autorização que não vale**, **Devolveu mais do que o cliente pagou**. Botões **Manter** ("Manter esta devolução?" "A devolução vale como está. Nada muda na maleta nem no dinheiro.") e **Não aceitar** ("Não aceitar esta devolução?" "O dinheiro já voltou para o cliente e a peça já está com o vendedor. O valor devolvido fica como pendência do vendedor e será cobrado no acerto."; pede o motivo: "Diga por que a devolução não foi aceita."). Depois: "Devolução mantida." e "Devolução não aceita. O valor ficou como pendência do vendedor."
- **Devoluções** (todas). Filtros **Situação** (**Aceitas**, já vem; **Para revisão**, **Mantidas**, **Não aceitas**, **Canceladas**) e **Vendedor** (**Todos os vendedores**). Colunas: **Devolvida em**, **Vendedor**, **Cliente**, **Peças**, **Motivo**, **Valor devolvido**, **Voltou para o cliente** (em dinheiro e em crédito), **Situação**, **O que fazer**. Motivo da devolução: **Desistência**, **Defeito**, **Não serviu** e **Outro**. Na tela da dona a situação "Para revisão da dona" é **Para revisão**. Botões: **Registrar devolução** e **Cancelar devolução** (pergunta "Cancelar esta devolução?"; "Isto cria um novo lançamento que desfaz a devolução: o dinheiro que voltou para o cliente volta para a venda. Esta devolução continua registrada."; pede o motivo: "Diga o motivo de cancelar a devolução."; botões **Cancelar devolução** e **Voltar**).
- **Registrar devolução (pela dona).** Primeiro **Código da peça** e **Buscar vendas** ("Leia ou digite o código da peça que o cliente devolveu para achar a venda."), com a lista **Vendas com esta peça** (**Vendida em**, **Cliente**, **Total**, **Garantia**: "Desistência até 19/11, defeito até 12/12") e o botão **Escolher esta venda**; venda a prazo aparece com "Esta venda é a prazo: a devolução primeiro abate o que o cliente ainda deve." e sem o botão. Depois, para cada peça da venda: **Vendidas**, **Já devolvidas**, **Ainda podem voltar**, **Quantas voltam** e **Estado da peça** (**Boa** ou **Com defeito**); e os campos **Motivo da devolução**, **Observação** (obrigatória no motivo **Outro**) e **Como o dinheiro foi devolvido** (**Dinheiro**, **Transferência**). Aviso fixo: "A peça devolvida vai para a loja. Ela não volta para a maleta do vendedor." Botões **Registrar devolução** e **Voltar**. Depois: "Devolução registrada." Fora do prazo: "Passou do prazo da garantia desta venda. O vendedor pode pedir a autorização no computador dele, e você aprova em Devoluções para autorizar."
- **Trocas.** Ajuda: "Trocas feitas pelos vendedores. Para desfazer uma troca, use Cancelar troca." Filtros **Situação** (**Feitas**, já vem; **Canceladas**; **Todas**) e **Vendedor**. Colunas: **Trocada em**, **Vendedor**, **Cliente**, **Peças que voltaram**, **Peças que saíram**, **Diferença** ("Diferença a pagar: R$ 50,00", "Fica de crédito: R$ 40,00" ou "Sem diferença"), **Situação** (**Feita** ou **Cancelada**) e **O que fazer**. Botão **Cancelar troca** ("Cancelar esta troca?"; "Isto cria um novo lançamento que desfaz a troca: a peça que voltou sai de novo da maleta e a peça que saiu volta. Não dá para cancelar se o crédito que sobrou já foi usado. Esta troca continua registrada."; motivo: "Diga o motivo de cancelar a troca.").
- **Créditos dos clientes.** Ajuda: "O crédito é o valor que sobra numa troca e que o cliente usa numa compra futura com o mesmo vendedor." Filtros **Situação** (**Em aberto**, já vem; **Usados**; **Cancelados**) e **Vendedor**. Colunas: **Gerado em**, **Cliente**, **Vendedor**, **De onde veio** (**Troca**, **Devolução**, **Transferido pela dona**), **Valor**, **Já usado**, **Saldo**, **Situação**, **O que fazer**. Botão **Transferir crédito** (só no crédito em aberto): "Transferir este crédito?" "O saldo inteiro, {valor}, passa para outro vendedor, que poderá usá-lo na compra deste cliente. O vendedor de agora deixa de poder usar este crédito." com o campo **Passar para o vendedor** (aviso: "Escolha o vendedor que vai receber o crédito.") e os botões **Transferir crédito** e **Voltar**; depois: "Crédito transferido."
- **Garantia e limites.** Ajuda: "Os prazos e os limites das devoluções. Valem para todos os vendedores." Campos: **Dias para o cliente desistir** (dica: "Conta a partir do dia da venda."), **Dias para devolver por defeito**, **Devolução em dinheiro por semana, por vendedor** com a caixa **Sem limite**, **Valor a partir do qual você aprova antes** com a caixa **Não aprovar antes**, e **Avisar quando um vendedor devolver mais de (%) do que vendeu** (dica: "Olha as últimas 4 semanas."). Avisos junto ao campo: "Use um número de dias entre 1 e 365.", "Use uma porcentagem entre 1 e 100." e "Informe um valor maior que zero." Botão **Salvar**; depois: "Garantia e limites salvos."
- Em todas as telas, embaixo da lista: "Dólar e Guarani pela cotação do Banco Central do dia de cada lançamento." (parte 11). Na tela nunca aparecem os nomes técnicos das situações ("EM_REVISAO", "PEDIDA", "CREDITO") nem as palavras "estorno", "sincronização" ou "fila".

**Devolução, troca e crédito no Corona Vendas (Fase 6, tarefa 6.8; ADR-0014, itens 3 a 9, 11, 12 e 14; §19).** Desenho **ainda não aprovado pela dona** (pergunta 29): segue os padrões das telas da Fase 5. Tudo funciona igual com e sem internet: o computador guarda e envia depois. **O valor que a tela mostra é sempre uma prévia**; o que vale é o que a loja calcula quando os dados chegam. Nunca aparecem "Corona Gestão", "estorno" nem os nomes técnicos ("CREDITO", "EM_REVISAO", "PEDIDA").

- **Tela inicial:** o quinto botão **Devolução e troca** (13/11/2026). Avisos das autorizações respondidas: "A dona aprovou o pedido da venda de {cliente}. Registre a devolução até {dd/mm}." (até usar ou vencer) e "A dona negou o pedido da venda de {cliente}." (por 7 dias depois da resposta).
- **Devolução e troca.** Ajuda: "Leia o código da peça que o cliente trouxe." Campo **Código da peça** e botão **Buscar vendas**. Lista **Vendas com esta peça** (as suas, da mais nova para a mais antiga): **Vendida em**, **Cliente**, **Vendidas**, **Já devolvidas**, **Ainda podem voltar**, **Garantia** ("Desistência até 19/11 · Defeito até 12/12") e os botões **Devolver** e **Trocar**. A venda a prazo pode ser escolhida (a devolução primeiro abate o que o cliente ainda deve). A venda de maleta em acerto mostra "O acerto desta maleta está acontecendo. Espere o acerto terminar e registre a devolução de novo.", as duas sem os botões. Sem venda: "Não achamos venda sua com esta peça. Confira o código." Botão **Minhas devoluções e trocas**. Também se chega pela ficha da venda em **Minhas vendas** (botões **Devolver** e **Trocar**).
- **Devolução** ("Devolução de {cliente}"). Para cada peça da venda, com a foto: **Vendidas**, **Já devolvidas**, **Ainda podem voltar**, **Quantas voltam** e **Estado da peça** (**Boa** ou **Com defeito**). Campos **Motivo da devolução** (**Desistiu**, **Defeito**, **Não serviu**, **Outro**), **Observação** e **Como o dinheiro foi devolvido** (**Dinheiro**, **Transferência**). Prévia: "Valor da devolução: R$ X", "Abatido do que o cliente deve: R$ W" (só quando há), "Volta em dinheiro: R$ Y", "Volta como crédito do cliente: R$ Z" (venda paga com crédito; com "O crédito aparece depois do envio."), e para cada peça **Voltou para a maleta** ou **Peça para a loja**. Aviso fixo: "O valor é uma prévia. A loja confere quando os dados forem enviados." Fora do prazo, o botão some e aparece "Passou do prazo da garantia. Peça autorização à dona." com **Pedir autorização à dona**; pedido esperando: "Você já pediu autorização à dona para esta venda. Espere a resposta."; com a autorização aprovada: "A dona aprovou esta devolução. Vale até {dd/mm}." (a devolução usa a autorização sozinha). Confirmação: "Registrar esta devolução?" "Voltam {N} peça(s). O cliente recebe a prévia de R$ X. Depois de registrar, só dá para cancelar hoje." com **Registrar devolução** e **Voltar**; depois, "Devolução registrada."
- **Pedir autorização à dona.** Confirmação: "Pedir autorização à dona?" "A dona vê o pedido quando os dados forem enviados. Se ela aprovar, você tem 7 dias para registrar a devolução." com **Pedir autorização à dona** e **Voltar**; depois, "Pedido registrado. Espere a resposta da dona."
- **Troca** ("Troca de {cliente}"). **Peças que voltam** (como na devolução, sem a forma do dinheiro) e **Peças que o cliente leva** (da maleta conferida: **Código da peça**, "A etiqueta não lê? Procure pelo nome.", **Quantidade** e **Desconto**). Prévia: "Valor das peças que voltam: R$ A", "Valor das peças novas: R$ B" e a diferença ("Diferença a pagar: R$ X", "Fica de crédito: R$ Y" ou "Sem diferença"), com o aviso fixo da prévia. Diferença a pagar: **Como o cliente pagou a diferença** (**Dinheiro**, **Transferência** ou **A prazo (fiado)**). Sem maleta conferida: "Para trocar, confira a sua maleta antes." Confirmação: "Registrar esta troca?" "Voltam {N} peça(s) e o cliente leva {M}. Depois de registrar, só dá para cancelar hoje." com **Registrar troca** e **Voltar**; depois, "Troca registrada. Receba R$ X do cliente.", "Troca registrada. Ficou de crédito para {cliente}: R$ Y." ou "Troca registrada."
- **Vender** com o cliente escolhido e crédito em aberto: "Crédito do cliente: R$ 40,00 em aberto." e o botão **Usar crédito** (o valor já vem com o menor entre o crédito e o total; pode diminuir, nunca aumentar; o resto vai na forma de sempre). No pagamento, a forma é **Crédito do cliente**.
- **Minhas devoluções e trocas.** Filtro de período igual ao de Minhas vendas. Colunas: **Dia**, **Cliente**, **O que foi** (**Devolução** ou **Troca**), **Peças**, **Valor**, **Como voltou** ("Abatido do que o cliente deve: R$ W", "Em dinheiro: R$ X", "Em crédito: R$ Y") e **Situação**: **Aguardando envio** (ainda no computador), **Aceita**, **Para revisão da dona** (com "A dona vai olhar esta devolução."), **Mantida**, **Não aceita**, **Feita** (troca) e **Cancelada**. Botões **Cancelar devolução** e **Cancelar troca** só no mesmo dia; depois, "Só dá para cancelar no mesmo dia. Peça à dona para cancelar." Confirmações: "Cancelar esta devolução?" "Isto cria um novo lançamento que desfaz a devolução: a peça sai de novo da maleta e o dinheiro devolvido volta para a venda. Esta devolução continua registrada." e "Cancelar esta troca?" "Isto cria um novo lançamento que desfaz a troca: a peça que voltou sai de novo da maleta e a peça que saiu volta. Esta troca continua registrada.", com o campo **Motivo** ("Diga o motivo de cancelar a devolução." / "Diga o motivo de cancelar a troca.") e os botões **Cancelar devolução** (ou **Cancelar troca**) e **Voltar**; depois, "Devolução cancelada." ou "Troca cancelada." Embaixo, **Pedidos de autorização**: **Pedido em**, **Cliente**, **Peças** e a situação (**Aguardando envio**, **Esperando a dona**, **Aprovado (vale até dd/mm)**, **Negado**, **Usado**, **Vencido**).
- **Meus clientes:** a ficha mostra "Crédito do cliente: R$ X em aberto." (só os créditos com você).
- **Na venda** (Minhas vendas): o pagamento em crédito tem a forma **Crédito do cliente**; o dinheiro que voltou aparece como **Devolução** e o que voltou de uma devolução cancelada como **Devolução cancelada**; o "a receber" já desconta o devolvido.
- Envios não aceitos, a descrição: "Devolução de {cliente}", "Troca de {cliente}", "Pedido de autorização: venda de {cliente}", "Cancelamento de devolução" e "Cancelamento de troca".

**Relatórios de devolução e troca (Fase 6, tarefa 6.10; §12 e §13).** Menu do Corona Gestão: grupo **Relatórios de devoluções** (depois de "Relatórios da maleta"). Os nomes de tela abaixo são os únicos usados; a §12 só descreve.

| Relatório (§12) | Nome na tela |
| --- | --- |
| Devoluções por vendedor e motivo | **Devoluções por vendedor e motivo** (uma linha por devolução) |
| Taxa de devolução | **Quanto cada vendedor devolveu do que vendeu** |
| Peças com defeito | **Peças que voltaram com defeito** |
| Trocas | **Trocas feitas** |
| Créditos em aberto e utilizados | **Créditos dos clientes: em aberto e usados** |
| Reembolsos | **Dinheiro devolvido aos clientes** |
| Autorizações de exceção | **Pedidos de autorização** |
| Conciliações de devolução e troca | **Conferência das devoluções e trocas** (deve ficar vazio: "Tudo certo: as devoluções, as trocas e os créditos batem com a maleta e com o dinheiro.") |
| Conferência de hoje (tarefa 8.4) | **Conferência de hoje** (grupo **Conferência** do menu): **Última conferência** (data e hora de Brasília, "Feita sozinha pelo sistema" ou "Pedida por <nome>", e "Tudo certo", "Achou N diferenças" ou "Não terminou: <conferências>"), o botão **Conferir agora** ("Conferindo..." enquanto roda), **Conferências anteriores** com **Ver** e o detalhe das diferenças ("Devia ser X; está Y."). Avisos na tela inicial: **A conferência de hoje achou N diferenças** ("Abra a conferência para ver o que não bate. Nada foi mudado sozinho.") e **A conferência de hoje não rodou** ("Uma parte da conferência não terminou. Toque em Conferir agora; se continuar, avise o suporte." ou "A conferência automática não rodou nas últimas 26 horas. Toque em Conferir agora."). Erros: "Uma conferência já está rodando. Espere um pouco e atualize a tela." e "Não encontramos esta conferência. Volte à lista e abra uma das que aparecem.". Em Real; Dólar e Guarani nunca aparecem |
| Conciliações do dinheiro (tarefa 7.8) | **Conferência do dinheiro** (grupo **Conferência** do menu; onze conferências (as duas últimas, de estoque, também mostram "A peça ... nesta maleta não bate" e peças em número), cada uma com "Conferidos: N" e "Tudo certo" ou o que não bate, com "Devia ser ...; está ...". Texto fixo: "O que aparece aqui é erro do sistema, não da loja. Não corrija nada à mão: avise o suporte." Em Real; peças em número; Dólar e Guarani nunca aparecem) |

- Filtros: **De**, **Até** (sobre o dia, em Brasília, em que a devolução, a troca, o crédito, o dinheiro ou o pedido aconteceu), **Vendedor**, **Motivo da devolução** (**Desistência**, **Defeito**, **Não serviu**, **Outro**), **Situação**, **Buscar**, **Limpar filtros**. Situação das devoluções: **Feitas** (já vem escolhida; não mostra as canceladas), **Para revisão**, **Mantidas**, **Não aceitas**, **Canceladas**, **Todas (canceladas marcadas)**. Das trocas: **Feitas** (já vem), **Canceladas**, **Todas**. Dos créditos: **Em aberto e usados** (já vem), **Só em aberto**, **Só usados**, **Cancelados**, **Todos**. Dos pedidos de autorização: **Todos**, **Esperando você**, **Aprovados**, **Usados**, **Negados**, **Vencidos**.
- Devolução cancelada aparece como **Cancelada** e fica **fora do total** ("Devoluções feitas (valem no total)" e "Devoluções canceladas (fora do total)"); igual nas trocas e nos créditos.
- Totais do rodapé: "Devoluções feitas", "Por desistência", "Por defeito", "Por não ter servido", "Por outro motivo", "Voltou em dinheiro", "Voltou em crédito", "Para revisão", "Não aceitas", "Trocas feitas", "Diferença a pagar", "Ficou de crédito", "Créditos gerados", "Créditos já usados", "Créditos em aberto", "Pedidos feitos", "Esperando você", "Aprovados", "Usados", "Negados", "Vencidos", "Vendedores acima do limite". Em **Créditos dos clientes: em aberto e usados** vale a conta: gerados menos usados é igual a em aberto.
- **Quanto cada vendedor devolveu do que vendeu:** colunas **Vendas**, **Vendido**, **Devoluções**, **Trocas**, **Devolvido**, **Por desistência**, **Por defeito**, **Por não ter servido**, **Por outro motivo** e **Porcentagem devolvida**; o vendedor que passou do limite que você definiu em Garantia e limites aparece com **Acima do limite**. A troca conta como uma devolução e uma venda.
- **Peças que voltaram com defeito:** uma linha por peça, com a foto ("Sem foto" quando não há): **Peça**, **Código**, **Quantas voltaram**, **Devoluções**, **Vendedores**, **Valor** e **Última vez**.
- **Dinheiro devolvido aos clientes:** só o dinheiro (**Dinheiro**, **Transferência**); o crédito não entra. A devolução cancelada aparece como **Devolução cancelada**, com o valor negativo, para o total fechar.
- **Conferência das devoluções e trocas:** cinco conferências, cada uma com "Conferidos: N" e a lista do que não bate (vazia quando tudo certo): **Maleta com devoluções e trocas** (cada peça que voltou à maleta ou saiu na troca está registrada uma vez), **Devolvido por peça da venda** (nunca mais do que foi vendido), **Créditos e usos** (créditos gerados menos usos é igual ao que está em aberto), **Dinheiro devolvido** (nunca mais do que o cliente pagou) e **Contas da venda** (o que o cliente pagou, já descontado o que voltou, nunca passa do total da venda menos o devolvido). Em Real; Dólar e Guarani nunca entram na conferência.
- Dólar e Guarani seguem a cotação do dia **de cada lançamento** (da devolução, da troca, de quando o crédito nasceu, do dinheiro devolvido, do pedido). O total em Dólar e em Guarani é a soma das linhas já convertidas. Sem cotação naquele dia: "Sem cotação".
- **Corona Vendas, em Meus números** (só os dados do próprio vendedor, sem custo; segue o aviso de dados da parte 7): **Recebimentos por forma de pagamento** passa a mostrar só dinheiro, Pix, cartão e cheque ("O crédito do cliente não entra aqui. Veja Crédito dos meus clientes."); a seção **Devoluções e trocas no período** (colunas **Motivo**, **Devoluções**, **Valor devolvido**; totais **Devoluções**, **Trocas**, **Valor devolvido**, **Voltou em dinheiro**, **Voltou em crédito**; vazio: "Nenhuma devolução ou troca neste período."); e a seção **Crédito dos meus clientes** (colunas **Cliente**, **Crédito gerado**, **Já usado**, **Em aberto**; total **Crédito em aberto**; vazio: "Nenhum cliente seu tem crédito agora.").

Recusas do computador: os mesmos textos do servidor (partes "Devolução do cliente", "Autorização, revisão e antifraude" e "Troca e crédito do cliente", acima), com "Informe pelo menos uma peça devolvida." e "Informe as peças que o cliente levou." quando faltam peças. O computador não sabe do limite da semana nem do valor que a dona aprova antes: a devolução acima deles chega à dona **Para revisão da dona**.

**Vender só depois de conferir (decisão de 11/11/2026; tarefa 5.3).** A maleta que ainda não foi conferida não vende:

> **Confira a maleta antes de vender.**
> Esta maleta ainda não foi conferida. Confira todas as peças da maleta antes de vender.

**Computadores do vendedor (Fase 5, tarefa 5.3; ADR-0013, itens 13 e 14).** Na tela é sempre **computador** (nunca "aparelho", "dispositivo" ou "identificador"). Situações na tela: **Em uso** (ativo), **Aguardando liberação**, **Trocado** (substituído) e **Bloqueado**. Botões da dona, na ficha do vendedor: **Trocar computador** (libera o computador novo; o antigo só envia o que guardou) e **Bloquear computador** (perdido ou roubado; ação sem volta, com confirmação). Textos que o servidor devolve:

> **Este computador ainda não foi liberado pela dona.**
> Este computador ainda não foi liberado pela dona. Avise a dona para liberar este computador.

> **Este computador foi trocado por outro.**
> A dona trocou este computador por outro. Use o computador novo para vender.

> **Este computador foi desligado da loja.**
> Este computador foi desligado da loja pela dona. Fale com a dona.

> **Este computador já é usado por outro vendedor.**
> Este computador já é usado por outro vendedor. Cada vendedor usa o próprio computador. Fale com a dona.

> **Este computador não pode ser liberado.**
> Este computador foi bloqueado ou trocado e não pode voltar a vender. Peça ao vendedor para entrar no computador que ele vai usar e libere esse.

> **Não encontramos este computador.**
> Não encontramos este computador. Atualize a tela e tente de novo.

Depois de apagar os dados (tarefa 5.8), o Corona Vendas mostra: "Este computador foi desligado da loja. Os dados foram apagados."

**Enviar as vendas guardadas (Fase 5, tarefa 5.5; ADR-0013, item 2).** Textos que o servidor devolve (o vendedor vê o de cada venda não aceita, com o botão **Entendi**; a dona vê em **Envios com problema**, com **Tentar de novo** e **Descartar**):

> **Suas vendas já estão sendo enviadas.**
> Suas vendas já estão sendo enviadas. Aguarde um instante.

> **Muitas vendas de uma vez.**
> Muitas vendas de uma vez. O Corona Vendas vai enviar em partes menores.

> **Esta venda depende de outro registro que não foi aceito.**
> Esta venda depende de outro registro que não foi aceito. A dona já foi avisada.

> **Este cliente é atendido por outro vendedor.**
> Este cliente é atendido por outro vendedor. Só quem atende o cliente muda o cadastro dele. Fale com a dona.

Na tela da dona: "Não encontramos este envio. Atualize a tela e tente de novo." · "Este envio já foi resolvido. Atualize a tela para ver como ficou." · "Este envio é antigo demais para ser repetido. Os detalhes completos ficam guardados por 90 dias. Descarte-o." Ao descartar, o motivo é obrigatório: "Diga o motivo de descartar este envio."

**Avisos dos computadores em "O que precisa da sua atenção" (Fase 5, tarefa 5.11; ADR-0013, item 21; dúvida D4: 3 dias).**

> **O computador de Maria está há 3 dias sem enviar.**
> Peça a Maria para conectar o Corona Vendas à internet. As vendas feitas sem internet só chegam quando o computador conectar.

> **Maria entrou num computador novo.**
> Para Maria vender nesse computador, abra a ficha do vendedor e use Trocar computador. Se não foi Maria, use Bloquear computador.

(Com 1 dia: "há 1 dia". Vários computadores: um aviso por computador.)

**Telas da dona para o Corona Vendas (Fase 5, tarefa 5.10; ADR-0013, itens 2, 13, 14 e 21; §19.5).** No Corona Gestão, o grupo do menu **Computadores dos vendedores** tem quatro telas. Desenho **ainda não aprovado pela dona**: segue os padrões das telas existentes.

- **Computadores** (escolha o vendedor): cada computador mostra a situação (**Em uso**, **Aguardando liberação**, **Trocado**, **Bloqueado**), **Último contato**, **Vendas ainda por enviar** e **Último envio completo** ("Nunca enviou tudo" quando não houver). Botões: **Trocar computador** (só para o que está aguardando liberação) e **Bloquear computador** (só para os que não estão bloqueados).
  - Confirmação de **Trocar computador**: "Trocar o computador de {vendedor}?" · "O computador novo passa a vender. O computador antigo deixa de vender e só envia o que ainda tinha guardado. As maletas dele passam para o computador novo." · botões **Trocar computador** e **Cancelar**.
  - Confirmação de **Bloquear computador**: "Bloquear este computador?" · "Use quando o computador foi perdido ou roubado. Ele deixa de funcionar e os dados dele são apagados. Isso não tem volta. As vendas que ele ainda não tinha enviado ficam guardadas para você aprovar ou descartar em Vendas de computador bloqueado." · botões **Bloquear computador** e **Cancelar**.
- **Envios com problema**: "Vendas e cadastros feitos no computador do vendedor que não puderam entrar." Cada linha: o que foi (**Venda**, **Estorno de venda**, **Cliente novo**, **Mudança no cadastro de cliente**, **Conferência da maleta**), o vendedor, o dia e a hora, e o motivo. Botões **Tentar de novo** e **Descartar** (o motivo é obrigatório). Vazio: "Nenhum envio com problema. Está tudo em dia." Os cadastros de **cliente com CPF repetido** aparecem numa lista à parte, **Clientes com CPF repetido**: "Dois cadastros com o mesmo CPF. Veja os dados, junte os cadastros e descarte este." Descartar: "Descartar este envio?" · "Ele não vai entrar. Fica registrado com o seu motivo." · **Descartar** e **Cancelar**.
- **Vendas de computador bloqueado**: "Vendas que estavam num computador bloqueado e ainda não valem." Botões **Aprovar** e **Descartar** (motivo obrigatório). Aprovar: "Aprovar este envio?" · "Ele passa a valer como se tivesse sido enviado normalmente." Depois de aprovar: "Aprovado." Se o servidor recusar: "Não foi possível aprovar: {motivo}. Ele foi para Envios com problema." Vazio: "Nenhuma venda esperando você."
- **Clientes alterados pelo vendedor**: "Quando o vendedor e você mudam o mesmo dado de um cliente, vale o último que chegou. Aqui está o que foi trocado." Cada linha: cliente, quem mudou, o dia, e **Era** / **Passou a ser** para cada dado. Vazio: "Nenhum dado de cliente foi trocado."
- Avisos em "O que precisa da sua atenção": **Há 3 envios com problema.** (com 1: "Há 1 envio com problema.") · "São vendas ou cadastros feitos no Corona Vendas que não puderam entrar. Abra Envios com problema e escolha Tentar de novo ou Descartar." · **Há 2 vendas de um computador bloqueado esperando você.** (com 1: "Há 1 venda de um computador bloqueado esperando você.") · "Elas ainda não valem. Abra Vendas de computador bloqueado e escolha Aprovar ou Descartar." Cada aviso tem o botão **Abrir**.

**Receber os dados (Fase 5, tarefa 5.4).** Quando o ponto de onde o computador recebe os dados não vale mais (a loja restaurou uma cópia de segurança), o servidor responde:

> **Os dados deste computador vão ser baixados de novo.**
> Os dados deste computador vão ser baixados de novo. Suas vendas guardadas continuam salvas.

**Cadastros da dona (fornecedores, categorias, praças, semanas, restrições, clientes; tarefa 1.2)**

Cadastro que não existe mais (ou já foi excluído):

> **Não encontramos este cadastro.**
> Atualize a tela e tente de novo.

Nome, código, CNPJ ou número de semana que já estão em uso por outro cadastro (o que foi excluído não conta):

> **Este cadastro já existe.**
> Confira a lista ou use outro nome ou código.

Duas pessoas editando o mesmo cadastro (a segunda a gravar recebe o aviso e não apaga a alteração da primeira):

> **Este cadastro foi alterado por outra pessoa.**
> Atualize a tela e faça a alteração de novo.

Semana com dias que já estão em outra semana:

> **Estas datas já fazem parte de outra semana.**
> Escolha dias que não estejam em outra semana.

Avisos junto ao campo (aparecem embaixo do campo, em português simples):

- Campo obrigatório vazio: "Preencha este campo."
- Texto grande demais: "Use no máximo {max} caracteres."
- CPF com número errado: "Confira o CPF: ele não parece estar certo."
- CNPJ com número errado: "Confira o CNPJ: ele não parece estar certo."
- Semana que termina antes de começar: "O último dia não pode ser antes do primeiro dia."

**Cadastro do vendedor (tarefa 1.3)**

Os erros de "não achou", "já existe" e "alterado por outra pessoa" são os mesmos dos cadastros acima. O CPF e o CNPJ repetidos de outro vendedor voltam como "Este cadastro já existe."

Praça ou restrição que ainda está no cadastro de algum vendedor e a dona tenta excluir:

> **Este cadastro está em uso.**
> Troque a praça ou a restrição dos vendedores que a usam e tente de novo.

Documento do vendedor (foto ou PDF) que não é de um tipo aceito:

> **Este tipo de arquivo não pode ser enviado.**
> Envie uma foto (JPG, PNG ou WebP) ou um arquivo PDF.

Documento maior que 10 MB:

> **Este arquivo é grande demais.**
> Envie um arquivo de até 10 MB. Se for uma foto, tire-a com menos qualidade ou reduza o tamanho antes de enviar.

Avisos junto ao campo:

- CPF ou CNPJ do vendedor com número errado: "Confira o CPF ou o CNPJ: o número não parece estar certo."
- Valor em dinheiro negativo nas abas (renda, valor da moradia): "O valor não pode ser menor que zero."
- Campo de lista com opção que não existe (por exemplo, o sexo): "Escolha uma das opções da lista."
- Limite de consignação negativo: "O limite não pode ser menor que zero."
- Limite de consignação vazio: "Informe o limite em reais ou marque \"Sem limite\"."
- Comissão fora de 0 a 100: "A comissão deve ficar entre 0 e 100."
- Praça, restrição ou vendedor que não existe mais: "Esta opção não existe mais. Atualize a tela e escolha de novo."
- Usuário do tipo vendedor sem escolher o vendedor: "Escolha o vendedor deste acesso."
- Arquivo vazio: "Este arquivo está vazio. Escolha outro."

**Produtos e códigos (tarefa 1.5)**

Código que outro produto já usa (a mensagem diz qual produto):

> **Este código já está em uso.**
> O código {código} já é usado pelo produto {nome}. Use outro código ou abra esse produto.

(Quando duas pessoas gravam o mesmo código ao mesmo tempo, o texto é: "Este código já está em uso por outro produto. Use outro código ou abra o produto que já o usa.")

Categoria ou fornecedor que ainda está em algum produto e a dona tenta excluir:

> **Este cadastro está em uso por produtos.**
> Troque a categoria ou o fornecedor desses produtos e tente de novo.

Avisos junto ao campo:

- Peça sem nenhum código: "Informe pelo menos um código para a peça."
- Código de barras com número que não confere: "O código de barras {código} não confere. Leia o código de novo ou confira os números."
- Dois códigos iguais na mesma peça: "O código {código} aparece mais de uma vez nesta peça."
- Mais de um código principal: "Escolha apenas um código principal."
- Preço com mais de duas casas depois da vírgula: "Use no máximo duas casas depois da vírgula."
- Preço negativo: "O valor não pode ser menor que zero."
- Categoria ou fornecedor que não existe mais: "Esta categoria não existe mais. Atualize a tela e escolha de novo."

**Fotos de produto (tarefa 1.6)**

Arquivo que não é uma foto aceita (por exemplo, um PDF ou um programa com nome de foto):

> **Esta não é uma foto que o sistema aceite.**
> Envie uma foto nos formatos JPG, PNG ou WebP.

Arquivo com cara de foto, mas que não abre (danificado):

> **Não foi possível abrir esta foto.**
> O arquivo pode estar danificado. Tire ou salve a foto de novo e envie outra vez.

Foto com tamanho de imagem enorme (mesmo pesando menos de 10 MB):

> **Esta foto é grande demais em tamanho de imagem.**
> Reduza a foto (até 25 milhões de pontos, por exemplo 5000 por 5000) e envie de novo.

Peça que já tem foto (decisão de 11/11/2026: uma foto só por peça, a nova substitui a antiga; não há mais o aviso de "5 fotos"):

> **Foto trocada.**
> A foto nova substituiu a anterior desta peça.

Foto que não existe mais, ou que pertence a outro produto: usa o "não achou" dos cadastros.

Importação de várias fotos (cada foto da lista leva um destes textos, sem derrubar as outras):

- Gravada: "Foto gravada na peça {nome}."
- Já estava na peça: "Esta foto já estava na peça {nome}. Nada mudou."
- Nome sem peça: "Nenhuma peça tem o código {código}. Confira o nome do arquivo ou cadastre a peça."
- Nome repetido: "Há mais de um arquivo para o código {código}. Deixe só um e envie de novo."
- Foto recusada: o mesmo texto do erro correspondente acima.

Avisos junto ao campo:

- Lista de ordem das fotos que não bate com as fotos da peça: "A lista de fotos não confere com a peça. Atualize a tela e tente de novo."


### Corona Gestão: menu, listas e usuários (tarefa 1.8)

**Menu lateral.** Início · Cadastros (Produtos, Fornecedores, Categorias, Praças, Semanas, Restrições) · Pessoas (Vendedores, Clientes, Usuários) · Relatórios de cadastros (um item por relatório, nomes na seção da tarefa 1.12). Item que ainda não foi feito: "{Nome} ainda está sendo preparado. Logo você poderá usar por aqui."

**Listas.** Carregando: "Buscando..." (com o nome do que se busca, por exemplo "Buscando os usuários..."). Nada na lista: "Ainda não há nenhum {item}." Busca sem resultado: "Nenhum {item} encontrado. Confira o que você digitou ou limpe a busca." Total: "{N} usuário" / "{N} usuários". Erro ao buscar: "Não foi possível buscar agora. Tente de novo. Se continuar, fale com o suporte do sistema."

**Sem internet (já com a pessoa dentro).** "Sem conexão com a internet. Tente de novo." Quando a internet volta, o aplicativo tenta sozinho. A pessoa não precisa entrar de novo: só uma recusa do servidor encerra a sessão.

**Alterado por outra pessoa (409 de versão).** Vale o texto do servidor (parte 6, cadastros da dona), e a tela oferece **Atualizar** para buscar o cadastro de novo.

**Usuários (Pessoas → Usuários).**

- Campos: **Nome**, **Usuário**, **Tipo de acesso** (**Dona** ou **Vendedor**) e, para o vendedor, **Vendedor**. Filtros: **Tipo de acesso** (Todos, Dona, Vendedor) e **Situação** (Todos, Ativos, Inativos).
- Botões: **Novo usuário**, **Editar**, **Inativar usuário**, **Reativar usuário**, **Nova senha temporária**.
- Senha temporária (aparece uma única vez, com o botão **Copiar senha**): "Anote esta senha agora. Ela não aparece de novo." e "Entregue a senha só para {nome}. No primeiro acesso, ele ou ela cria uma senha nova." A senha vale por 7 dias.
- Inativar: "{Nome} vai sair do sistema agora e não vai mais conseguir entrar. Você pode reativar depois." Botões: **Voltar** e **Inativar usuário**.
- Reativar: "{Nome} volta a poder entrar com a senha que já tinha. Quem estava dentro precisa entrar de novo." Botões: **Voltar** e **Reativar usuário**.
- Nova senha temporária: "A senha atual de {nome} deixa de valer e quem estiver dentro sai do sistema agora. Será criada uma senha temporária nova." Botões: **Voltar** e **Criar senha temporária**.
- As regras do servidor (não inativar a si mesma, não mudar o próprio acesso, manter uma administradora) aparecem com o texto do servidor, acima.

**Cadastros básicos (Cadastros e Pessoas → Clientes).** Fornecedores, Categorias, Praças, Semanas, Restrições e Clientes usam a mesma tela.

- Botões: **Novo fornecedor / Nova categoria / Nova praça / Nova semana / Nova restrição / Novo cliente**, **Editar**, **Excluir {item}**, **Salvar**, **Cancelar**, **Voltar**.
- Campos: Fornecedor (**Nome do fornecedor (razão social)**, **CNPJ**, **Nome do contato**, **Telefone**, **E-mail**, **Endereço**); Categoria e Praça (**Nome**); Semana (**Número da semana**, **Primeiro dia**, **Último dia**, **Situação**: Aberta ou Fechada); Restrição (**Código**, **Descrição**, **Impede montar maleta para o vendedor com esta restrição**; na lista, coluna **Impede montar maleta** com Sim ou Não); Cliente (**Nome do cliente**, **CPF**, **Telefone**, **WhatsApp**, **Vendedor responsável**, **Observações**, **Endereço**).
- Excluir: "{Nome} some das listas. O que já foi registrado com ele continua guardado. Por aqui não dá para desfazer." (com "ela" nos femininos). Botões: **Voltar** e **Excluir {item}**.
- Depois de salvar: "{Nome} foi cadastrado." (ou "cadastrada"), "Os dados de {nome} foram salvos.", "{Nome} foi excluído." (ou "excluída").
- **Ativo e inativo** (tarefa 1.14; fornecedores, categorias, praças, restrições e clientes; as semanas têm só Aberta e Fechada). Palavras: **Ativo**, **Inativo**, **Inativar {item}**, **Reativar {item}**. Filtro **Situação**: **Ativos** (já vem escolhido), **Inativos**, **Todos**. A lista mostra a coluna **Situação** (Ativo ou Inativo). Inativar não é excluir: o cadastro some das listas de escolha e das buscas, mas continua nos relatórios e no histórico. Desde 17/11/2026 usuários e produtos também dizem **Inativar**, **Inativo** e **Inativos** (antes, "Desativar" e "Desativados"); a mensagem do vendedor "Seu acesso foi desativado." não mudou.
- Inativar (pede confirmação): título "Inativar {nome}?"; texto "{Nome} deixa de aparecer nas listas de escolha e nas buscas, mas continua nos relatórios e no histórico. Pode voltar a qualquer hora, é só reativar." Botões: **Voltar** e **Inativar {item}**. Depois: "{Nome} foi inativado." (ou "inativada") "Pode voltar a qualquer hora." Reativar não pede confirmação: "{Nome} voltou a ficar ativo." (ou "ativa").
- Lista vazia só com inativos: "Nenhum cliente inativo." (e o mesmo para os outros, no feminino quando for o caso). Nas listas de escolha dos formulários (categoria, fornecedor, praça, restrição) só aparece quem está ativo; o que já estava escolhido continua aparecendo com a marca "(inativo)" ou "(inativa)".
- Cliente: campo **Data de nascimento** (opcional; dica: "Se não souber, deixe em branco. Serve para avisar dos aniversariantes."). Data no futuro: "A data de nascimento não pode ser no futuro." Data muito antiga: "Confira a data de nascimento: ela parece muito antiga."
- Número da semana com letra ou vírgula: "Digite só números, sem vírgula nem ponto."

**Cotação do dia na tela da dona.** Rodapé e cartão: "Cotação do Banco Central de 06/11/2026." Quando a cotação em uso é a de um dia anterior (fim de semana, feriado ou busca que falhou): "Cotação do Banco Central de 06/11/2026, a última publicada." Sem nenhuma cotação ainda: "Ainda não há cotação. Os valores em Dólar e Guarani aparecem quando ela chegar."

**Tudo em dia.** Quando não há aviso em "O que precisa da sua atenção": "Tudo em dia."

### Corona Gestão: relatórios de cadastro (tarefa 1.12)

**Nome de cada relatório na tela** (grupo do menu: **Relatórios de cadastros**; a §12 só descreve, o nome de tela é este):

| Relatório (§12) | Nome na tela |
| --- | --- |
| Vendedores (limite, restrição, situação) | **Vendedores: limite e situação** |
| Clientes | **Clientes cadastrados** |
| Fornecedores | **Fornecedores cadastrados** |
| Produtos (com custo) | **Produtos e custos** |
| Produtos sem foto | **Produtos sem foto** |
| Produtos sem código de barras | **Produtos sem código de barras** |
| Códigos duplicados ou em conflito | **Códigos repetidos ou parecidos** |
| Aniversariantes | **Aniversariantes do mês** |
| Alterações de produtos (pela auditoria) | **Mudanças nos produtos** |
| Resultado das importações | **Resultado das importações** |

- **Filtros próprios** (tarefa 1.13): Vendedores: **Limite a partir de (R$)**, **Limite até (R$)**, **Com restrição** (Tanto faz · Só com restrição · Só sem restrição). Clientes: **Vendedor responsável** (Tanto faz · Só com vendedor · Só sem vendedor). Fornecedores: **Produtos ativos** (Tanto faz · Só quem tem produtos · Só quem não tem produtos). Clientes e Fornecedores também têm **Situação** (**Só ativos**, **Só inativos**, **Todos**) e a coluna **Situação**. Aniversariantes do mês: **Quem mostrar** (Vendedores e clientes · Só vendedores · Só clientes), coluna **Quem** (Vendedor ou Cliente) e, no rodapé, **Vendedores** e **Clientes**. Produtos e custos: **Estoque** (Qualquer · Sem estoque · Abaixo do mínimo · Com estoque), **Preço de venda a partir de (R$)**, **Preço de venda até (R$)**, **Foto** (Tanto faz · Só com foto · Só sem foto). Resultado das importações: **O que foi importado** (Planilhas e fotos · Planilha de produtos · Fotos em massa) e **Resultado** (Todos · Criado · Atualizado · Já existia · Recusado).
- **Filtros:** **Buscar**, **Situação** (**Só ativos**, **Só inativos**, **Todos**), **Categoria**, **Fornecedor**, **Praça**, **Vendedor**, **Restrição**, **De** e **Até** (data do cadastro) e **Mês**. Sem escolher, a lista mostra só os ativos. Botão **Limpar filtros**. Data final antes da inicial: "A data final não pode ser antes da data inicial."
- **Totais:** bloco **Totais** no fim da lista, com **Ver os registros** em cada total. Aberto o detalhe: "Mostrando só: {total}." e o botão **Ver o relatório inteiro**. Os totais em Dólar e em Guarani são a soma das linhas já convertidas.
- **Exportar:** **Baixar em PDF**, **Baixar em Excel**, **Baixar em CSV**, sempre o relatório inteiro com os filtros da tela. Preparando: "Preparando o arquivo...". Pronto: "O arquivo {nome} foi preparado. Escolha onde guardar." Sem linhas: "Não há nada para exportar com estes filtros." Muitas linhas: "Este relatório tem linhas demais para exportar de uma vez. Use os filtros para reduzir e tente de novo." Erro: "Não foi possível preparar o arquivo agora. Tente de novo."
- **Cabeçalho do arquivo:** Corona Semijoias, nome do relatório, "Filtros: ...", "Gerado em 10/11/2026 às 09:30 por {nome}", "Cotação do Banco Central de 06/11/2026. Neste relatório, o Dólar e o Guarani seguem a cotação de hoje." No Excel e no CSV, cada valor em dinheiro vira três colunas (**(R$)**, **(US$)**, **(Gs.)**) e há a coluna **Data da cotação**.
- **Avisos fixos:** Mudanças nos produtos: "Mostra o que foi cadastrado e alterado, e quem fez. O resultado das importações em massa está no relatório "Resultado das importações"." Resultado das importações: "Mostra as importações feitas a partir de agora. Na planilha, o resultado é o que o aplicativo informou ao terminar; se a internet cair no meio, as últimas linhas podem não aparecer aqui." Produtos sem código de barras: "Só para informar: o código de barras não é obrigatório, a loja pode usar só o código dela."
- **Listas vazias:** "Nenhum vendedor com estes filtros." (e o mesmo para cliente, fornecedor, produto), "Todos os produtos têm foto.", "Todos os produtos têm código de barras.", "Nenhum código repetido ou parecido.", "Nenhum aniversariante neste mês.", "Nenhuma mudança neste período.", "Nenhuma importação com estes filtros."
- **Códigos em conflito (texto na coluna):** "Parecido com o código de outro fornecedor" · "Parecido com outro código (muda só espaço, traço ou letra maiúscula)" · "Código de barras com número errado ou incompleto".

## 7. Estado de conexão do app do vendedor

Sempre em palavras comuns (§19.11). Nunca "fila", "sincronização" ou "pendentes".

| Situação | Texto |
| --- | --- |
| Com internet, nada a enviar | Com internet. Tudo enviado. |
| Com internet, enviando | Com internet. Enviando suas vendas… |
| Sem internet | Sem internet. Suas vendas estão salvas e serão enviadas quando você conectar. |
| Só vendas esperando para ser enviadas | 1 venda aguardando envio / 3 vendas aguardando envio |
| Também há outros registros esperando (clientes, devoluções etc.) | 1 item aguardando envio / 3 itens aguardando envio |
| Fotos das peças da maleta ainda sendo baixadas (a venda e a conferência seguem normalmente) | Baixando fotos: 45 de 120 |
| Problema num envio | Mostrar em "envios com problema", na tela inicial da dona (§19.3) |
| Corona Vendas abaixo da versão mínima, depois de enviar o que guardou (tarefa 5.13): as vendas continuam sendo enviadas, mas dados novos só chegam depois de atualizar | **Seu aplicativo está desatualizado.** As vendas guardadas continuam sendo enviadas. Para receber dados novos, deixe o computador com internet, feche o Corona Vendas e abra de novo. Se o aviso continuar, avise a dona. |
| Envio feito numa versão antiga do Corona Vendas que o servidor já não aceita (motivo de um envio não aceito, tarefa 5.13) | Este envio foi feito numa versão antiga do aplicativo e não pôde ser aceito. A dona já foi avisada. |

**Aviso nos relatórios do app do vendedor** (§12): "Dados até dd/mm às hh:mm. N itens aguardando envio" (ou "N vendas aguardando envio", quando só houver vendas).

## 8. Formatos

| Dado | Formato | Exemplo |
| --- | --- | --- |
| Dinheiro | R$, ponto nos milhares, vírgula nos centavos, sempre 2 casas | R$ 1.234,56 |
| Dinheiro negativo | sinal na frente | -R$ 50,00 |
| Dólar | US$, ponto nos milhares, vírgula nos centavos, sempre 2 casas | US$ 1.234,56 |
| Dólar negativo | sinal na frente, igual ao dinheiro negativo | -US$ 50,00 |
| Guarani | Gs., ponto nos milhares, **sem centavos** | Gs. 1.379.397 |
| Guarani negativo | sinal na frente, igual ao dinheiro negativo | -Gs. 55.754 |
| Cotação | 4 casas; o Guarani por mil | US$ 1,00 = R$ 5,5013 · Gs. 1.000 = R$ 0,8950 |
| Data | dd/mm/aaaa | 09/11/2026 |
| Hora | 24 horas, horário de Brasília (nunca "UTC" nem "GMT") | 14:30 |
| Data e hora | data, "às", hora | 09/11/2026 às 14:30 |
| CPF | 000.000.000-00 | 123.456.789-09 |
| CNPJ | 00.000.000/0000-00 | 12.345.678/0001-95 |
| Telefone celular | (00) 00000-0000 | (11) 91234-5678 |
| Telefone fixo | (00) 0000-0000 | (11) 1234-5678 |
| CEP | 00000-000 | 01310-100 |
| Porcentagem | número e %, vírgula nos decimais | 10% e 7,5% |
| Quantidade de peças | número e a palavra no singular ou plural | 1 peça, 3 peças |
| Código de barras | só os dígitos, sem máscara | 7891234567895 |

Os exemplos de CPF, CNPJ, telefone e CEP são só para mostrar o formato.

**Por que "Gs." no Guarani:** é a forma que o Paraguai usa nos preços (por exemplo "Gs. 50.000"), usa o mesmo ponto nos milhares do real e não depende do símbolo ₲, que pode faltar nas fontes da tela e do PDF. O Guarani não tem centavos (ADR-0006).

**Por que a cotação do Guarani é por mil:** 1 guarani vale R$ 0,000895. "Gs. 1.000 = R$ 0,8950" é mais fácil de ler e tem as mesmas casas que o Banco Central publica.

## 9. A confirmar

Termos que dependem de resposta do cliente. **Não usar nas telas** até a resposta.

| Termo | O que se sabe | Onde está |
| --- | --- | --- |
| DX | Provavelmente "valor consignado (enviado)". A confirmar. | §2 e §17.1 |
| DV | Provavelmente "valor devolvido". A confirmar. | §2 e §17.1 |
| VD | Provavelmente "valor vendido". A confirmar. | §2 e §17.1 |
| Numeração da semana | Como numerar as semanas e quais são o primeiro e o último dia. A confirmar. | §17.8 |
| Diferença da troca | Se o crédito do cliente pode ser devolvido em dinheiro. A confirmar. | §17.7 |
| Cartão | Não é forma de pagamento hoje. Falta perguntar ao cliente se o cliente final paga com cartão na maquininha do vendedor; se pagar, o cartão entra só como forma registrada, sem integração. A confirmar. | §17.13 |
| Entrada | **Resolvida em 13/11/2026:** no sistema novo não existe entrada (ADR-0012 aceito). A palavra não aparece em nenhuma tela. | §17.12 |

## 10. Dúvidas para a dona

Nenhuma dúvida segue em aberto. As outras foram resolvidas em 09/11/2026 (ver "Decisões tomadas", no topo).

1. ~~**"Entrada".**~~ **Resolvida em 13/11/2026 pelo responsável (ADR-0012 aceito):** no sistema novo não existe entrada, e a palavra não aparece em nenhuma tela (§17.12). Se um dia existir, entra como tarefa nova.

## 11. Três moedas na tela

Tudo é gravado em Real. Dólar e Guarani aparecem ao lado de todo valor, nos dois apps (acertos, relatórios, telas de valores, preço da peça), pela cotação do Banco Central do dia de cada lançamento (§7.5, ADR-0006). Os formatos estão na parte 8.

### Como as três aparecem juntas

- **Ordem fixa:** Real, Dólar, Guarani. Sempre nessa ordem, em todas as telas e relatórios.
- **O Real em destaque:** maior e em negrito, porque é o valor que vale e que fica gravado.
- **Dólar e Guarani logo abaixo** (ou ao lado, quando faltar altura), menores, separados por um ponto no meio. Sempre texto escuro sobre fundo claro, nunca dourado (§18).
- Modelo:

> **R$ 100,00**
> US$ 18,18 · Gs. 111.732

- **Grades e relatórios:** uma coluna para cada moeda, na ordem R$, US$ e Gs., e os totais do rodapé nas três. O total em Dólar e em Guarani é a soma das linhas, então o detalhamento sempre fecha.
- **Linha da cotação**, embaixo de telas de valores e no cabeçalho dos relatórios: "Cotação do Banco Central de 06/11/2026". Nos relatórios de vários dias: "Dólar e Guarani pela cotação do Banco Central do dia de cada lançamento".
- **Minha maleta (Corona Vendas)**, decisão de 11/11/2026 (D1): preço e valor em aberto em Dólar e Guarani pela cotação de hoje, com a frase "Dólar e Guarani pela cotação de hoje." (é preço para vender agora; a venda grava a cotação do dia dela). Sem cotação: "Sem cotação do Dólar e do Guarani por enquanto: os valores aparecem só em Real."
- Ninguém digita cotação. Os campos de valor são em reais, com **uma exceção**: o recebimento do representante (Financeiro → Recebimentos do representante e "Pagou agora" no acerto), em que a dona escolhe a **Moeda** (**Real**, **Dólar** ou **Guarani**) e digita o valor nela. Antes de confirmar, a tela mostra **Fica assim:** com R$, US$ e Gs. e a linha da cotação; depois, a lista e a ficha do acerto mostram **Recebido em Gs. 500.000** (ou **Recebido em US$ 100,00**) junto das três moedas. Sem cotação: "Ainda não há cotação do dia. Por enquanto, registre o recebimento em reais." e só Real. O que vale e fica gravado é o valor em reais que o sistema calculou.

### Textos

**Cotação no acerto** (na tela e no PDF do acerto, sempre, com a data da cotação que o acerto gravou)

> Cotação do Banco Central de 06/11/2026.

O acerto usa uma cotação só, a mais recente no momento do fechamento (ADR-0006). Por isso os valores em Dólar e Guarani do acerto podem ser um pouco diferentes dos do relatório de vendas da mesma semana. O valor em reais é sempre o mesmo e é o que vale.

**Cotação no Corona Vendas, com internet**

> Cotação do Banco Central de 06/11/2026.

**Cotação no Corona Vendas, sem internet** (a última que o computador recebeu é de um dia anterior)

> Cotação de 05/11/2026, a última que este computador recebeu. Conecte-se à internet para receber a mais nova.

Nos relatórios do Corona Vendas, junto do aviso de dados (parte 7):

> Dados até 09/11 às 14:30. Cotação de 06/11/2026. 3 vendas aguardando envio

**Aviso à dona em "O que precisa da sua atenção"** (a cotação de um dia útil não chegou até o fim da tarde)

> **A cotação de hoje ainda não chegou.**
> Os valores em Dólar e Guarani estão usando a cotação de 06/11/2026. O sistema tenta de novo sozinho. Se o aviso continuar amanhã, fale com o suporte do sistema.

Quando o dia sem cotação não é hoje (por exemplo, ontem):

> **A cotação de 09/11/2026 não chegou.**
> Os valores em Dólar e Guarani desse dia estão usando a cotação de 06/11/2026. O sistema tenta de novo sozinho. Se o aviso continuar amanhã, fale com o suporte do sistema.

O aviso some sozinho quando a cotação chega. Fim de semana e feriado não têm aviso: nesses dias o Banco Central não publica cotação, e vale a do último dia útil.

## 12. Estoque e compras (Fase 2)

Termos de tela do estoque e das compras. Vale a regra da §19.1: nada de termo técnico. Toda correção é um novo lançamento; nada é apagado (§19.5).

| Na tela | Nunca na tela | Observação |
| --- | --- | --- |
| Estoque | estoque central, saldo, `estoque_atual` | Quantas peças de cada produto há na loja. As peças que estão nas maletas aparecem em "Maletas" (Fase 3). |
| Receber compra | entrada de compra, entrada de mercadoria, bipar a compra | Botão e tela para registrar o que chegou do fornecedor, lendo o código de cada peça. |
| Compra | pedido de compra, NF de entrada | Uma chegada de peças de um fornecedor, com data, itens e valores. |
| Custo da compra | preço de custo do item | Quanto a loja pagou por peça nesta compra. O custo só aparece para a dona. |
| Entradas e saídas | movimentação, movimento, lançamento de estoque, log | Nome do histórico do estoque, por produto e por período. |
| Entrada · Saída · Perda · Ajuste | `ENTRADA`, `SAIDA`, `AVARIA`, `AJUSTE` | Tipos mostrados nas entradas e saídas. "Perda" é a peça com defeito ou extraviada. "Maleta" e "Devolução da maleta" entram na Fase 3, no lugar de `REMESSA` e `DEVOLUCAO`. |
| Ajustar o estoque | corrigir saldo, acertar saldo, inventariar | Corrige a quantidade e pede o motivo. |
| Desfazer lançamento | estornar, estorno, anular, apagar | Cria um novo lançamento que cancela o anterior e deixa o histórico. Explicar na confirmação: "O lançamento antigo continua no histórico." |
| Contagem do estoque | inventário | Conferir o que há na loja lendo os códigos. |
| Começar a contagem · Fechar a contagem | abrir inventário, fechar inventário, congelar | Ao começar, o sistema anota quantas peças esperava de cada produto. |
| Contado · Esperado | contagem física, saldo do sistema | Colunas da contagem: o que foi lido e o que o sistema esperava. |
| Diferença | divergência, discrepância | Contado menos esperado. Pode ser para mais ou para menos. |
| Estoque mínimo | ponto de pedido, alerta de estoque, `estoque_minimo` | Campo do produto. Aviso: "Está acabando". |
| Está acabando | alerta, estoque crítico, abaixo do mínimo | Aviso do produto que chegou ao estoque mínimo ou ficou abaixo. |
| Produtos parados | giro baixo, produtos sem giro, estoque encalhado | Produtos com estoque que não tiveram saída no período escolhido. |
| Cadastro rápido | cadastro simplificado, produto provisório | Cadastrar o produto na hora, ao receber a compra, quando o código lido não existe. |

**Textos padrão**

- Saída sem estoque suficiente: **Não há peças suficientes no estoque.** Há {n} peça(s) de "{produto}". Confira a quantidade ou receba a compra antes.
- Ajuste sem motivo: **Diga o motivo do ajuste.** Isso ajuda a entender a mudança depois.
- Desfazer lançamento (confirmação): **Desfazer este lançamento?** O sistema cria um novo lançamento que cancela este. O antigo continua no histórico. Botões: **Desfazer lançamento** e **Voltar**.
- Lançamento já desfeito: **Este lançamento já foi desfeito.**
- Reenvio da mesma compra (não é erro para a pessoa): **Esta compra já estava registrada.** Nada foi somado de novo.
- Reenvio da mesma leitura na contagem (não é erro): **Esta leitura já estava registrada.** Nada foi somado de novo.
- Contagem fechada com peça excluída durante a contagem: **Sem ajuste, porque a peça foi excluída durante a contagem: {peças}.** A diferença ficou registrada.
- Fechar a contagem (confirmação): **Fechar a contagem?** O estoque será ajustado nos {n} produtos com diferença. Depois de fechada, a contagem não muda; para corrigir, faça um ajuste. Botões: **Fechar a contagem** e **Continuar contando**.
- Produto da contagem que não foi lido: **{n} produtos não foram lidos.** Marque se devem ficar com zero peças ou se ficam como estão.
- Compra com o mesmo identificador e outros dados: **Esta compra já foi registrada com outros dados.** Confira a lista de compras antes de lançar de novo.
- Compra com peça repetida: **A peça "{produto}" aparece mais de uma vez nesta compra.** Some as quantidades numa linha só.
- Código que não existe na compra: **Não encontramos nenhuma peça com o código {código}.** Cadastre a peça antes de receber a compra.
- Compra que não dá para desfazer: **Não é possível desfazer esta compra.** De "{produto}" já saíram peças e há só {n} no estoque.
- Compra já desfeita: **Esta compra já foi desfeita.**
- Compra não encontrada: **Não encontramos esta compra.** Atualize a tela e tente de novo.
- Aviso na tela inicial: **{n} produtos estão acabando.** Esses produtos chegaram ao estoque mínimo. Veja a lista e faça a reposição. (Com um só: **1 produto está acabando.**)
- Já existe contagem com essas peças: **Já existe uma contagem em andamento.** Feche ou cancele essa contagem antes de começar outra.
- Contagem fechada ou cancelada: **Esta contagem já foi encerrada.** Comece uma nova contagem se precisar.
- Contagem que não dá para fechar: **Não é possível fechar esta contagem.** De "{produto}" há {n} no estoque agora e a contagem pede tirar {m}. Confira a peça, lance a compra que falta ou refaça a contagem dela.
- Peça fora da contagem: **Esta peça não faz parte desta contagem.**
- Contagem sem peças: **Não há peças nesta seleção para contar.** Escolha outra.
- Leitura repetida com outra quantidade: **Esta leitura já foi registrada com outros dados.** Atualize a tela e confira a quantidade contada.
- Contagem não encontrada: **Não encontramos esta contagem do estoque.** Atualize a tela e tente de novo.
- Lista vazia: "Ainda não há entradas e saídas.", "Nenhum produto está acabando." e "Ainda não há compras."


**Telas de compras (tarefa 2.6, Corona Gestão)**

- **Menu Estoque** · Receber compra · Compras. Abas de Receber compra: **Ler as peças** e **Importar planilha**.
- Campos: **Fornecedor**, **Data da compra**, **Número da nota (se tiver)**, **Observações (se quiser)**, **Código da peça ou nome**, **Quantidade**, **Custo de cada peça**, **Total da linha**, **Total da compra**. Botões: **Adicionar peça**, **Tirar**, **Começar de novo**, **Confirmar compra**, **Receber outra compra**, **Ver na lista de compras**, **Desfazer compra** (pede o **Motivo**).
- Situação da compra na lista: **Registrada** ou **Desfeita** (nunca "estornada"). Filtros: **De**, **Até**, **Fornecedor**, **Situação**, **Limpar filtros**.
- **Cadastro rápido:** "Não encontramos a peça com o código {código}. Cadastre agora e ela já entra na compra." Botões **Cadastrar esta peça agora**, **Cadastrar e pôr na compra**, **Agora não**.
- Ao ler a mesma peça de novo: "{peça}: agora são {n} peças nesta compra." A mesma frase vale para o leitor e para quem digita.
- Confirmação: **Confirmar esta compra?** "Vão entrar {n} peças no estoque, num total de {valor}. Se precisar corrigir depois, é preciso desfazer a compra." Depois: **Compra gravada.** "Entraram {n} peças no estoque, num total de {valor}." (o valor é o que o sistema conferiu).
- Planilha: colunas **código, quantidade e custo**; botão **Baixar a planilha modelo**; situações da linha **Entra na compra**, **Peça não cadastrada** e **Não entra**; ações **Cadastrar peça**, **Corrigir código**, **Tirar linha**. A compra só grava com todas as linhas boas. Fim: **Compra importada.**

**Telas de estoque (tarefa 2.7, Corona Gestão)**

- **Menu Estoque** · Estoque · Entradas e saídas · Está acabando · Contagem do estoque · Receber compra · Compras.
- **Estoque:** colunas **No estoque**, **Estoque mínimo**, **Situação** (**Está acabando** ou **Em dia**); filtro **Só os que estão acabando**. Ficha: **Entrada**, **Saída**, **Perda**, **Ajustar o estoque**, **Mudar o estoque mínimo**; coluna do histórico **Ficou com**; ao lado do custo: "Só você vê o custo."
- **Desfazer lançamento:** botão **Desfazer** na linha; o desfeito mostra **Desfeito** e o que desfaz mostra "Desfaz Entrada +5 de 10/11/2026". Pede o **Motivo**.
- **Ajuste:** "Como você quer ajustar?" · **Eu contei** · **Quero dizer a diferença** (3 para mais, -3 para menos) · **Motivo do ajuste**.
- **Contagem do estoque:** **Começar a contagem** (todas as peças, uma categoria ou um fornecedor), **Continuar contando**, **Ver a contagem**, **Fechar a contagem**, **Cancelar a contagem**. Campo **Código da peça**; "Última peça lida"; **Definir a quantidade**; lista "Esperado e contado" com **Mostrar** (Só as que têm diferença, As que ainda não li, As que já li, Todas as peças). Situações: **Em andamento**, **Fechada**, **Cancelada**. Pergunta do fechamento: "Eles devem ficar com zero peças ou ficam como estão?" (**Ficam com zero peças** · **Ficam como estão**).

**Relatórios de estoque e de compras (tarefa 2.8, Corona Gestão)**

Menu: **Relatórios de estoque** e **Relatórios de compras** (depois de "Relatórios de cadastros"). Os nomes de tela abaixo são os únicos usados; a §12 só descreve.

| Relatório (§12) | Nome na tela |
| --- | --- |
| Posição atual (valor a custo, só dona) | **Estoque agora, com valor a custo** |
| Movimentações por período | **Entradas e saídas por período** |
| Estoque mínimo | **Peças que estão acabando** |
| Produtos parados | **Peças paradas** (filtro **Sem sair há quantos dias**, padrão 30) |
| Inventário e divergências | **Contagens do estoque e diferenças** |
| Conciliação do estoque | **Conferência do estoque** (deve ficar vazio: "Tudo certo: a quantidade de todas as peças bate com o que entrou e saiu.") |
| Compras por período | **Compras por período** |
| Compras por fornecedor | **Compras por fornecedor** |
| Itens de uma compra | **Peças da compra** (abre a partir da compra, botão **Ver as peças**) |
| Histórico de custo por produto | **Histórico de custo das peças** (botão **Ver o histórico de custo** na linha de uma peça) |

- Filtros: **De**, **Até**, **Buscar**, **Situação**, **Categoria**, **Fornecedor**, **Quem lançou**, **Tipo de lançamento**, **De onde veio**, **Diferença**, **Limpar filtros**. Situação das compras: **Registradas**, **Só desfeitas**, **Todas (desfeitas marcadas)**; das contagens: **Todas**, **Em andamento**, **Fechadas**, **Canceladas**.
- Compra desfeita aparece como **Desfeita** e fica **fora do total** ("Compras registradas (valem no total)" e "Compras desfeitas (fora do total)").
- Origem do lançamento: **Lançamento feito à mão**, **Ajuste do estoque**, **Compra**, **Contagem do estoque**, **Maleta**, **Desfazer lançamento**.
- Totais do rodapé usam estas palavras no lugar do texto do servidor ("Peças que entraram", "Peças que saíram", "Com diferença", "Peças a mais", "Peças a menos", "Peças que faltam para chegar ao mínimo").
- Abrir uma linha: **Ver as peças** (compra), **Ver só esta contagem** (contagem), **Ver o histórico de custo** (peça), com **Voltar ao relatório**.
- A foto aparece em toda linha de peça na tela ("Sem foto" quando não há); os arquivos PDF, Excel e CSV levam o nome e o código da peça, sem a imagem.

**Relatórios da maleta e nota da maleta (tarefa 3.4, só os dados; as telas vêm na 3.6)**

Menu: **Relatórios da maleta** (depois de "Relatórios de compras"). Os nomes de tela abaixo são os únicos usados; a §12 só descreve.

| Relatório (§12) | Nome na tela |
| --- | --- |
| Remessas por vendedor e semana | **Maletas por vendedor e semana** |
| Saldo atual por maleta | **O que ainda está com o vendedor, peça a peça** (abre a partir da maleta, botão **Ver as peças**) |
| Mercadoria em maletas por vendedor | **Mercadoria com cada vendedor** |
| Maletas em aberto e atrasadas | **Maletas em aberto e atrasadas** |
| Reposições | **Reposições** |
| Nota de remessa (PDF) | **Nota da maleta** (botão **Imprimir a nota**; o PDF se chama "Nota de remessa", a única vez que a palavra aparece) |

- Filtros: **De**, **Até** (sobre o dia em que a maleta foi enviada), **Semana**, **Vendedor**, **Praça**, **Tipo** (**Nova**, **Reposição**), **Produto**, **Categoria**, **Buscar**, **Situação**, **Prazo para atrasar (dias)** (já vem 7), **Limpar filtros**.
- Situação das maletas: **Enviadas** (já vem escolhida), **Canceladas**, **Todas (canceladas marcadas)**. Do saldo: **Com peças** (já vem escolhida), **Sem peças**, **Todas**. Das maletas em aberto: **Todas**, **Atrasadas**, **No prazo**.
- Maleta cancelada aparece como **Cancelada** e fica **fora do total** ("Maletas enviadas (valem no total)" e "Maletas canceladas (fora do total)"). Maleta ainda sendo montada nunca aparece nos relatórios.
- Totais do rodapé: "Maletas enviadas", "Maletas novas", "Reposições", "Peças enviadas", "Peças devolvidas à dona", "Peças ainda com o vendedor", "Mercadoria em maletas", "Vendedores com maleta atrasada", "Vendedores acima do limite", "Maletas em aberto", "Maletas atrasadas", "Maletas no prazo". Colunas: **Número da maleta** (por exemplo S12-V7-1: semana 12, vendedor 7, primeira maleta dele na semana), **Enviada**, **Devolvida**, **Saldo**, **Preço da maleta**, **Dias desde o envio**, **Limite**, **Sobra do limite**.
- Maleta **atrasada**: passaram mais de 7 dias desde o envio e ainda há peça com o vendedor (o prazo de 7 dias é provisório; a dona confirma).
- Dólar e Guarani seguem a cotação do dia em que **cada maleta foi enviada**. Maleta enviada antes da primeira cotação do sistema mostra "Sem cotação" no Dólar e no Guarani.
- Nota da maleta: loja, número da maleta, vendedor, praça, semana, tipo, data do envio, lista de peças (código, nome, quantidade, preço, subtotal), total de peças e de valor, e a linha "Cotação do Banco Central de {data do envio}" com o total em Dólar e em Guarani. Linha de assinatura do vendedor ao fim. Maleta cancelada sai com a marca **Cancelada**. Maleta ainda em montagem: "A nota só fica pronta depois que a maleta for enviada. Envie a maleta e tente de novo."

**Montar maleta (tarefa 3.5, Corona Gestão)**

Menu: grupo **Maletas**, item **Montar maleta**. Os textos desta tela são os únicos usados; erros de regra (limite, saldo, restrição) vêm do servidor.

- Nova maleta: **Vendedor**, **Semana**, **Praça** (já vem "A praça do vendedor"), **Tipo da maleta** (**Maleta nova**, **Reposição**), botão **Começar a montar**.
- Maletas em montagem: **Continuar montando** e **Excluir** (confirmação: "As peças continuam no estoque, nada foi enviado").
- Leitura: **Ler uma peça**, campo **Código da peça ou nome**, botão **Colocar na maleta**. Peça lida: **Última peça lida** com **No estoque**, **Nesta maleta** e **Preço unitário**. Recusas: **Peça não encontrada**, "está desativada", "está sem estoque", "Só há N peças ... no estoque".
- Lista: **Quantidade**, **Atualizar**, **Tirar**; totais **Peças na maleta** e **Valor da maleta**.
- Limite: **Limite do vendedor**, **Já está com ele**, **Esta maleta**, **Sobra**; "Sem limite" marcado: **Sem limite**; ainda não definido: **Limite não definido** e "Defina o limite deste vendedor antes de montar a maleta". Frase da montagem: "Limite R$ X — em mãos R$ Y — pode levar mais R$ Z".
- Envio: botão **Enviar maleta**, confirmação "Enviar esta maleta?" (vendedor, peças e valor), depois **Maleta enviada**; reenvio: **Esta maleta já estava enviada**. O botão **Imprimir nota** só aparece quando a tarefa 3.7 ligar a impressão.

## 13. Contas a receber do cliente (tarefa 7.3, mensagens da API)

As telas chegam nas tarefas 7.10 e 7.11; estas são as mensagens que a API já devolve.

- **Parcelas:** "Esta venda já está paga. Não há parcelas." · "O valor a prazo (R$ 0,18) é pequeno demais para 12 parcelas. Use menos parcelas." · "O número de parcelas vai de 1 a 24." · "O primeiro vencimento não pode ser antes do dia da venda."
- **Receber:** "O valor passa do que falta pagar nesta venda (R$ 40,00). Confira o valor." · "Esta venda não tem nada a receber." · "Este recebimento já foi registrado com outros dados." · "Recebimento não encontrado." · "Este recebimento já foi estornado." · "O recebimento é só em dinheiro ou transferência."
- **Estornar:** "Depois do dia do recebimento, só a dona pode estornar." · "O pagamento feito na hora da venda se corrige cancelando a venda."

## 14. Devolução de venda a prazo (tarefa 6.6)

- **Linha da prévia e da lista:** "Abatido do que o cliente deve: R$ W" (só aparece quando há abatimento). Na troca, "O cliente paga: R$ X" é o valor das peças novas menos o crédito.
- **Corona Gestão, aviso da venda a prazo:** "Esta venda é a prazo: a devolução primeiro abate o que o cliente ainda deve."
- **Parcela** `PARCIAL` quer dizer paga ou abatida em parte; o que falta é sempre o valor menos o pago menos o abatido.

## 15. Fechamento do acerto (tarefa 7.5)

- **Fechar o acerto:** o botão da dona; confere o que o vendedor devolveu contra o que devia ter, calcula a comissão e deixa as maletas acertadas.
- **Sobrou peça:** "Contaram mais peças do que o esperado. Confira as vendas e a contagem antes de fechar o acerto."
- **Faltou peça:** a dona escolhe **Cobrar neste acerto**, **Cobrar depois** ou **A loja assume** (pede o motivo).
- **Pendência:** peça que faltou, defeito ou valor que a dona não aceitou. Estados: **Aberta**, **Cobrada**, **A loja assumiu**, **Encontrada**, **Aprovada**, **Recusada**.
- **Ficou devendo:** o que o vendedor ainda deve depois do acerto (negativo: a loja deve ao vendedor).
- **Meta da comissão:** "Meta da semana" em Guaranis; **Comissão abaixo da meta** e **Comissão na meta** são as duas porcentagens.
- **Quanto falta para a meta:** a prévia do vendedor, em Guarani e em Real.
- Erros: "Há devolução esperando a sua decisão. Mantenha ou não aceite antes de fechar o acerto." · "Chegou coisa nova neste acerto. Confira de novo antes de fechar." · "Busque a cotação do dia antes de fechar o acerto."

## Dinheiro do vendedor e caixa (tarefa 7.6)

Os textos das telas são das tarefas 7.9, 7.10 e 7.11; estes são os termos que elas devem usar.

- **Quanto o vendedor deve neste acerto** (o líquido menos o crédito); **Crédito do vendedor** ("A loja deve R$ X a este vendedor. O valor será abatido no próximo acerto."); **O que ficou devendo**; **Pago neste acerto**.
- Formas: **Dinheiro**, **Transferência (PIX)** e **Boleto**. Nunca "Cheque", "Cartão" nem "Entrada" no repasse do vendedor.
- **Boleto**: "O boleto vira uma conta a receber. Quando for pago, registre o recebimento." Aviso de erro: "O boleto pago precisa ser do valor inteiro da conta do boleto."
- **Contas que o vendedor deve** (conta a receber do vendedor): **Vencimento**, **Valor**, **Já pago**, **Em aberto**; parcela "2 de 3".
- **Registrar recebimento** (a dona, fora do acerto); **Estornar recebimento** (com motivo); mensagens: "Escolha a data de pagamento do que ficou devendo.", "O valor recebido passa do que o vendedor deve neste acerto.", "Este vendedor não tem conta em aberto.", "Este recebimento já foi estornado."
- **Pagar ao vendedor agora** / **Deixar de crédito para o próximo acerto** (acerto a favor do vendedor).
- **Caixa** (só a dona): **Entradas**, **Saídas**, **Saldo**; categorias "Repasse do representante" e "Pagamento ao representante" (a 9.8 trocou "vendedor" por "representante" nos textos gravados do caixa).

## Contas a pagar, caixa manual e fechar semana (tarefa 7.7)

Só a dona vê estas telas (Corona Gestão); as telas são da tarefa 7.10. Valores em Real, com Dólar e Guarani ao lado (parte 11).

- **Contas a pagar** (parcelas das compras): **Fornecedor**, **Compra**, **Parcela** ("2 de 3"), **Vencimento**, **Valor**, **Já pago**, **Falta pagar**, **Situação** (Aberta, Parcial, Paga, Cancelada, Vencida). Botões: **Gerar contas** (parcelas de 1 a 24, mensais, a partir do primeiro vencimento), **Registrar pagamento** (dinheiro, Transferência (PIX) ou boleto; o valor pode ser parte da parcela; a data é opcional e nunca no futuro), **Estornar pagamento** (com motivo).
- Mensagens: "As contas desta compra já foram geradas. Para refazer as parcelas, desfaça a compra e lance de novo." / "O valor da compra é pequeno demais para tantas parcelas. Escolha menos parcelas." / "O valor passa do que falta pagar desta conta (R$ 0,00). Confira o valor." / "Esta conta foi cancelada porque a compra foi desfeita. Não há o que pagar." / "Este pagamento já foi estornado e não pode mudar." / "A data do pagamento não pode ser no futuro."
- **Caixa manual:** **Lançar no caixa** (Entrada ou Saída, **Categoria**, **Forma**, **Data**, **Descrição**), **Estornar lançamento** (com motivo). Mensagens: "Esta categoria é do sistema. Escolha uma das suas categorias." / "Esta categoria está inativa. Reative a categoria ou escolha outra." / "Esta categoria é de entrada de dinheiro. Escolha uma categoria do mesmo tipo." / "Este lançamento veio de um pagamento. Desfaça o pagamento para ele sair do caixa." / "Este lançamento já é um estorno e não pode ser estornado de novo. Faça um lançamento novo." / "Essa semana já foi fechada. Lance com a data de hoje."
- **Categorias do caixa:** **Nova categoria**, **Renomear**, **Inativar**, **Reativar** (não se exclui); o tipo (entrada ou saída) não muda depois. Mensagem: "Já existe uma categoria com este nome. Use outro nome." / "Esta categoria é do sistema e não pode ser alterada."
- **Aviso de caixa negativo** (em "O que precisa da sua atenção"): "O caixa está negativo em R$ 0,00." / "Entrou menos dinheiro do que saiu. Confira os lançamentos do caixa e lance o que faltar." O caixa nunca bloqueia: só avisa.
- **Fechar semana** (botão que nomeia o que vai acontecer; confirmação clara, ação sem volta): "Esta semana ainda não terminou. Feche a semana depois do último dia dela." / "Há acerto em andamento nesta semana. Termine ou desista do acerto antes de fechar a semana." / "Esta semana já foi fechada e não muda mais." / "Para fechar a semana, use Fechar semana." (no formulário da semana, que não tem mais a opção "Fechada").

## 16. Telas do acerto (tarefa 7.9)

Só a dona, no Corona Gestão. Valores em Real, com Dólar e Guarani ao lado pela cotação gravada no acerto (parte 11); a frase fixa é "Valores em Dólar e Guarani pela cotação de DD/MM/AAAA. Vale o valor em reais." (sem cotação: "Sem cotação: os valores aparecem só em reais.").

- **Menu:** grupo **Acerto** com **Acertos**, **Pendências** e **Comissão e meta**. Na tela inicial, **Fazer acerto** abre a lista de acertos.
- **Acertos:** botão **Começar acerto** (escolhe o vendedor; "Mais opções" mostra a **Semana**). Situações: **Em acerto**, **Acertado**, **Desistido**. Aviso quando o vendedor não tem maleta: "Este vendedor não tem maleta com ele: o acerto vai ter só vendas e devoluções antigas."
- **Contagem das peças:** **Peça boa** ou **Com defeito**, **Quantidade**, **Contar**, **Desfazer leitura**. Avisos: "Chegou venda nova: confira a contagem." e "O computador do vendedor ainda tem N envios para mandar." Botões **Conferir o acerto** e **Desistir do acerto** (pede o motivo: "As maletas voltam a ficar com o vendedor e nada de dinheiro é gravado.").
- **Conferência do acerto:** colunas **Saiu na maleta**, **Voltou boa**, **Voltou com defeito**, **Faltou**, **Valor da falta**. "Sobrou peça: confira as vendas ou registre o ajuste." e "Tem devolução esperando sua revisão." (botão **Revisar devoluções**) impedem fechar. Para cada falta e cada devolução não aceita: **Cobrar agora**, **Cobrar depois** ou **A loja assume** (pede o motivo; na parte 15 o primeiro se chamava "Cobrar neste acerto"). Pendência antiga: **Cobrar agora** ou **Deixar para depois**. Defeito: **Aprovar como defeito**.
- **Comissão e meta (na conferência e na ficha):** "Vendido na semana: Gs. X de Gs. Y (meta)", "Bateu a meta: comissão de 40%." ou "Ainda não bateu a meta: comissão de 30%.", e a comissão de cada venda.
- **Falta para a meta (só na conferência do acerto em andamento, tarefa 8.11):** logo abaixo de "Vendido na semana", "Falta para a meta: Gs. 600.000 (R$ 540,00)." O Guarani é o que falta para a meta; o Real é o equivalente, pela cotação mais recente, só para mostrar (ADR-0012, item 5). Quando a meta foi batida a linha não aparece, e a ficha do acerto fechado não tem a linha (já diz se bateu a meta). Mesmo termo e mesma conta da prévia do vendedor (**Falta para a meta**, parte 16).
- **Totais:** **Vendido**, **Descontos**, **Devolvido**, **Vendido depois dos descontos**, **Comissão do vendedor**, **Faltas cobradas**, **Valor do acerto**, **Crédito do vendedor usado**, **A pagar agora**; e, no pagamento, **Recebido agora**, **Boleto**, **Ficou devendo**, **Crédito do vendedor disponível**, **Pago ao vendedor agora** (na ficha fechada: **Recebido no acerto**, **Ficou devendo**, **Pago ao vendedor**).
- **Pagamento:** **Dinheiro**, **Transferência (PIX)** e **Boleto** (valor e vencimento). O que ficou devendo: **Em quantas parcelas** e **Data da primeira parcela** ("Escolha a data de pagamento do que ficou devendo."). **Contas antigas do vendedor** (pagar junto; "não entra no valor deste acerto"). A favor do vendedor: "A loja deve R$ X ao vendedor." com **Guardar como crédito para o próximo acerto** ou **Pagar agora**. Nunca aparecem cheque, cartão nem entrada.
- **Fechar acerto** (nomeia a ação): "Fechar o acerto de {vendedor}? Depois de fechado, o acerto não muda mais." com **Fechar acerto** e **Voltar e conferir**; depois, "Acerto fechado." Erros: "Sem internet. O acerto não foi fechado. Tente de novo." · "Os números mudaram desde que você abriu. Confira o acerto de novo." · "O valor pago passa do que o vendedor deve. Confira os valores." · "A semana deste acerto já está fechada, por isso o acerto não pode ser fechado. Fale com o suporte."
- **Ficha do acerto fechado:** só leitura. Botão **Imprimir acerto** ("O acerto {arquivo} foi preparado. Escolha onde guardar."). O recebimento desfeito aparece como **Desfeito**.
- **Pendências:** situações **Em aberto**, **Cobrada**, **A loja assumiu**, **Peça encontrada**, **Defeito aprovado**, **Defeito recusado**. Só a em aberto tem **Resolver** (**A loja assume** ou **A peça apareceu**, esta só para peça que faltou; pede o motivo); depois, "Pendência resolvida." A cobrança é no próximo acerto: "A pendência em aberto aparece no próximo acerto do vendedor, e é lá que ela é cobrada."
- **Comissão e meta (tela):** **Meta da semana, em Guarani**, **Comissão abaixo da meta, em %**, **Comissão a partir da meta, em %** (na parte 15, "Comissão na meta"); **Salvar**; "Comissão e meta salvas." e "Vale para os próximos acertos. Acerto já fechado não muda."

## 17. Telas do financeiro (tarefa 7.10)

Só a dona, no Corona Gestão. Valores em Real; Dólar e Guarani ao lado, pela cotação do dia, só nas contas, nos recebimentos e no saldo (a frase fixa vem do rodapé da cotação, parte 11). No extrato do caixa, só Real.

- **Menu:** grupo **Financeiro** com **Caixa**, **Categorias do caixa**, **Contas a pagar**, **Contas a receber** e **Recebimentos do vendedor**. Em **Cadastros, Semanas**, o botão **Fechar semana**.
- **Formas:** **Dinheiro**, **Transferência (PIX)** e **Boleto**. Nunca "Cheque", "Cartão" nem "Entrada" como forma.
- **Caixa:** **Saldo**, **Saldo antes do período**, **Entradas**, **Saídas**, **Saldo no fim do período**; filtros **De** e **Até**; **Lançar no caixa** com **O dinheiro** (**Entrou** ou **Saiu**), **Categoria**, **Forma**, **Data**, **Descrição**, **Valor** e o botão **Lançar**; **Estornar lançamento** (pede o **Motivo do estorno**). Origens: **Lançamento da loja**, **Recebimento do vendedor**, **Pagamento ao fornecedor**, **Pagamento ao vendedor**. Aviso: "O caixa está negativo em R$ 0,00." e "Entrou menos dinheiro do que saiu. Confira os lançamentos do caixa e lance o que faltar."
- **Categorias do caixa:** **Nova categoria** (**Nome da categoria**, **Tipo**: Entrada ou Saída), **Renomear**, **Inativar**, **Reativar**; a categoria do sistema aparece como "Da loja (não muda)", sem botões.
- **Contas a pagar:** colunas **Fornecedor**, **Compra**, **Parcela** ("1 de 3"), **Vencimento**, **Valor**, **Já pago**, **Falta pagar**, **Situação**; filtros **Fornecedor**, **Situação**, **Vence de**, **Vence até**; **Registrar pagamento** (**Forma**, **Valor pago**, **Data do pagamento**, "Se deixar em branco, vale hoje."); **Ver pagamentos** ("Pagamentos desta parcela") e **Estornar pagamento**.
- **Contas a receber** (só consulta): **Do cliente ao vendedor** e **Do vendedor à loja**; colunas **Vendedor**, **Cliente**, **Conta**, **Parcela**, **Vencimento**, **Valor**, **Já pago**, **Em aberto**, **Situação**. Para registrar o que o vendedor pagou, use **Recebimentos do vendedor**.
- **Recebimentos do vendedor:** **Registrar recebimento** (**Vendedor**, **Conta a baixar**: "As contas em aberto, da mais antiga para a mais nova" ou uma conta com vencimento e valor em aberto, **Forma**, **Valor recebido**); situações **Valendo**, **Desfeito** e **Estorno**; de onde veio: **Pago no acerto** ou **Recebimento avulso**; **Estornar recebimento** (**Motivo do estorno** e, só para o que pagou um acerto, **Novo vencimento da conta, se o recebimento pagou um acerto**).
- **Fechar semana:** "Fechar a semana N? Depois de fechada, ela não aceita mais lançamentos nem mudanças. Isso não pode ser desfeito." com **Fechar semana** e **Voltar**; depois, "Semana fechada." (ou "Esta semana já estava fechada.").
- **Cadastro do vendedor:** não tem mais o campo "Comissão (%)"; a comissão é a da tela **Comissão e meta** (parte 16).

## 18. Corona Vendas: parcelas, a receber e meu acerto (tarefa 7.11)

Só o vendedor, no Corona Vendas, e só o que é dele. Valores em Real, com Dólar e Guarani ao lado pela cotação do dia (parte 11) e, na ficha do acerto, pela cotação gravada no acerto. A tela nunca calcula parcela, comissão nem total: mostra o que o servidor devolveu.

- **Onde fica:** em **Meus números**, dois botões: **Meu acerto e minha comissão** e **A receber dos clientes**. Os cinco botões da tela inicial não mudam.
- **Parcelas (ao vender e ao trocar):** quando sobra valor a prazo aparece o bloco **Parcelas**, com **Em quantas parcelas? (1 a 24)** e **Primeiro vencimento** (já vem um mês depois de hoje). Ajuda: "O que ficou a prazo é dividido pelo sistema em parcelas por mês. Você só escolhe em quantas e quando vence a primeira." Erros: "O número de parcelas vai de 1 a 24." · "Escolha a data do primeiro vencimento." · "O primeiro vencimento não pode ser antes de hoje." · "O primeiro vencimento está longe demais. Escolha uma data até um ano depois da venda." Não há entrada, cheque nem cartão.
- **A receber, o que os clientes devem a você:** cada venda a prazo mostra **Total da venda**, **Já pago**, **Falta receber** e as parcelas ("Parcela 1 de 3 · Vence em 10/12/2026 · R$ 33,33 · Aberta"); **Vencida** marca a parcela que passou do dia; situações **Aberta**, **Paga em parte**, **Paga** e **Cancelada**. Venda que ainda não chegou à loja: "Aguardando envio" e "As parcelas aparecem aqui depois que esta venda chegar à loja. Conecte-se à internet para enviar."; o "Falta receber" dela leva o aviso "Estimativa: o que vale é o que a loja confirma depois de receber os dados."
- **Receber:** botão **Receber** (por venda), campo **Valor recebido (R$)** e **Como o cliente pagou?** com **Dinheiro** ou **Transferência** (gravada como PIX); botão **Registrar recebimento**. Ajuda: "O sistema abate o valor das parcelas, da que vence primeiro para a que vence depois." Mensagens: "Recebimento registrado." · "Digite o valor recebido em reais, por exemplo 50,00." · "Informe o valor recebido, maior que zero." · "O valor passa do que falta pagar nesta venda (R$ 40,00). Confira o valor." · "Esta venda não tem nada a receber."
- **Estornar recebimento (só no mesmo dia):** botão **Estornar recebimento**, campo **Por que está estornando?** e botão **Estornar**; "Recebimento estornado."; o recebimento estornado aparece como **Estornado**. Depois do dia: "Depois do dia do recebimento, só a dona pode estornar." Pagamento da hora da venda: "Este pagamento foi feito na hora da venda. Para corrigir, estorne a venda."
- **O que você deve à loja:** as contas do acerto, com **Vence em**, **Valor**, **Já pago** e **Forma combinada** (**Dinheiro**, **Transferência (PIX)** ou **Boleto**). Boleto: "O boleto é uma conta que a dona registra como paga quando você pagar." Sem conta: "Você não tem nada em aberto com a loja."
- **Minha comissão desta semana (prévia):** "Prévia: é só uma estimativa. O valor final é o do fechamento do acerto." **Vendido na semana**, **Meta da semana**, **Comissão que valeria hoje**, **Comissão prevista** e **Falta para a meta** em Guarani e em Real; com a meta batida: "Você já bateu a meta da semana." Sem internet: "A prévia da comissão precisa de internet. Conecte-se para ver quanto falta para a meta." Botão **Atualizar prévia**.
- **Meus acertos:** **Semana N**, situação (**Em acerto**, **Acertado**, **Desistido**), **Pedido por você**, **Fechado em** e o botão **Ver acerto**. Em andamento: "Este acerto está acontecendo. Os valores aparecem depois que a dona fechar o acerto." Desistido: "A dona desistiu deste acerto. Nada de dinheiro foi gravado nele."
- **Ficha do acerto fechado:** "Valores em Dólar e Guarani pela cotação de DD/MM/AAAA. Vale o valor em reais." (sem cotação: "Sem cotação: os valores aparecem só em reais."); **Resumo do acerto** (**Vendido**, **Descontos**, **Devolvido pelos clientes**, **Vendido sem descontos e devoluções**, **Sua comissão**, "Comissão de 40.00% sobre as vendas", **Meta da semana**, **Peças que faltaram (cobradas neste acerto)**, **Quanto você devia neste acerto**, **Pago neste acerto**, **O que ficou devendo**); **Peças do acerto**, **Comissão de cada venda**, **Pendências** e **O que você pagou neste acerto**. Acerto a favor do vendedor: "A loja deve R$ 35,00 a você. O valor será abatido no próximo acerto."

## Relatórios financeiros (Fase 7, tarefa 7.12; §12)

Menu do Corona Gestão: grupo **Relatórios financeiros** (depois de "Relatórios de devoluções"). Os nomes de tela abaixo são os únicos usados; a §12 só descreve. Cheque, cartão e entrada não existem e não aparecem. Dólar e Guarani seguem a cotação do Banco Central do dia de **cada lançamento** (do fechamento, no que nasce do acerto); tudo é gravado em Real.

| Tela | O que mostra |
| --- | --- |
| **Acertos por vendedor e semana** | Um acerto fechado por linha: Vendido, Descontos, Devolvido, Comissão, Faltas cobradas, Valor do acerto, Recebido no acerto, Ficou devendo |
| **Faltas e pendências** | Peça que faltou, Peça com defeito, Devolução não aceita, Crédito sem saldo; situação: Em aberto, Cobrada do vendedor, A loja assumiu, Peça encontrada, Defeito aprovado ou recusado |
| **Esperado e contado** | Por peça de cada maleta do acerto: Esperadas, Contadas boas, Contadas com defeito, Faltaram, Valor do que faltou |
| **Comissões por vendedor e semana** | Vendido para a meta (Gs), Meta da comissão (Gs), Percentual (30% ou 40%), Comissão; situação "Paga no acerto" (a comissão é paga abatendo no acerto) |
| **Parcelas a receber** | Parcelas dos clientes e dos vendedores: Valor, Já pago, Cancelado, Falta; filtro **Quem deve** |
| **Parcelas a pagar** | Parcelas das compras aos fornecedores |
| **Quem está devendo** | Só parcelas vencidas e não pagas, com Dias de atraso e o rodapé por faixa (até 30, 31 a 60, mais de 60 dias) |
| **Fluxo do caixa** | Entradas, Saídas e Saldo do período; estorno marcado |
| **Recebimentos por forma de pagamento** | Dinheiro, Transferência e Boleto repassados pelos vendedores; momento: No acerto ou Depois do acerto |

Os nomes "Parcelas a receber" e "Parcelas a pagar" evitam repetir "Contas a receber" e "Contas a pagar", que são as telas de trabalho do Financeiro. DRE e lucro por produto e por vendedor ficam para a Fase 8.

## Relatórios de vendas e curva ABC (Fase 8, tarefa 8.5; §12)

Menu do Corona Gestão: grupo **Relatórios de vendas** (depois de "Relatórios de devoluções") e, no grupo de estoque, **Curva ABC dos produtos**. Dólar e Guarani seguem a cotação do Banco Central do dia de **cada venda**; tudo é gravado em Real. Nenhum custo aparece.

| Tela | O que mostra |
| --- | --- |
| **Vendas feitas** | Uma venda por linha: Vendida em, Vendedor, Praça, Cliente, Peças, Vendido, Devolvido, Vendido líquido; filtro de situação: só as feitas, só as canceladas, feitas e canceladas |
| **Vendas por vendedor**, **por praça**, **por cliente**, **por produto**, **por categoria**, **por dia** | Vendas, Peças, Vendido, Devolvido, Vendido líquido e Valor médio por venda de cada grupo |
| **Ranking de vendedores**, **Ranking de produtos** | O mesmo, do que mais vendeu para o que menos vendeu, com a Posição (e a foto, no ranking de produtos) |
| **Valor médio por venda** | Os vendedores do maior para o menor valor médio por venda (o "ticket médio" da especificação não aparece na tela) |
| **Vendas canceladas** | Cancelada em, Cancelada por e Motivo; ficam fora de todos os totais |
| **Curva ABC dos produtos** | Posição, foto, Código, Produto, Categoria, Peças, Vendido líquido, Parte do total, Soma até aqui e **Classe** A, B ou C |

Termos: **Vendido líquido** = o que foi vendido menos o que o cliente devolveu ou trocou. **Classe A**: os produtos que somam até 80% do vendido líquido; **B**: até 95%; **C**: o resto. Produto sem venda no período não aparece na curva. "Sem praça" aparece para o vendedor sem praça cadastrada.

## Resultado do período e lucro (Fase 8, tarefa 8.6; §12)

Menu do Corona Gestão: no grupo **Relatórios financeiros**. Só a dona vê custo e lucro; nada disto existe no Corona Vendas. Dólar e Guarani seguem a cotação do Banco Central do dia de **cada registro**; tudo é gravado em Real.

| Tela | O que mostra |
| --- | --- |
| **Resultado do período** | De que dia até que dia (obrigatório). Linhas: Vendas, Devoluções e trocas, Receita líquida, Custo das peças vendidas, Lucro bruto, Comissões, Perdas, Cobrado do vendedor, uma linha por categoria de despesa ou entrada da loja e o Resultado. Resultado negativo vem escrito "(prejuízo)" |
| **Registros do resultado** | Os registros que formam uma linha escolhida (a venda, a devolução, a comissão, a perda, a cobrança ou o lançamento do caixa), com o custo de cada peça e a marca **Custo do cadastro** |
| **Lucro por produto** | Foto, Código, Peça, Categoria, Peças, Receita líquida, Custo das peças, Lucro, Margem e Custo do cadastro |
| **Lucro por vendedor** | Vendedor, Praça, Receita líquida, Custo das peças, Lucro bruto, Comissão e Lucro |

Termos: **resultado do período** = o que sobrou (ou faltou) no período, depois de tudo. **Lucro bruto** = receita líquida menos o custo das peças vendidas. **Lucro** (por peça ou vendedor) = receita líquida menos o custo (e, por vendedor, menos a comissão). **Custo das peças vendidas** = o custo da compra mais recente até o dia da venda. **Custo do cadastro** = não havia compra registrada até a venda, então vale o custo que está no cadastro hoje: é uma estimativa e muda se o cadastro mudar. **Perdas** = peças que faltaram no acerto (menos as encontradas depois), avarias e peças a menos na contagem do estoque, pelo custo. **Cobrado do vendedor** = o que o acerto cobrou das faltas. Aviso fixo na tela: "A devolução abate o mês em que a venda foi feita; por isso o resultado de um mês pode mudar depois."

## Relatórios de controle (Fase 8, tarefa 8.9; §12)

Menu do Corona Gestão: grupo **Controle**, depois de "Relatórios financeiros". Só a dona vê. Nenhuma senha aparece.

| Tela | O que mostra |
| --- | --- |
| **Auditoria: quem fez o quê** | Quando, Quem fez ("Pelo servidor" quando não foi uma pessoa), O que foi, Registro, Antes e Depois; filtros de busca, período e pessoa |
| **Computadores e envios** | Vendedor, Situação, Versão do programa, Último recebimento, Último envio, Esperando envio, Envios com problema e Guardados à parte; filtros de situação e de envios com problema |
| **Acessos: entradas e erros** | Quando, Pessoa, Usuário digitado, Programa, Resultado, Endereço de internet e Bloqueado até; filtros de resultado e de programa |

Situações do computador na tela: **Em uso**, **Esperando a sua liberação**, **Trocado por outro** e **Bloqueado**. Resultados de acesso: **Entrou**, **Senha errada**, **Recusada: acesso bloqueado**, **Usuário desativado**, **Programa muito antigo**, **Senha temporária vencida**, **Computador que não combina com o acesso** e **Bloqueio retirado ao redefinir a senha**. Na tela nunca aparecem "quarentena", "sessão", "token" nem "log". Usuário que não existe aparece como **Usuário que não existe**.

## 20. Corona Vendas em dois idiomas (issue #212, 17/11/2026)

O Corona Vendas tem **dois idiomas: espanhol (padrão) e português**. A pessoa troca em "Español | Português", na tela de entrada e no alto de todas as telas; a escolha fica guardada no computador e vale na hora. O Corona Gestão também tem os dois idiomas (parte 21). Os textos moram em `apps/seller-app/src/renderer/textos-pt.ts` e `textos-es.ts` (mesmas chaves; um teste falha se faltar uma) e as mensagens de erro em espanhol, por código, em `erros-es.ts`. Datas e números seguem o idioma (dd/mm/aaaa e "1.234,56" nos dois); o dinheiro continua R$, US$ e Gs.

Espanhol simples, de loja, no tom de `docs/perguntas-para-a-dona.md` (tratamento por "vos": "Conectate", "Escribí"). A dona usa "maleta", "acerto" e "cuota".

| Português | Español (na tela) |
| --- | --- |
| dona | dueña |
| vendedor | vendedor |
| cliente | cliente |
| maleta | maleta |
| acerto | acerto |
| peça | pieza |
| parcela | cuota |
| venda / vender | venta / vender |
| a prazo (fiado) | a plazo (fiado) |
| a receber | por cobrar |
| receber (dinheiro do cliente) | cobrar |
| conferir a maleta | controlar la maleta |
| ler o código | leer el código |
| estornar | anular |
| devolução | devolución |
| troca | cambio |
| crédito do cliente | crédito del cliente |
| cotação | cotización |
| Dinheiro (forma de pagamento) | Efectivo |
| Transferência | Transferencia |
| Boleto | Boleta |
| CPF | documento |
| usuário / senha | usuario / contraseña |
| sem internet / aguardando envio | sin internet / esperando envío |
| prévia (valor estimado) | estimación |
| sobras (do acerto) | lo que sobró |
| pendências | pendientes |
| garantia | garantía |

### Proibido na tela, em espanhol

Mesma regra da parte 2: nunca aparecem em tela as palavras abaixo (a verificação não diferencia maiúsculas e considera plural), nem os identificadores do bloco B. "error" e "null" valem como palavra comum do espanhol e por isso não estão nesta lista; mensagens em inglês e códigos técnicos continuam proibidos.

<!-- PROIBIDAS-ES:INICIO -->
```
outbox
sync
sincronización
cola de sincronización
payload
UUID
token
refresh
failed
invalid
unauthorized
forbidden
timeout
undefined
Exception
stack trace
movimiento de stock
remesa
bipear
PTAX
```
<!-- PROIBIDAS-ES:FIM -->

## 21. Corona Gestão em dois idiomas (issue #213, 17/11/2026)

O Corona Gestão segue a parte 20: **espanhol (padrão) e português**, com os botões **ES | PT** no alto de todas as telas e na tela de entrada; a escolha fica guardada no computador (num arquivo da pasta de dados, gravado pelo programa). A troca recarrega a tela no mesmo endereço (se houver algo por salvar, pergunta antes). Os textos moram em `apps/owner-app/src/renderer/textos-*.ts` (português) e `apps/owner-app/src/renderer/idioma/es/*.ts` (espanhol, mesmas chaves; `idioma.test.ts` falha se faltar uma ou sobrar texto em português). Os títulos dos relatórios estão em `idioma/es/relatorios-mapa.ts`; as mensagens de erro da API em espanhol, por código, em `idioma/erros-es.ts`. Tratamento da dona por **"usted"** (a dona é cliente do sistema, não colega de loja). Termos próprios da dona, além da tabela da parte 20:

| Português | Español (na tela) |
| --- | --- |
| Corona Gestão | Corona Gestión |
| cadastro (registro) | registro |
| praça | plaza |
| estoque | stock |
| montar maleta | armar maleta |
| contagem (do estoque) | conteo |
| fornecedor | proveedor |
| conferência (do sistema) | verificación |
| caixa | caja |
| lançamento | movimiento |
| conta a pagar / a receber | cuenta a pagar / a cobrar |
| recebimento do vendedor | cobro del vendedor |
| semana (fechar semana) | semana (cerrar semana) |
| painel da semana | panel de la semana |
| relatório | informe |
| computador do vendedor | computadora del vendedor |
| envios com problema | envíos con problema |
| nota de remessa | nota de envío |
| Inativar / Reativar | Inactivar / Reactivar |

O que o **servidor** monta em português (avisos da tela inicial, textos de campo recusado, algumas linhas de relatório) continua como a API manda; as mensagens de erro por código têm texto em espanhol. As planilhas de produtos e de compra aceitam os títulos de coluna nos dois idiomas.

## Corona Gestão: Representante e o consignado ao cliente (tarefa 9.4; §23 e ADR-0016)

Estas linhas valem **sobre** as que dizem "na tela, sempre vendedor" (acima) **no Corona Gestão**. No Corona Vendas valem as linhas da tarefa 9.2. No código e no banco os nomes técnicos continuam (`vendedor`, `vendedores`, `VENDEDOR`, `vendedorId`).

| Antes | Agora, na tela do Corona Gestão | Observação |
| --- | --- | --- |
| vendedor, vendedora | **Representante** | Quem usa o Corona Vendas e atende os clientes da praça. Vale em todo o menu, nas telas, nos relatórios, nos arquivos (PDF, Excel, CSV) e nas mensagens. Plural: **Representantes**. Em espanhol, **representante** / **representantes**. |
| Pessoas → Vendedores | **Pessoas → Representantes** | |
| Vendedor (filtro, coluna) | **Representante** | |
| cliente (do vendedor) | **Cliente** | Quem pega peças da maleta em consignado para revender. Não acessa o sistema. Cada representante tem a sua lista de clientes. |

**Menu novo (Corona Gestão)**

| Onde | Texto (pt) | Texto (es) | Para que serve |
| --- | --- | --- | --- |
| Maletas | **Fechamento da maleta** | Cierre de la maleta | A dona lê o código de cada peça que voltou. A tela e os arquivos têm duas partes separadas: **Peças da maleta** (es: Piezas de la maleta) e **Dinheiro** (es: Dinero). O relatório mostra enviadas, devolvidas pelos clientes, vendidas, ainda com clientes (e com quem), **lidas na volta** e **diferença**, com o valor vendido, a comissão dos clientes, o **Valor recebido** (es: **Valor cobrado**, como no resto do sistema em espanhol) e as **Pendências a receber** (es: Pendientes por cobrar). Sai em PDF, Excel e CSV. |
| Consignado | **Notas dos clientes** | Notas de los clientes | Notas, acertos e recibos dos clientes. Só leitura. |
| Consignado | **Semanas dos representantes** | Semanas de los representantes | Semanas de cada representante, com notas, valor DX, VD, comissão e recebido. |
| Consignado | **Clientes dos representantes** | Clientes de los representantes | Ficha do cliente (endereço, documento, indicado por, como conheceu, comissão, quanto deve, peças com ele, notas) e as três fotos. |
| Consignado | **Avisos do consignado** | Avisos del consignado | O que o balcão fez e o sistema aceitou, mas a dona deve olhar. |

**Palavras e frases do Fechamento da maleta**

- Na tela, "**ler o código**" e "**lidas na volta**", nunca "bipar" (vale a parte 2).
- Botões: **Começar o fechamento**, **Somar peça**, **Concluir o fechamento** (ação sem volta; pede confirmação), **Escolher outra maleta**.
- Confirmação: "As peças lidas voltam ao estoque da loja, até o que a maleta devia ter, e a maleta fica fechada. A diferença não é corrigida sozinha: ela fica no relatório. Essa ação não tem volta."
- Diferença: "Faltam N peças em relação ao que devia estar na maleta. O sistema não corrige sozinho: decida o que fazer com a falta." (ou "Sobram").
- Maleta com situação **Fechada** (antes: Enviada, Conferida, Em acerto, Acertada, Cancelada, Em montagem).
- **Valores por maleta:** o relatório diz "Os valores da maleta são divididos pelo vendido de cada maleta dentro da nota do cliente." (ADR-0016, item 14).

**Situação da nota do cliente:** Em aberto, Pendente, Fechado, Remarcado (§23.4). **Formas de pagamento:** Dinheiro, Pix, Débito, Crédito à vista (sem cheque, sem boleto).

**O acerto antigo da §7.4 fica escondido** (ADR-0016, item 15): enquanto o servidor disser que não é para mostrar (`GET /api/configuracoes/telas`), somem o grupo **Acertos** do menu (Acertos, Pendências do acerto, Comissão por meta), o botão **Acerto** da tela inicial e do **Painel da semana** e o cartão de acertos do resumo. Nada é apagado; ligar de volta é mudar a configuração.

**Fotos do cliente:** foto do cliente e do documento (frente e verso) são dados pessoais. A ficha só as busca quando a dona pede ("Ver a foto") e não as guarda no computador. "A foto ainda não chegou." quando o representante ainda não enviou; isso nunca trava nada.


## 22. Corona Vendas: consignado ao cliente (tarefa 9.2, 19/11/2026)

Vale a especificação §23 (que manda sobre as partes 18 e 20 onde discordarem). **Na tela, o programa fala em "Representante" (quem usa o Corona Vendas; antes "vendedor") e "Cliente" (quem leva peças em consignado para revender)**, nos dois idiomas e nos dois aplicativos. No código e no banco continuam `vendedor` e `cliente`. Os textos moram em `apps/seller-app/src/renderer/textos-pt.ts` e `textos-es.ts`, bloco `consignado` (mesmas chaves nos dois).

| Português | Español (na tela) | Observação |
| --- | --- | --- |
| Representante | Representante | Quem usa o Corona Vendas. |
| Cliente | Cliente | Quem pega peças para revender. Não usa o sistema. |
| Gerenciamento Semanal | Gestión Semanal | A tela principal. Botões: Novo Pedido, Cobrança, Reposição, Troca, Clientes, Maletas e o campo Pesquisar. |
| Novo Pedido | Nuevo Pedido | A saída de peças para um cliente (a nota). |
| Pedido / nota | Pedido / nota | O que um cliente levou numa saída. Número `semana-sequência` (ex.: `0000064-21`). |
| Cobrança | Cobranza | O acerto por nota: o cliente devolve o que não vendeu e paga o vendido menos a comissão. |
| Reposição | Reposición | Acrescenta peças a um cliente com pedido em aberto. |
| Troca | Cambio | Troca uma peça que está com o cliente por outra, sem acerto. |
| Remarcar ("Cliente não veio") | Reprogramar ("El cliente no vino") | Muda a Data do retorno. |
| Nova semana | Nueva semana | Abre a semana seguinte e fecha a anterior. |
| Data do retorno | Fecha de retorno | Saída + 45 dias, editável. |
| Valor DX / DV / VD | Valor DX / DV / VD | Levado (DX), devolvido (DV) e vendido (VD = DX − DV). "Itens DX, DV e VD" são as quantidades de peças. |
| Comis. % / Comissão | Comis. % / Comisión | Parte do vendido que fica com o cliente (padrão 30%, de 0% a 40%). |
| Valor Líq. | Valor neto | Sempre VD − comissão. |
| Pendência | Pendiente / deuda | O que o cliente deve; acumula e entra sozinha no próximo acerto. |
| Recibo | Recibo | Registro de um pagamento. |
| Lançar pagamento | Registrar pago | No cadastro do cliente: abate a pendência mais antiga. |
| Estornar | Anular | Corrigir é estornar (registro novo, com motivo) e fazer de novo. |
| Maleta (código `0001`, `0002`...) | Maleta | O código vem do servidor (tarefa 9.3); só enquanto a maleta ainda não trouxe o código a tela mostra o provisório `M-XXXXXX`. |
| Em aberto, Pendente, Fechado, Remarcado | Abierto, Pendiente, Cerrado, Reprogramado | Situações do pedido; cores: branca, amarela, verde e rosa (o texto aparece sempre, a cor sozinha não basta). |
| Dinheiro, Pix, Débito, Crédito à vista | Efectivo, Pix, Débito, Crédito al contado | Sem cheque, sem boleto, sem parcelado, sem entrada, sem desconto. |

Mensagens que a tela diz (português simples, dizendo o que fazer; em espanhol, as mesmas por código):

- Bipe que não entra: "Esta peça não está na maleta escolhida. Confira o código." / "... Ela está na maleta M-XXXXXX." / "Só há N desta peça na maleta. Nada foi acrescentado."
- Cobrança: "Estas N peça(s) não foram lidas e serão consideradas vendidas. Confirmar?" / "Esta peça não está neste pedido. Confira o código." / "O valor pago passa do total a receber. Confira os valores."
- Pendência no Novo Pedido: "Este cliente deve R$ X. O valor será somado ao próximo acerto."
- Fotos sem internet: "Algumas fotos ainda vão ser enviadas quando houver internet. Pode continuar trabalhando."
- Sem semana: "Não há semana aberta. Toque em "Nova semana" para começar."

### Relatórios do consignado (tarefa 9.3, §23.11)

| Português | Español (na tela) | Observação |
| --- | --- | --- |
| Nota de saída | Nota de salida | Comprovante da saída, com espaço para assinatura. Sem nota fiscal. |
| Relatório do acerto | Informe del cobro | O acerto de uma nota. |
| Situação do cliente | Situación del cliente | O que já vendeu e o que falta. |
| Peças vendidas por cliente | Piezas vendidas por cliente | Com período (De, Até). |
| Relatório da semana / Totais da semana | Informe de la semana / Totales de la semana | A aba Totais. |
| Situação da maleta | Situación de la maleta | Peças na maleta, com clientes (com quem) e vendidas. |
| Imprimir · Salvar em PDF · Preparar para o WhatsApp | Imprimir · Guardar en PDF · Preparar para WhatsApp | O sistema abre a conversa; quem anexa e envia é o representante. |
| Sem cotação | Sin cotización | Aparece no lugar do Dólar e do Guarani quando faltou a cotação do dia do lançamento. |
| Praça | Plaza | No cabeçalho da tela principal e dos relatórios. |

Mensagens: "O PDF foi guardado na pasta ... No WhatsApp que abriu, anexe o PDF e envie. Nada é enviado sozinho." / "Sem internet: o PDF já está guardado na pasta ..., mas o WhatsApp só abre com internet. Quando a internet voltar, toque de novo em "Preparar para o WhatsApp"." / "Este cliente não tem telefone cadastrado. Cadastre o telefone do cliente ou salve o PDF e envie por outro meio."


## Preço unitário (pedido do responsável, 23/11)

- Toda coluna ou rótulo que mostra o preço de uma peça se chama **Preço unitário** (ES: **Precio unitario**): nota da maleta, nota de saída do cliente, peças da maleta, produtos, compras, consulta de código, venda e acerto.
- Não mudam: **Preço de custo**, **Preço de venda** (cadastro e filtros), totais e subtotais.
