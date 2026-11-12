# Roteiro do teste com um vendedor real sem internet

Para o **responsável** aplicar, sentado com a dona e um vendedor de verdade (§19 da especificação, Fase 5). Não é para o vendedor ler. O objetivo é descobrir onde o Corona Vendas atrapalha quando falta internet, não avaliar o vendedor. O teste em si **não foi feito pelo sistema**: é pendência do responsável (ver [pendencias-do-responsavel.md](pendencias-do-responsavel.md), item 14).

Critério de aceite: sem treinamento longo e sem ajuda, o vendedor consegue vender, entender o que está acontecendo com as vendas dele e ver tudo chegar na dona depois, sem perder nenhuma venda e sem medo.

## Antes de começar

1. **Computador de verdade do vendedor** (um dos notebooks que ele usa), com o Corona Vendas instalado pelo instalador (`Corona-Vendas-Setup-<versão>.exe`), não pelo modo de desenvolvimento. Servidor de verdade (a VPS) ou, se ainda não houver VPS, a API do computador de testes na mesma rede.
2. Na tela da dona (Corona Gestão): o vendedor cadastrado e ativo, **uma maleta enviada** com pelo menos 6 peças reais (com código e foto), 2 clientes cadastrados e a cotação do dia lançada.
3. **Primeira entrada com internet:** o vendedor entra com a senha dele e confere a maleta (a venda só abre depois de conferir). Espere o indicador mostrar "Com internet. Tudo enviado." e as fotos terminarem de baixar.
4. Tenha à mão: papel e caneta (ou gravador, se ele concordar), o celular com cronômetro e o [PROBLEMAS-DE-USO.md](PROBLEMAS-DE-USO.md) aberto para registrar.
5. Combine com a dona que ela fica olhando a tela dela (Corona Gestão) e **não ajuda** o vendedor.

## Como apresentar

- Diga: "Quem está sendo testado é o sistema, não você. Não existe errado."
- Peça para **pensar em voz alta**. **Nunca ajude antes de ele tentar.** Se travar, espere um bom tempo, depois pergunte "o que você faria agora?". Só então mostre, anotando que precisou de ajuda.
- Não use palavras técnicas e não diga onde clicar.
- Para tirar a internet: tire o cabo ou desligue o Wi-Fi do computador (modo avião do Windows). Para voltar, ligue de novo.

## As tarefas, uma de cada vez

Cada uma com as palavras que o vendedor usaria. Anote o tempo, onde hesitou, onde errou e as frases dele.

| #   | Peça ao vendedor                                                                                 | O que deve acontecer                                                                                                                        | O que observar                                                                |
| --- | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| 1   | (Com internet) "Mostre para mim a sua maleta."                                                   | Vê a maleta com fotos e o indicador "Com internet. Tudo enviado."                                                                           | Acha "Minha maleta" sozinho? Entende o indicador?                             |
| 2   | **Tire a internet.** "Veja se o programa ainda funciona e me diga o que ele está dizendo."       | O indicador passa a dizer "Sem internet. Suas vendas estão salvas e serão enviadas quando você conectar."                                  | Leu? Entendeu que pode continuar? Ficou com medo?                             |
| 3   | "Venda uma peça para este cliente, à vista."                                                     | A venda é feita; o indicador mostra "1 venda aguardando envio". As fotos continuam aparecendo.                                              | Quanto tempo levou? Precisou de ajuda? Achou o cliente? Entendeu o desconto?  |
| 4   | "Venda mais duas peças, uma a prazo, para o outro cliente."                                      | Ficam 3 vendas aguardando envio.                                                                                                            | O parcelamento ficou claro? Erros de leitura do código?                       |
| 5   | "Cadastre uma cliente nova que acabou de chegar."                                                | O cadastro é feito e entra na contagem ("N itens aguardando envio").                                                                        | Preencheu sem ajuda? O que não entendeu?                                      |
| 6   | "Você vendeu a peça errada. Desfaça essa venda." (estorno no mesmo dia)                          | O estorno é feito sem internet e a peça volta para a maleta.                                                                                | Achou o botão? Entendeu o que aconteceu?                                      |
| 7   | "Quanto você vendeu hoje? E quanto os seus clientes ainda devem?"                                | "Meus números" mostra os valores em Real, Dólar e Guarani, com "Dados até dd/mm às hh:mm" e as vendas aguardando envio.                     | Entendeu a data dos dados? Confiou nos números?                               |
| 8   | **Feche o programa e abra de novo, ainda sem internet.**                                         | O programa pede a **senha** (mesmo sem internet) e abre com tudo. As vendas continuam lá.                                                   | Lembrou a senha? Estranhou ter de digitar de novo?                            |
| 9   | **Volte a internet.** "Me diga o que aconteceu."                                                 | O indicador passa por "Com internet. Enviando suas vendas…" até "Com internet. Tudo enviado." Sem ação do vendedor.                         | Percebeu sozinho? Confia que as vendas chegaram?                              |
| 10  | (A dona) "Veja na sua tela as vendas do vendedor."                                               | As vendas aparecem na tela da dona, com os valores e a hora em que foram feitas no computador dele.                                         | A dona confere venda por venda com o papel do vendedor. Alguma diferença?     |

### Se der tempo (cenários difíceis, um por vez)

- **Venda recusada.** Antes do teste, a dona tira da maleta a peça que o vendedor vai vender (com o computador dele sem internet). O vendedor vende; volta a internet. Esperado: o vendedor vê "1 venda não foi aceita. A dona já foi avisada." com o motivo em palavras simples e o botão **Entendi**; a dona vê em "Envios com problema" com **Tentar de novo** e **Descartar**. Observar: o vendedor entende o que fazer? A dona entende?
- **Prazo.** Deixe o computador **sem internet por mais de 7 dias** de verdade (ou combine uma simulação com o responsável técnico). Esperado: o aviso "Conecte este computador à internet em breve." nos últimos 3 dias e, passados os 10 dias, o bloqueio de vendas novas com o texto "Este computador ficou muitos dias sem internet." As vendas guardadas continuam salvas.
- **Troca de computador.** A dona troca o computador do vendedor na ficha dele ("Trocar computador"). Esperado: o antigo envia o que tem e depois avisa que foi desligado da loja.
- **Atualização do programa com vendas aguardando:** ver [roteiro-atualizacao-com-vendas-aguardando.md](roteiro-atualizacao-com-vendas-aguardando.md).

## O que observar em todas

Quanto tempo levou, **onde hesitou**, **onde errou**, **o que não entendeu** (palavra ou botão), onde precisou de ajuda e frases como "isto aqui o que é?". Um silêncio longo olhando a tela já é um sinal. Atenção especial a: o vendedor **teve medo de perder a venda**? Usou por conta própria palavras como "fila", "sincronizar" ou "offline" (isso mostra como ele pensa, não o que a tela deve dizer)?

## Como registrar

Cada problema vira uma entrada nova em [PROBLEMAS-DE-USO.md](PROBLEMAS-DE-USO.md), com data, tela, quem testou, o que aconteceu, gravidade e situação. Escreva o que **aconteceu**, não a sua opinião do que consertar; use as palavras dele. No fim, pergunte "o que foi mais difícil?" e "o que você mudaria?". Se alguma venda não chegou na dona ou chegou duplicada, é **gravidade máxima**: pare o teste e avise o time.
