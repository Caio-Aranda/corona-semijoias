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
| código de barras | `produto_codigos` | Pode haver mais de um por peça. Mostrado sem máscara, como texto. |
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
| limite de consignação | `limite_consignacao` | Valor máximo de peças que o vendedor pode levar na maleta. |
| foto | `produtos_fotos` | Aparece junto com a peça sempre que ela é lida ou pesquisada (§20). |
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
| Atualizar agora | Instala a versão nova do aplicativo. Só existe quando houver atualização automática (Fase 9). Antes disso, o aviso de aplicativo desatualizado mostra só o texto. |
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

O botão **Atualizar agora** só aparece quando houver atualização automática (Fase 9). Antes disso, o aviso mostra só o texto.

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
