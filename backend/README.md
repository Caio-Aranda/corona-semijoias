# Backend (API Corona Semijoias)

API Spring Boot 4.1.2 (Java 21, Maven wrapper 3.3.4 com Maven 3.9.16, Flyway, PostgreSQL).
Como rodar e testar: seção "API" do [README da raiz](../README.md).

## Organização

Pacote base `br.com.coronasemijoias.api`, organizado **por funcionalidade**, não por camada:

- `comum/erros`: tratador global de erros (respostas em português com campo `codigo`);
- `comum/json`: configuração do JSON (UTC; dinheiro como texto decimal);
- `comum/configuracao`: mensagem em português quando falta o `.env`;
- `documentacao`: grupos `dona` e `vendedor` do OpenAPI.

Cada funcionalidade nova ganha o próprio pacote (por exemplo `produtos`) e, dentro dele, os
subpacotes `dona` e `vendedor` para controladores e DTOs. Regra do ADR-0003: o subpacote
`vendedor` nunca depende do `dona` (nem o contrário); o que for comum fica no pacote da funcionalidade.

Migrações Flyway ficam em `src/main/resources/db/migration` e, depois de publicadas, nunca são editadas.
