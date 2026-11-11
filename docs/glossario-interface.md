# Glossário da interface

Referência única das palavras, dos textos padrão e das mensagens que aparecem nas telas dos dois apps (app da dona e app do vendedor). Vale a regra da §19.1 da especificação: a tela fala a língua da loja, não a do programador. Quem usa é uma pessoa leiga em tecnologia.

Como usar:

- Antes de escrever um texto de tela, procure o termo aqui. Se não existir, acrescente primeiro neste arquivo.
- A especificação (`docs/ESPECIFICACAO.md`) manda. Se este glossário e a especificação divergirem, vale a especificação e a divergência vira dúvida (parte 10).
- Português simples em todo o arquivo, exceto na coluna "Equivalente técnico", que existe só para a equipe de desenvolvimento e **nunca vai para a tela**.

## Decisões tomadas

Em 09/11/2026 o responsável resolveu as dúvidas que estavam abertas neste glossário. Só falta a da "Entrada" (parte 10).

- Na tela, sempre "vendedor".
- Cancelar venda segue a §19.5: "Cancelar venda", com a explicação de que é um novo lançamento. A palavra "anular" não é usada.
- Na tela, sempre "ler o código" ("Leia o código de novo"). "Bipar" e "bipe" nunca aparecem na tela.
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
| crédito do cliente | `creditos_cliente` | Valor que sobra de uma troca e que o cliente usa numa compra futura. |
| conferência | `CONFERIDA`, conferência da maleta | O vendedor confere as peças recebidas contra a lista da maleta. |
| cobrança | cobrança | Recebimento do cliente pelo vendedor e repasse do vendedor à dona. |
| repasse | repasse no acerto | Dinheiro que o vendedor entrega à dona no acerto. |
| comissão | `comissoes` | Parte da venda que fica com o vendedor. |
| pendência | `pendencias_acerto` | Peça que faltou ou valor que ficou em aberto no acerto. |
| pedido de autorização | `autorizacoes_excecao` | O vendedor pede à dona para aceitar uma devolução fora do prazo. |
| limite de consignação | `limite_consignacao`, `limite_consignacao_ilimitado` | Valor máximo, em reais e a preço de venda, das peças que o vendedor pode ter em mãos. É obrigatório: a dona preenche o valor ou marca **Sem limite**. Zero vale zero (não é "sem limite"). Sem uma das duas escolhas, o vendedor não recebe maleta. |
| foto | `produtos_fotos` | Aparece junto com a peça sempre que ela é lida ou pesquisada (§20). |
| estoque | `produtos.estoque_atual`, estoque central | Quantidade de cada produto guardada na loja (fora das maletas). Ver parte 12. |
| entrada de compra | `compras`, `itens_compra` | Registro das peças que chegaram de um fornecedor. Na tela, "Receber compra". Ver parte 12. |
| entradas e saídas | `movimentacoes_estoque` | Histórico de tudo o que aumentou ou diminuiu o estoque. A palavra "movimentação" nunca aparece na tela (parte 2). |
| ajuste | movimento `AJUSTE` | Correção do estoque feita pela dona, sempre com o motivo. |
| contagem do estoque | `inventarios` | Conferir peça por peça o que há na loja, lendo os códigos. A palavra "inventário" não aparece na tela. |
| diferença | divergência, `itens_inventario` | Quando o que foi contado não bate com o que o sistema esperava. |
| estoque mínimo | `produtos.estoque_minimo` | Quantidade abaixo da qual o sistema avisa que está acabando. |
| produtos parados | relatório de produtos parados | Produtos que não saem do estoque há muito tempo. |
| sem foto | imagem padrão "sem foto" | Imagem com a marca, nunca um espaço quebrado. A falta de foto nunca trava a venda. |
| cotação | taxa PTAX de venda do Banco Central, tabela `cotacoes` (ADR-0006) | Quanto valem o Dólar e o Guarani em reais num dia, segundo o Banco Central. Sempre com a data: "Cotação do Banco Central de 06/11/2026". Ver parte 11. |
| O que precisa da sua atenção | bloco da tela inicial da dona | Acertos pendentes, devoluções para autorizar, estoque baixo, maletas atrasadas, envios com problema (§19.3). |

Botões da tela inicial (§19.3 e §19.7):

