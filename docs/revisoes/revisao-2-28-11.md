# Revisão geral 2 (28/11/2026)

Issue 307. Só revisão e relatório: nenhum código foi mudado. Tudo foi feito na pilha isolada do e2e (banco e API descartáveis, dados fictícios, nunca na produção).

## Como foi feito

- Pilha do `testes-e2e` (`npm run e2e`) com a massa fictícia do manual da dona (`testes-e2e/massa-do-manual.ts`, do PR do manual): representante Ana com maleta enviada, uma nota acertada acima da meta e uma nota em aberto; peças de exemplo; cotação do dia.
- Um teste temporário (não commitado) abriu o Corona Gestão nos **dois idiomas** e visitou as **110 rotas** do menu e das áreas de informes, em 1366 x 768. Em cada tela ele guardou o título e conferiu automaticamente: alerta de erro, tela vazia, rolagem horizontal da página, tabela mais larga que o espaço e palavras em português na versão em espanhol. As telas com achado foram fotografadas.
- Além disso, o percurso do dia a dia (entrada, semana, representante, maleta, reposição, estoque, fechamento, notas e meta, compra, cotação, informes, verificação) foi percorrido e fotografado para o manual (PR do manual); o que apareceu de estranho ali está abaixo.
- As capturas estão em `C:/Projetos/manual-lucia/revisao/` (fora do git; nomes `es-<rota>.png` e `pt-<rota>.png`, com `/` trocada por `-`) e a lista completa linha a linha em `C:/Projetos/manual-lucia/revisao/achados.txt`.

## 1. Quebra o uso

### 1.1 Cinco informes de Maletas mostram "Algo salió mal" / "Algo deu errado agora" (os dois idiomas)

- **Telas:** Informes › Maletas: "Maletas por representante y semana" (`relatorios/maleta-remessas`), "Lo que todavía está con el representante, pieza por pieza" (`maleta-saldo`), "Mercadería con cada representante" (`maleta-mercadoria`), "Maletas abiertas y atrasadas" (`maleta-em-aberto`) e "Reposiciones" (`maleta-reposicoes`). Em português: "Maletas por representante e semana", "O que ainda está com o representante, peça a peça", "Mercadoria com cada representante", "Maletas em aberto e atrasadas", "Reposições".
- **Como reproduzir:** entrar como a dona, Informes › Maletas, abrir qualquer um dos cinco. A tela abre com título, filtros e tabela vazia, e um aviso vermelho: "Algo salió mal. Intente de nuevo. Si sigue así, hable con el soporte del sistema." (em português: "Algo deu errado agora. Tente de novo. Se continuar, fale com o suporte do sistema."). Tentar de novo não resolve.
- **Capturas:** `es-relatorios-maleta-remessas.png`, `es-relatorios-maleta-saldo.png`, `es-relatorios-maleta-mercadoria.png`, `es-relatorios-maleta-em-aberto.png`, `es-relatorios-maleta-reposicoes.png` e as `pt-...` das mesmas.
- **O que foi apurado:** a API, chamada direto com o token da dona, responde 200 com linhas para `remessas`, `saldo`, `em-aberto` e `reposicoes` (`GET /api/dona/relatorios/maleta/<nome>?pagina=0&tamanho=20`); o `api.log` não tem nenhum erro de servidor. O erro está, portanto, na ida do app até a API (parâmetros que o app manda) ou na leitura da resposta no app. Isso afeta também a exportação em PDF/Excel/CSV desses informes e o atalho do Painel da semana, que leva a `relatorios/maleta-remessas`.
- **Causa provável (não confirmada):** o tratamento de `maleta-*` em `apps/owner-app/src/renderer/nucleo/relatorios-maleta.ts` (definições; `prepararLinhaDeMaleta`, chamado em `nucleo/relatorios.ts` perto da linha 563) ou a chamada dos relatórios de maleta em `apps/owner-app/src/main/api.ts` (importa `relatorioDeMaletasEmAbertoEAtrasadas`, `relatorioDeMercadoriaPorVendedor` e os irmãos perto das linhas 166-179). O informe "Reposiciones" falha mesmo sem nenhuma linha, o que aponta para o pedido (filtros ou opções dos filtros: representante, semana, praça), não para as linhas.
- **Correção sugerida:** abrir o informe com as ferramentas do Electron (Ctrl+Shift+I) ou pelo teste e2e e ler a resposta da chamada que falha; corrigir; e **adicionar um teste e2e que abra os cinco informes** (hoje nada os pega: o `gestao.test.ts` só abre `relatorios/produtos`). Os testes de componente passam porque usam respostas simuladas.

