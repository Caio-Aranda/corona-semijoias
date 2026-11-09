# Glossário da interface

Referência única das palavras, dos textos padrão e das mensagens que aparecem nas telas dos dois apps (app da dona e app do vendedor). Vale a regra da §19.1 da especificação: a tela fala a língua da loja, não a do programador. Quem usa é uma pessoa leiga em tecnologia.

Como usar:

- Antes de escrever um texto de tela, procure o termo aqui. Se não existir, acrescente primeiro neste arquivo.
- A especificação (`docs/ESPECIFICACAO.md`) manda. Se este glossário e a especificação divergirem, vale a especificação e a divergência vira dúvida (parte 10).
- Português simples em todo o arquivo, exceto na coluna "Equivalente técnico", que existe só para a equipe de desenvolvimento e **nunca vai para a tela**.

## 1. Termos da loja

| Na tela | Equivalente técnico | Observação |
| --- | --- | --- |
| dona | perfil `ADMIN` | Quem é dona da loja e usa o app da dona. |
| vendedor | `vendedores`, perfil `VENDEDOR` | Quem leva a maleta e vende. "Vendedor" ou "vendedora": ver dúvida 1. |
| cliente | `clientes` | Quem compra do vendedor. |
| maleta | remessa (`remessas`) | Conjunto de peças entregue ao vendedor, com quantidade e preço fixados. A palavra "remessa" só aparece no PDF "Nota de remessa" (§19.1). |
| reposição | remessa do tipo `REPOSICAO` | Maleta complementar, sempre criada pela dona. |
| acerto | `acertos` | Encontro semanal em que o vendedor devolve o que sobrou, repassa o dinheiro e recebe a comissão. |
| semana | `semanas` | Período de controle: maleta, cobrança e acerto são semanais. Como numerar as semanas: a confirmar (parte 9). |
| peça | produto / item de maleta, venda ou troca | A peça física, na maleta, na venda, na troca e na devolução. No cadastro e na consulta, "produto" é aceito (ver dúvida 6). |
| código de barras | `produto_codigos` | Pode haver mais de um por peça. Mostrado sem máscara, como texto. |
| ler o código | bipar | O leitor "digita" o código. Ver dúvida 3. |
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
| O que precisa da sua atenção | bloco da tela inicial da dona | Acertos pendentes, devoluções para autorizar, estoque baixo, maletas atrasadas (§19.3). |

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
| Maleta | `CANCELADA` | Cancelada | Ver dúvida 2. |
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
| Forma de pagamento | entrada | Entrada | §7.4. Ver dúvida 5. |

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
| Atualizar agora | Instala a versão nova do aplicativo. |
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

(No app da dona, a última frase fica pendente: ver dúvida 8.)

**Bloqueio temporário por tentativas**

> **Acesso bloqueado por alguns minutos.**
> Você errou a senha várias vezes. Por segurança, espere {minutos} minutos e tente de novo.

**Usuário desativado**

> **Seu acesso foi desativado.**
> Fale com a dona da loja para voltar a usar o sistema.

**Senha temporária (troca obrigatória no primeiro acesso)**

> **Crie sua senha nova.**
> A senha que você recebeu é temporária. Para continuar, crie uma senha nova que só você saiba.
> Campos: **Senha nova** e **Repita a senha nova**. Botão: **Salvar senha nova**.

**Regras da nova senha** (valores propostos; ver dúvida 7)

> Sua senha nova precisa ter:
> - pelo menos 8 letras, números ou símbolos;
> - letras e números;
> - uma senha diferente da temporária.

Avisos no campo:

> A senha nova ainda não está boa. Ela precisa ter pelo menos 8 caracteres, com letras e números.

> As duas senhas não são iguais. Digite a mesma senha nos dois campos.

> A senha nova precisa ser diferente da temporária. Escolha outra.

**Aplicativo errado para o perfil**

Dona no app do vendedor:

> **Este é o aplicativo do vendedor.**
> Seu acesso é de dona da loja. Para entrar, abra o aplicativo da dona.

Vendedor no app da dona:

> **Este é o aplicativo da dona da loja.**
> Seu acesso é de vendedor. Para entrar, abra o aplicativo do vendedor.

**Aplicativo desatualizado**

> **Seu aplicativo está desatualizado.**
> Atualize para continuar usando.
> Botão: **Atualizar agora**.

**Sessão expirada**

> **Sua sessão terminou.**
> Entre de novo com seu usuário e sua senha para continuar.

Quando passa o prazo máximo sem internet (app do vendedor, §11; ver dúvida 9):

> **Este computador ficou muitos dias sem internet.**
> Conecte-se à internet para continuar.

