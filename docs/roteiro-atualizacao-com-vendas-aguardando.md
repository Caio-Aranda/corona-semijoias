# Roteiro manual: instalador de verdade trocando de versão com vendas aguardando

Para o **responsável** (ou quem ele indicar) fazer num computador Windows. Cobre o que os testes automáticos da tarefa 5.13 não alcançam: o instalador NSIS de verdade, o app instalado em `%LocalAppData%\Programs\corona-vendas` e a atualização automática. Os testes automáticos já provam, sem o instalador, que o banco local migra sem tocar nas vendas aguardando, que a ordem é mantida, que nada é enviado duas vezes e que o servidor aceita o formato antigo por 90 dias. **Não foi automatizado** porque exige instalar de verdade duas versões, um servidor de atualização e um computador de teste que se possa sujar; fica como pendência do responsável ([pendencias-do-responsavel.md](pendencias-do-responsavel.md), item 15).

Mecanismo de publicar e de atualizar: [entrega-corona-vendas.md](entrega-corona-vendas.md).

## O que precisa estar pronto

- Um computador Windows de teste (não o do vendedor de verdade) e o Docker do desenvolvimento, ou a VPS.
- `apps/seller-app/.env.producao` e `.env.publicar` configurados (modelos `.example`), apontando para o servidor de teste.
- A API com `APP_VERSAO_MINIMA_VENDAS` **menor** do que a versão antiga que vai ser instalada (para o primeiro teste), e uma maleta conferida para um vendedor de teste.

## Passo a passo

1. **Versão antiga.** Em `apps/seller-app/package.json`, `version` = X.Y.Z (por exemplo 1.0.0). `npm run vendas:empacotar` e instale `Corona-Vendas-Setup-X.Y.Z.exe` no computador de teste.
2. Entre com o vendedor de teste, com internet, e espere "Com internet. Tudo enviado.".
3. **Tire a internet** do computador (modo avião). Faça **3 vendas** (uma a prazo), **1 estorno** e **1 cliente novo**. O indicador deve mostrar itens aguardando envio. Anote o que foi feito (cliente, peça, valor) e a hora.
4. **Versão nova.** Aumente `version` (X.Y.Z+1) e rode `npm run vendas:publicar` (o instalador sobe e depois o `latest.yml`). O computador de teste continua **sem internet**.
5. **Caso A, atualizar sem ter enviado.** Ligue a internet **e feche o programa logo em seguida** (antes de o indicador dizer "Tudo enviado"). O app baixa a versão nova em segundo plano e, ao fechar, antes de instalar, **para de buscar dados, espera o envio (até 30 s), tenta mais uma vez e fecha o banco**. Depois o instalador troca de versão.
6. Abra o programa de novo (já na versão nova). Esperado:
   - o programa **pede a senha** e abre sem erro, sem a mensagem "Não conseguimos abrir os dados deste computador" nem a de dados mais novos que o programa;
   - o indicador mostra "Com internet. Tudo enviado." (ou ainda enviando, e depois "Tudo enviado.");
   - na tela da dona: as 3 vendas, o estorno e o cliente chegaram **uma vez só**, na ordem, com a hora em que foram feitos (antes da atualização);
   - existe a cópia do banco de antes da migração (o app guarda as duas últimas);
   - a conferência da maleta e as fotos continuam.
7. **Caso B, sem nenhuma internet durante a troca.** Repita os passos 1 a 3. Instale a versão nova **por cima, à mão** (execute o instalador novo), ainda sem internet. Abra: as vendas devem estar lá ("N vendas aguardando envio"). Ligue a internet: tudo é enviado uma vez.
8. **Caso C, app abaixo da versão mínima.** Suba `APP_VERSAO_MINIMA_VENDAS` do servidor acima da versão antiga, com o computador ainda na antiga e com vendas aguardando. Ligue a internet. Esperado: o indicador diz "Seu aplicativo está desatualizado." e **as vendas guardadas são enviadas mesmo assim**; maletas e dados novos não chegam até atualizar.
9. **Caso D, atualização no meio de um envio.** Com muitas vendas aguardando (umas 40), ligue a internet e feche o programa durante o envio. Reabra: nada duplicado nem perdido (confira pelo relatório de vendas da dona).

## O que anotar

Para cada caso: passou ou não, o que a tela disse (texto exato), o tempo do fechamento e qualquer mensagem de erro. Se **uma venda sumiu ou veio duplicada**, é gravidade máxima: guarde a pasta de dados do app (`%AppData%`, pasta do Corona Vendas) e o log da API e abra uma tarefa. Resultado e data entram em [PROBLEMAS-DE-USO.md](PROBLEMAS-DE-USO.md) ou, se tudo passar, numa linha no [PROGRESSO.md](PROGRESSO.md).
