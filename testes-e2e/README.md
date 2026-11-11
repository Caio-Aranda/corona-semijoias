# Testes de ponta a ponta (Corona Gestão)

Abrem o **Corona Gestão de verdade** (Electron), digitam e clicam, e conferem o resultado contra uma API de teste.
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
- o Corona Gestão compilado apontando para essa API.

Ver o relatório depois: `npx playwright show-report testes-e2e/resultados/relatorio`. O `api.log` e os traços de
quem falhou ficam em `testes-e2e/resultados/` (ignorado pelo Git).

## O que testam hoje

1. Login da dona com a senha temporária e troca obrigatória da senha.
2. Cadastro de um fornecedor.
3. Entrada de compra lendo o código (digitar + Enter, como o leitor) e conferência do estoque.
4. Exportação de um relatório em CSV e leitura do arquivo gerado.

Os dados são criados pelo próprio teste (conta, categoria, peça) num banco que some no fim. Os testes rodam
**um depois do outro**, na ordem do arquivo: o 1º define a senha da dona e o 2º cria o fornecedor que o 3º usa.

## Regras

- Texto da tela só por nome visível (`getByRole`, `getByLabel`), do jeito que a dona vê.
- Nunca usar dado real nem apontar para outro banco. Valores falsos ficam em `ambiente.ts`.
- Corona Vendas: só depois da Fase 4.
