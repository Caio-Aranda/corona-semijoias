# Corona Semijoias

Sistema de semijoias consignadas: app da dona (online), app do vendedor
(offline-first) e API única. Especificação completa: docs/ESPECIFICACAO.md
(leia antes de qualquer tarefa; se uma regra mudar, atualize o documento primeiro).

## Stack
Java 21 + Spring Boot 3 + Flyway + PostgreSQL (backend) · Electron + React +
TypeScript (dois apps separados) · SQLite criptografado no app do vendedor.

## Regras inegociáveis
- Dinheiro em NUMERIC(14,2), nunca float. Datas em UTC.
- IDs são UUID gerados no cliente. Exclusão lógica (deleted_at), nunca DELETE.
- Vendas, pagamentos e movimentos de maleta/estoque são imutáveis: correção = estorno.
- O servidor recalcula totais, parcelas e comissões; nunca confia no app.
- O app do vendedor nunca importa código da dona e nunca recebe preço de custo
  nem dados de outros vendedores.
- Permissões validadas na API, não só na tela.
- Migrações Flyway publicadas nunca são editadas: criar uma nova.
- Código de barras é texto (EAN-13, validar dígito verificador).
- Fotos de produto são arquivos (nunca binário no PostgreSQL); toda leitura ou
  pesquisa de produto mostra a foto, e a falta de foto nunca trava a venda.

## Interface
- Quem usa é uma pessoa leiga: linguagem da loja (maleta, acerto, vendedor, cliente),
  sem termos técnicos na tela. Erros em português simples dizendo o que fazer.
- Usar os termos de docs/glossario-interface.md.
- Marca: Corona Semijoias. Dourado só em detalhes e títulos grandes, nunca em texto
  pequeno. Telas de dados com texto escuro sobre fundo claro.

## Como trabalhar
- Plano antes do código nas partes críticas (sync, acerto, financeiro).
- Regras de dinheiro e estoque: escrever os testes primeiro.
- Uma funcionalidade por branch; commits no padrão Conventional Commits.
- Trabalhar a partir de uma issue do GitHub; abrir PR com "Closes #N".
- Ao terminar, informar: o que fez, o que não fez e as dúvidas.
- Nada de segredos no repositório (usar .env, com .env.example versionado).
- Pronto = funcionalidade + testes + relatório correspondente + conciliação passando.

## Comandos
(preencher conforme o projeto for criado)
