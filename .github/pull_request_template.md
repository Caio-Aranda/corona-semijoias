## O que fiz

<!-- Resumo do que entrou neste PR. -->

## O que não fiz

<!-- O que ficou de fora e por quê. -->

## Dúvidas

<!-- Perguntas para a orquestradora ou para o responsável. Escreva "Nenhuma" se não houver. -->

## Verificações

<!-- Cole a saída dos comandos rodados. -->

- [ ] Lint, verificação de tipos e formatação passando
- [ ] Testes passando (regras de dinheiro e estoque com testes escritos primeiro)
- [ ] Relatório correspondente e conciliação passando (quando a tarefa pedir)
- [ ] `docs/PROGRESSO.md` atualizado
- [ ] Nenhum segredo no repositório (só `.env.example`)

## Regras inegociáveis

Marque as que se aplicam e foram respeitadas. Se alguma não se aplica, deixe um traço no fim da linha.

- [ ] Dinheiro em NUMERIC(14,2), nunca float. Datas em UTC.
- [ ] IDs são UUID gerados no cliente. Exclusão lógica (`deleted_at`), nunca DELETE.
- [ ] Vendas, pagamentos e movimentos de maleta/estoque são imutáveis: correção = estorno.
- [ ] O servidor recalcula totais, parcelas e comissões; nunca confia no app.
- [ ] O app do vendedor nunca importa código da dona nem recebe preço de custo ou dados de outros vendedores.
- [ ] Permissões validadas na API, não só na tela.
- [ ] Migrações Flyway publicadas nunca são editadas: criar uma nova.
- [ ] Código de barras é texto (EAN-13, com dígito verificador validado).
- [ ] Fotos de produto são arquivos (nunca binário no PostgreSQL); a falta de foto nunca trava a venda.
- [ ] Interface em linguagem da loja, com os termos de `docs/glossario-interface.md`.