## 2. Número ou estoque errado

Nada encontrado nesta rodada. Conferido pelo percurso do manual, com contas feitas à mão: maleta da Ana (10 colares + 8 pulseiras), nota da Rosa (8 colares, R$ 1.200,00 = Gs 1.200.000, acima da meta de Gs 700.000 → comissão 40% = R$ 480,00, líquido R$ 720,00, pago R$ 720,00, deve R$ 0,00); fechamento lendo 1 colar e 5 pulseiras: faltam 1 colar e 2 pulseiras (a nota da Lidia deixa 1 pulseira com a cliente), cobrança de R$ 120,00 e pagamento parcial de R$ 50,00 entrando no caixa; "Verificación del dinero" diz "Todo bien"; o estoque da loja desce e sobe como esperado (Anillo de 0 para 12 depois da compra; pulseira em "0 en la tienda" com a Ana).

## 3. Tela confusa ou feia

### 3.1 Textos em português na versão em espanhol

Telas e palavras achadas (a varredura procurou palavras que só existem em português; o resto do texto dessas telas está em espanhol):

| Tela (rota) | O que aparece em português | Captura |
| --- | --- | --- |
| Informes › Registros › Productos (`relatorios/produtos`) | Na parte "Totales": "Produtos sem código de barras", "Com código do fornecedor", "Só com código da loja" e "4 produtos" | vista no manual (`docs/manuais/capturas/12-4.png`, PR do manual) |
| Informes › Registros › Representantes: límite y estado (`relatorios/vendedores`) | "restrição", "consignação" | `es-relatorios-vendedores.png` |
| Informes › Registros › Cambios en los productos (`relatorios/alteracoes-de-produtos`) | "alterações" | `es-relatorios-alteracoes-de-produtos.png` |
| Informes › Registros › Clientes registrados (`relatorios/clientes`) | "vendedor" (duas vezes) | `es-relatorios-clientes.png` |
| Informes › Maletas › Reposiciones (`relatorios/maleta-reposicoes`) | "Reposições" (ao lado do erro do item 1.1) | `es-relatorios-maleta-reposicoes.png` |
| Informes › Control › Auditoría (`relatorios/controle-auditoria`) | "vendedor", "Situação" | `es-relatorios-controle-auditoria.png` |
| Informes › Control › Computadoras y envíos (`relatorios/controle-computadores`) | "liberação" | `es-relatorios-controle-computadores.png` |
| Informes › Control › Accesos (`relatorios/controle-acessos`) | "Gestão" (nome do programa vindo do servidor; deveria ser "Gestión") | `es-relatorios-controle-acessos.png` |

- **Causa provável:** a tradução dos informes é uma tabela de frases (`apps/owner-app/src/renderer/idioma/es/relatorios-mapa.ts`, função de tradução aplicada em `nucleo/relatorios.ts` na linha `traducirDefinicion(RELATORIOS)`): o que não está na tabela cai em português (comportamento já descrito no cabeçalho de `idioma/es/avisos-da-dona.ts`). Os rótulos de totais e valores de coluna que vêm do servidor em português (por exemplo "Produtos sem código de barras") não passam pela tabela.
- **Correção sugerida:** completar a tabela e adicionar um teste que percorra as definições em espanhol e falhe se achar "ção/ões/ão" ou palavras da lista do glossário em português (existe a regra para os textos fixos; falta cobrir rótulos de totais).
- **Observação:** a palavra "cliente" que a varredura acusou é igual nos dois idiomas; não é problema.

### 3.2 Tabelas que não cabem em 1366 px (rolagem para o lado)

Telas em que a tabela passa da largura da janela (a dona precisa rolar para ver as últimas colunas, incluindo "Qué hacer"/"Acciones"):

