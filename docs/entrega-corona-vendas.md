# Corona Vendas: instalador e atualização (Fase 9 mínima, preparação)

O Corona Vendas usa o **mesmo mecanismo** do Corona Gestão ([entrega-corona-gestao.md](entrega-corona-gestao.md),
[ADR-0009](decisoes/ADR-0009-implantacao-certificado-atualizacao-e-backup.md)): instalador NSIS por usuário, certificado
do servidor fixado pela impressão digital e atualização automática que instala ao fechar. O que muda:

| | Corona Gestão | Corona Vendas |
| --- | --- | --- |
| Pasta | `apps/owner-app` | `apps/seller-app` |
| Instalador | `Corona-Gestao-Setup-<versão>.exe` | `Corona-Vendas-Setup-<versão>.exe` |
| Canal no servidor | `/atualizacoes/` | `/atualizacoes/vendas/` (pasta própria; os dois `latest.yml` nunca se misturam) |
| Gerar | `npm run owner:empacotar` | `npm run vendas:empacotar` |
| Publicar | `npm run owner:publicar` | `npm run vendas:publicar` |
| Configuração | `apps/owner-app/.env.producao` e `.env.publicar` | `apps/seller-app/.env.producao` e `.env.publicar` (modelos `.example`) |

O código comum (certificado fixado, endereço, atualização) mora no pacote **neutro** `packages/conexao-segura`, sem nada
de gestão nem de vendas; a varredura da barreira (ADR-0003) confere esse pacote também.

**Estado:** o instalador e a atualização do Corona Vendas foram provados no computador de desenvolvimento (instalar a 1.0.0,
publicar a 1.0.1, o app se atualizar sozinho ao fechar). **Ainda não há venda nem sincronização no app**: o banco local
criptografado e o bloqueio do segundo aparelho são da Fase 5; o instalador só vai a vendedores depois disso.

## Como publicar uma atualização do Corona Vendas

1. Em `apps/seller-app/package.json`, aumente `version` (sempre **maior**).
2. Código na `main`, CI verde (inclui a varredura da barreira).
3. `npm run vendas:publicar`. Gera o instalador, recusa versão igual ou menor, envia o instalador e só depois o
   `latest.yml` (em `/srv/corona/atualizacoes/vendas/`), confere o hash no servidor e guarda os 3 instaladores mais novos.
4. Os computadores dos vendedores acham a versão ao abrir (ou em até 6 horas), baixam em segundo plano e instalam quando o
   vendedor **fecha** o programa. Atenção: o vendedor pode ficar **offline** por dias; ele recebe a versão no primeiro
   contato com a internet. Por isso `APP_VERSAO_MINIMA_VENDAS` só sobe depois de a maioria já estar na versão nova.
5. Versão com defeito: publique outra, maior, com a correção (a antiga não volta).

## Riscos da primeira instalação no computador do vendedor

| Risco | O que fazer |
| --- | --- |
| **Windows**: "O Windows protegeu o computador" (instalador sem assinatura) | Esperado na primeira instalação: **Mais informações** e **Executar assim mesmo**. Explique antes ao vendedor que isso é normal. Assinar o instalador (Fase 9 completa) acaba com o aviso. |
| **Antivírus** segura ou apaga o instalador | Liberar o arquivo e a pasta `%LocalAppData%\Programs\corona-vendas`; instalador sem assinatura é mais desconfiado. |
| **Firewall / rede da loja** bloqueia a porta 443 para o IP do servidor | O app só fala HTTPS (443) com o IP do servidor. Testar a conexão no computador **antes** de entregar. |
| **Atualizar** sem internet | O app segue funcionando; a versão nova chega quando houver internet. |
| **Computador sem permissão** para instalar em `%LocalAppData%` | Raro; o instalador é por usuário, sem administrador. Se bloqueado por política da empresa, falar com quem administra o computador. |
| **Dois aparelhos** com o mesmo vendedor | O bloqueio do segundo aparelho é da Fase 5; até lá, não entregar o instalador a vendedores reais. |
| **IP do servidor mudou** | O app para de conectar e de atualizar; precisa de instalador novo (mesmo risco do Corona Gestão, [certificado/README.md](../deploy/certificado/README.md)). |