- App da dona: **Montar maleta**, **Receber compra**, **Fazer acerto**, **Cadastrar produto**, **Relatórios**.
- App do vendedor: **Vender**, **Minha maleta**, **Meus clientes**, **Meus números**.

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
| Cheque | `A_COMPENSAR` | A compensar | O banco ainda não confirmou o valor. |
| Cheque | `COMPENSADO` | Compensado | O valor entrou. |
| Cheque | `DEVOLVIDO` | Devolvido | O banco devolveu o cheque. |
| Forma de pagamento | dinheiro | Dinheiro | §7.4. |
| Forma de pagamento | Pix | Pix | §7.4. Escrever "Pix", nunca "PIX". |
| Forma de pagamento | cheque | Cheque | §7.4. Mostra também a situação do cheque, acima. |
| Forma de pagamento | boleto | Boleto | §7.4. |
| Forma de pagamento | entrada | Entrada (a confirmar, não usar na tela) | §7.4. O significado ainda não foi confirmado (parte 9). Não aparece em nenhuma tela até a resposta. |

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

Desativar o próprio acesso (a dona não se desativa, para não ficar sem acesso):

> **Você não pode desativar o seu próprio acesso.**
> Peça a outra pessoa administradora para fazer isso.

Mudar o próprio tipo de acesso (a dona não se rebaixa):

> **Você não pode mudar o seu próprio tipo de acesso.**
> Peça a outra pessoa administradora para fazer isso.

Desativar ou rebaixar a última pessoa administradora ativa:

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

Os quatro botões grandes são os da parte 1 (**Vender**, **Minha maleta**, **Meus clientes**, **Meus números**) e, no canto, **Sair**. Enquanto uma função não existe (Fase 0), o botão mostra o mesmo texto "Em breve." da tela inicial da dona, com o nome do botão no lugar de {Nome do botão}.

Desde a Fase 4 os quatro botões funcionam (o "Em breve." só vale para função que ainda não existe).

**Telas do vendedor (Corona Vendas, Fase 4)**

Os textos moram em `apps/seller-app/src/renderer/textos.ts`. Termos: **Minha maleta** (peças com saldo, preço e foto), **Conferir maleta** (o vendedor lê o código de cada peça recebida; ação sem volta: **Concluir conferência**), **Vender**, **Concluir venda** (nunca "OK"), **Estornar venda** (o vendedor só estorna no mesmo dia; depois, a dona), **Meus clientes**, **Cadastrar cliente**, **Meus números** (vendas por cliente, peça ou dia; recebimentos por forma; o que os clientes ainda devem). Forma de pagamento: **Dinheiro, Pix, Cartão, Cheque** ou **A prazo (fiado)**. Frases fixas:

> Esta peça não consta na sua maleta. (peça de fora, no leitor)
> Esta peça acabou na sua maleta. Escolha outra peça ou fale com a dona. (saldo zero)
> Esta maleta já está em outro aparelho. Peça à dona para liberar este aparelho.
> O desconto passa do limite que a dona definiu. Diminua o desconto.
> Sem internet. Conecte-se e tente de novo.

Na ficha da maleta, no Corona Gestão: **Conferência do vendedor** mostra "Na maleta", "Ele contou" e "Diferença" por peça; os movimentos aparecem como "Vendida pelo vendedor" e "Venda desfeita (estorno)".

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

Peça que já tem 5 fotos:

> **Esta peça já tem 5 fotos.**
> Exclua uma das fotos para poder enviar outra.

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

- Campos: **Nome**, **Usuário**, **Tipo de acesso** (**Dona** ou **Vendedor**) e, para o vendedor, **Vendedor**. Filtros: **Tipo de acesso** (Todos, Dona, Vendedor) e **Situação** (Todos, Ativos, Desativados).
- Botões: **Novo usuário**, **Editar**, **Desativar usuário**, **Reativar usuário**, **Nova senha temporária**.
- Senha temporária (aparece uma única vez, com o botão **Copiar senha**): "Anote esta senha agora. Ela não aparece de novo." e "Entregue a senha só para {nome}. No primeiro acesso, ele ou ela cria uma senha nova." A senha vale por 7 dias.
- Desativar: "{Nome} vai sair do sistema agora e não vai mais conseguir entrar. Você pode reativar depois." Botões: **Voltar** e **Desativar usuário**.
- Reativar: "{Nome} volta a poder entrar com a senha que já tinha. Quem estava dentro precisa entrar de novo." Botões: **Voltar** e **Reativar usuário**.
- Nova senha temporária: "A senha atual de {nome} deixa de valer e quem estiver dentro sai do sistema agora. Será criada uma senha temporária nova." Botões: **Voltar** e **Criar senha temporária**.
- As regras do servidor (não desativar a si mesma, não mudar o próprio acesso, manter uma administradora) aparecem com o texto do servidor, acima.