- Espanhol e português: Stock › Entradas y salidas (`estoque/entradas-e-saidas`), Informes: Clientes registrados, Stock ahora con valor a costo, Entradas y salidas por período, Historial de costo de las piezas, Devoluciones por representante y motivo, Cuánto devolvió cada representante, Pedidos de autorización, e as cinco tabelas de Maletas (dependem do item 1.1).
- Só em espanhol, além dessas: Notas de los clientes (`consignado/notas`, a coluna "Acciones" fica metade fora; capturas `es-consignado-notas.png` e `pt-consignado-notas.png`), Ventas canceladas. Só em português: Produtos e custos, Contagens do estoque e diferenças.
- **Causa provável:** colunas com muitas palavras de cabeçalho e valores com Dólar e Guarani embaixo do Real; a tabela das notas tem 10 colunas (`apps/owner-app/src/renderer/telas/consignado/TelaNotasDoConsignado.tsx` e o CSS das tabelas).
- **Correção sugerida:** o mesmo ajuste que foi feito na Cobrança do Corona Vendas (PR 303): esconder ou juntar colunas pouco usadas, quebrar o texto do botão da linha e fixar a coluna de ações à direita.

### 3.3 Pequenos detalhes do percurso do dia a dia

- **Armar maleta, aviso de limite fora de vista:** ao passar do limite, o aviso "Esta maleta pasa el límite en R$ ..." aparece embaixo do quadro do limite, depois da tabela de peças; com a maleta cheia a dona precisa rolar para achá-lo. Captura: `docs/manuais/capturas/05-6.png` (PR do manual). Sugestão: mostrar o aviso junto do botão "Enviar maleta" ou no alto da tela. Arquivo: `apps/owner-app/src/renderer/telas/maletas/MontagemDaMaleta.tsx`.
- **Fechamento da maleta, "0 piezas para leer":** depois de concluir, o quadro mostra "Piezas que volvieron después: 0 piezas para leer" e "Todavía con clientes: 1 pieza" ao mesmo tempo; a primeira linha com zero confunde. Captura: `docs/manuais/capturas/08-10.png`. Sugestão: esconder a linha quando for zero.
- **Representantes, salvar:** ao tocar em Guardar num representante novo, a tela continua no mesmo formulário (rolada para o fim) e a confirmação "fue registrado" não fica à vista; para ver o resultado a dona precisa voltar à lista. Captura: `docs/manuais/capturas/04-5.png`. Sugestão: levar para a lista com a mensagem no alto, como em Semanas.
- **Início: avisos de instalação nova:** numa instalação sem histórico aparecem "La verificación de hoy no se ejecutó" e "Las cotizaciones de 17/11/2026, ... no llegaron". É esperado em banco novo; só conferir que não aparecem na produção.

## 4. Pode esperar

- Pendências que já eram conhecidas (Excel do fechamento sem a lista das faltas, pull do Vendas sem a regra da meta, 152 produtos sem foto, backup no Google Drive, aviso de queda) continuam como estão.
- O manual e o vídeo da dona estão em WebM; um MP4 pode ser pedido por quem for mandar o vídeo pelo WhatsApp.

## 5. O que não deu para testar

- **Corona Vendas:** só foi usado para criar as notas (Novo Pedido, Cobrança, acerto) e funcionou sem erro, mas as telas dele **não** foram percorridas e fotografadas nos dois idiomas. Fica para uma terceira rodada (`seller:dev` ou o e2e do Vendas).
- Nas telas da dona só foi feito **olhar** (abrir cada rota). Não foram exercitados: formulários de produtos e fornecedores, importação de planilha, contagem de estoque, devoluções e trocas, financeiro (caixa, contas a pagar e a receber), exportação de PDF/Excel/CSV, fotos de produtos, usuários e computadores, atualização do programa.
- Telas com dados: a massa tem 1 representante com maleta, 2 notas e 4 peças; listas longas, paginação e filtros com muitos resultados não foram testados.
- Tamanho de janela diferente de 1366 x 768 e o programa instalado (instalador de produção) não foram testados.