**Sem conexão no app da dona** (o app da dona só funciona com internet, §4)

> **Sem internet.**
> O aplicativo da dona só funciona com internet. Conecte-se e tente de novo.
> Botão: **Tentar de novo**.

## 7. Estado de conexão do app do vendedor

Sempre em palavras comuns (§19.11). Nunca "fila", "sincronização" ou "pendentes".

| Situação | Texto |
| --- | --- |
| Com internet, nada a enviar | Com internet. Tudo enviado. |
| Com internet, enviando | Com internet. Enviando suas vendas… |
| Sem internet | Sem internet. Suas vendas estão salvas e serão enviadas quando você conectar. |
| Vendas esperando para ser enviadas | 1 venda aguardando envio / 3 vendas aguardando envio |

Se também houver outros registros esperando (clientes, devoluções), ver dúvida 10.

## 8. Formatos

| Dado | Formato | Exemplo |
| --- | --- | --- |
| Dinheiro | R$, ponto nos milhares, vírgula nos centavos, sempre 2 casas | R$ 1.234,56 |
| Dinheiro negativo | sinal na frente | -R$ 50,00 |
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

## 9. A confirmar

Termos que dependem de resposta do cliente. **Não usar nas telas** até a resposta.

| Termo | O que se sabe | Onde está |
| --- | --- | --- |
| DX | Provavelmente "valor consignado (enviado)". A confirmar. | §2 e §17.1 |
| DV | Provavelmente "valor devolvido". A confirmar. | §2 e §17.1 |
| VD | Provavelmente "valor vendido". A confirmar. | §2 e §17.1 |
| Numeração da semana | Como numerar as semanas e quais são o primeiro e o último dia. A confirmar. | §17.8 |
| Diferença da troca | Se o crédito do cliente pode ser devolvido em dinheiro. A confirmar. | §17.7 |

## 10. Dúvidas para a dona

Não decididas aqui. Cada uma precisa de resposta antes de virar texto de tela.

1. **"Vendedor" ou "vendedora"?** A tela de referência usa "Vendedora"; o CLAUDE.md e este glossário usam "vendedor". Como a dona prefere ver na tela?
2. **"Cancelar" ou "Anular"?** A especificação (§19.5) mantém "cancelar" para vendas, com a explicação de que é um novo lançamento, e este glossário segue isso. A palavra "Anular" só fica como pergunta: a dona entende melhor "cancelar venda" ou "anular venda"? Vale também para a maleta "Cancelada".
3. **"Bipar" ou "ler o código"?** A especificação usa "bipar". O glossário usa "ler o código" nas telas. Qual a equipe da loja usa?
4. **Nome dos dois aplicativos.** Como a dona quer chamar o app da dona e o app do vendedor (nome do instalador e do atalho)? As mensagens de aplicativo errado usam "aplicativo da dona" e "aplicativo do vendedor".
5. **"Entrada".** A forma de pagamento "Entrada" (§7.4) é entendida por todos como o valor pago adiantado? Precisa de uma frase de ajuda?
6. **"Peça" ou "produto".** O glossário usa "peça" na maleta, na venda, na troca e na devolução, e aceita "produto" no cadastro e na consulta (como na tela inicial da dona, "Cadastrar produto"). Isso está bom para a dona?
7. **Regras da senha nova.** A especificação não define as regras. Os valores da parte 6 (8 caracteres, letras e números, diferente da temporária) são uma proposta a ser fixada na decisão de autenticação.
8. **Esqueci a senha, no app da dona.** A dona redefine a senha dos vendedores (§11), mas a especificação não diz quem redefine a da própria dona. O texto fica pendente.
9. **Prazo máximo sem internet (10 dias).** A especificação não diz o que acontece com as vendas ainda não enviadas quando o prazo termina. O texto da parte 6 só pede para conectar.
10. **Registros esperando envio que não são vendas.** Quando houver clientes ou devoluções esperando, a tela mostra "N vendas aguardando envio" ou uma palavra mais geral, como "N itens aguardando envio"?
11. **Tempo do bloqueio por tentativas.** A especificação fala em limite de tentativas (§11) sem dizer por quanto tempo o acesso fica bloqueado. O texto usa `{minutos}`.
12. **Nomes de relatórios e aviso da especificação (§12).** Alguns nomes de relatório ("Remessas por vendedor e semana", "Movimentações por período") e o aviso "dados até a última sincronização em dd/mm hh:mm, com N operações pendentes" usam palavras da lista de proibidas (parte 2), o que conflita com a §19.1 e a §19.11. O glossário não decide esses textos: a especificação precisa ser ajustada antes, e o nome de tela de cada relatório será definido na tarefa daquele relatório.
