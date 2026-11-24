# Testes de ponta a ponta (Corona Gestão e Corona Vendas)

Abrem o **Corona Gestão e o Corona Vendas de verdade** (Electron), digitam e clicam, e conferem o resultado contra uma API de teste.
Pegam o que os testes de componentes não pegam: a ponte do Electron, o login, o leitor como teclado e os
arquivos exportados.

## Rodar

```bash
npm run e2e
```

Precisa do **Docker aberto**, do Java 21 e do `npm ci` feito. O comando sobe tudo sozinho e **limpa no fim**:

- um PostgreSQL **descartável** (`testes-e2e/docker-compose.yml`, projeto `corona-e2e`, porta **5499**, sem volume), que
  **nunca** é o banco de desenvolvimento nem a produção;
- a API (porta **8099**, perfil `dev`, sem buscar cotação na internet);
- o Corona Gestão compilado apontando para essa API;
- o Corona Vendas compilado apontando para a "rede" do teste (porta **8098**, `rede-do-vendas.ts`), que repassa tudo à API e
  pode ser cortada para simular a falta de internet.

Ver o relatório depois: `npx playwright show-report testes-e2e/resultados/relatorio`. O `api.log` e os traços de
quem falhou ficam em `testes-e2e/resultados/` (ignorado pelo Git).

## O que testam hoje

1. Login da dona com a senha temporária e troca obrigatória da senha.
2. Cadastro de um fornecedor.
3. Entrada de compra lendo o código (digitar + Enter, como o leitor) e conferência do estoque.
4. Exportação de um relatório em CSV e leitura do arquivo gerado.
5. O acerto inteiro da dona (`vendas-e-acerto-da-dona.test.ts`): a massa vem pela API (vendedor, maleta conferida, uma
   venda de R$ 200,00 e a cotação do dia gravada direto no banco descartável); na tela a dona começa o acerto, lê as
   peças que voltaram, confere os números do servidor (comissão 30%, falta para a meta, falta cobrada), paga parte em
   dinheiro, fecha e vê a ficha fechada. As contas à mão estão em `massa-do-acerto.ts`. Roda depois do arquivo
   `gestao.test.ts` (ordem alfabética) e também sozinho: `npm run e2e -- vendas-e-acerto`. Desde o consignado o acerto
   antigo fica escondido por padrão (ADR-0016, item 15); a massa liga `acerto_do_vendedor_visivel` no banco descartável.
6. Maleta e PDFs (`maleta-e-pdfs.test.ts`, revisão de 23/11/2026): a dona monta a maleta lendo o código, envia, baixa
   a nota em PDF (Real, Dólar e Guarani pela cotação do teste) e baixa o relatório de produtos em PDF em português e,
   trocando o idioma na hora, em espanhol.

7. Corona Vendas (`vendedor-*.test.ts`, achado B4 da revisão de 23/11/2026). Cada teste abre o programa com uma pasta de
   dados nova (um computador novo: banco local cifrado de verdade, sem segredo nenhum no teste) e a massa vem pela API da
   dona (`massa-do-vendas.ts`):
   - `vendedor-primeiro-acesso`: senha errada, senha temporária, troca obrigatória (senha fraca recusada), e a maleta e a
     cliente chegam; e o computador novo de quem já tem um ativo, que espera a dona liberar e só então recebe a maleta.
   - `vendedor-pedido-e-acerto`: Novo Pedido lendo o código (digitar + Enter) e conferido na API da dona; acerto na
     Cobrança com uma peça devolvida e pagamento parcial (fica devendo); o recebimento do que ficou devendo em Clientes,
     que fecha o pedido. As contas à mão estão no começo do arquivo.
   - `vendedor-sem-internet`: com a rede cortada o pedido é gravado no computador (o indicador avisa, nada chega à API) e
     sobe sozinho quando a internet volta.
     O Corona Vendas de hoje é só o do consignado (Novo Pedido, Cobrança, Clientes): não há mais tela de venda à vista ou a
     prazo, então o "recebimento" é o pagamento do que ficou devendo. Rodar só estes: `npm run e2e -- vendedor`. O
     `better-sqlite3-multiple-ciphers` já traz o binário que o Electron usa: não precisa recompilar, e o vitest segue igual.

Os dados são criados pelo próprio teste (conta, categoria, peça) num banco que some no fim. Os testes rodam
**um depois do outro**, na ordem do arquivo: o 1º define a senha da dona e o 2º cria o fornecedor que o 3º usa.

## Regras

- Texto da tela só por nome visível (`getByRole`, `getByLabel`), do jeito que a dona vê.
- Nunca usar dado real nem apontar para outro banco. Valores falsos ficam em `ambiente.ts`.
- Os testes do Corona Vendas rodam depois dos do Gestão (ordem alfabética), mas também sozinhos: a massa troca a senha da dona
  sozinha se ainda for a temporária.
- Não cobrem ainda: PDF do Corona Vendas, fotos, Troca, Reposição e o idioma espanhol do Vendas.