**Cadastros básicos (Cadastros e Pessoas → Clientes).** Fornecedores, Categorias, Praças, Semanas, Restrições e Clientes usam a mesma tela.

- Botões: **Novo fornecedor / Nova categoria / Nova praça / Nova semana / Nova restrição / Novo cliente**, **Editar**, **Excluir {item}**, **Salvar**, **Cancelar**, **Voltar**.
- Campos: Fornecedor (**Nome do fornecedor (razão social)**, **CNPJ**, **Nome do contato**, **Telefone**, **E-mail**, **Endereço**); Categoria e Praça (**Nome**); Semana (**Número da semana**, **Primeiro dia**, **Último dia**, **Situação**: Aberta ou Fechada); Restrição (**Código**, **Descrição**, **Impede montar maleta para o vendedor com esta restrição**; na lista, coluna **Impede montar maleta** com Sim ou Não); Cliente (**Nome do cliente**, **CPF**, **Telefone**, **WhatsApp**, **Vendedor responsável**, **Observações**, **Endereço**).
- Excluir: "{Nome} some das listas. O que já foi registrado com ele continua guardado. Por aqui não dá para desfazer." (com "ela" nos femininos). Botões: **Voltar** e **Excluir {item}**.
- Depois de salvar: "{Nome} foi cadastrado." (ou "cadastrada"), "Os dados de {nome} foram salvos.", "{Nome} foi excluído." (ou "excluída").
- **Ativo e inativo** (tarefa 1.14; fornecedores, categorias, praças, restrições e clientes; as semanas têm só Aberta e Fechada). Palavras: **Ativo**, **Inativo**, **Inativar {item}**, **Reativar {item}**. Filtro **Situação**: **Ativos** (já vem escolhido), **Inativos**, **Todos**. A lista mostra a coluna **Situação** (Ativo ou Inativo). Inativar não é excluir: o cadastro some das listas de escolha e das buscas, mas continua nos relatórios e no histórico.
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
| Problema num envio | Mostrar em "envios com problema", na tela inicial da dona (§19.3) |

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
| Entrada | Forma de pagamento da §7.4 cujo significado não foi confirmado. Hipótese 1: valor pago pelo vendedor ao levar a maleta, abatido no acerto. Hipótese 2: entrada de uma venda parcelada. A confirmar. | §17.12 |

## 10. Dúvidas para a dona

Só uma dúvida segue em aberto. As outras foram resolvidas em 09/11/2026 (ver "Decisões tomadas", no topo).

1. **"Entrada".** O que significa "Entrada" na tela de referência: o valor pago pelo vendedor ao levar a maleta, abatido no acerto, ou a entrada de uma venda parcelada? Ela é obrigatória? Como se calcula e o que acontece no acerto? Até a resposta, a palavra não aparece em nenhuma tela (§17.12). A resposta é necessária antes da Fase 3.

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
- Ninguém digita cotação nem valor em Dólar ou Guarani: os campos de valor são sempre em reais.

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
- Leitura: **Ler uma peça**, campo **Código da peça ou nome**, botão **Colocar na maleta**. Peça lida: **Última peça lida** com **No estoque**, **Nesta maleta** e **Preço**. Recusas: **Peça não encontrada**, "está desativada", "está sem estoque", "Só há N peças ... no estoque".
- Lista: **Quantidade**, **Atualizar**, **Tirar**; totais **Peças na maleta** e **Valor da maleta**.
- Limite: **Limite do vendedor**, **Já está com ele**, **Esta maleta**, **Sobra**; "Sem limite" marcado: **Sem limite**; ainda não definido: **Limite não definido** e "Defina o limite deste vendedor antes de montar a maleta". Frase da montagem: "Limite R$ X — em mãos R$ Y — pode levar mais R$ Z".
- Envio: botão **Enviar maleta**, confirmação "Enviar esta maleta?" (vendedor, peças e valor), depois **Maleta enviada**; reenvio: **Esta maleta já estava enviada**. O botão **Imprimir nota** só aparece quando a tarefa 3.7 ligar a impressão.
