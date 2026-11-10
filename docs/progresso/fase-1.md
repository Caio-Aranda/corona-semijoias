# Detalhe das tarefas da Fase 1

Uma seção por tarefa concluída, no mesmo formato de [fase-0.md](fase-0.md). A tabela de acompanhamento está em [PROGRESSO.md](../PROGRESSO.md).

### Tarefa 1.2: cadastros básicos (backend)

**O que foi feito**
- Migração `V010__cadastros_basicos.sql` com seis tabelas, todas com exclusão lógica (`deleted_at`) e unicidade por índice parcial (só vale para o que não foi excluído). Sincronizáveis: `categorias`, `semanas`, `clientes`. Só do servidor: `fornecedores`, `pracas`, `restricoes`. Todas registradas no `RegistroDeTabelas`; o teste de convenções passa.
- `semanas`: número único, `fim >= inicio`, situação `ABERTA`/`FECHADA` e restrição `EXCLUDE` (intervalo fechado nas duas pontas) que impede datas sobrepostas, inclusive em dois pedidos ao mesmo tempo.
- Pacote `cadastros`: entidades, repositórios com busca paginada, um serviço por cadastro sobre a base `ServicoDeCadastro` (página, edição com a versão lida, exclusão lógica, tradução dos erros do banco) e seis controladores em `/api/dona/{fornecedores,categorias,pracas,semanas,restricoes,clientes}` com `@ExigePerfil("ADMIN")` (vendedor recebe 403, sem token 401). Rotas: `GET` (lista com `busca`, `pagina` a partir de 0 e `tamanho` até 100), `GET /{id}`, `POST`, `PUT /{id}` e `DELETE /{id}` (204, exclusão lógica).
- `Documentos`: CPF e CNPJ guardados só com os caracteres do número, com dígitos verificadores conferidos no servidor. O CNPJ aceita o formato alfanumérico (julho de 2026); os numéricos antigos continuam valendo.
- Erros novos no glossário (parte 6) e em `CodigoErro`: `CADASTRO_NAO_ENCONTRADO` (404), `CADASTRO_JA_EXISTE` (409), `CADASTRO_ALTERADO` (409) e `SEMANA_SOBREPOSTA` (409). CPF e CNPJ errados e semana que termina antes de começar voltam como `DADOS_INVALIDOS` (400) com o campo.
- Clientes da API regerados (`npm run api:clientes`); só o da dona mudou.

**O que ficou pendente**
- `clientes.vendedor_responsavel_id` sem chave estrangeira (a tabela de vendedores nasce na tarefa 1.3, que acrescenta a FK). Não se confere ainda se o id existe.
- Auditoria: ainda não existe mecanismo de auditoria no backend (só a lista da §11: preço, estoque, desconto, cancelamento etc., que não incluem estes cadastros); nada foi criado.
- Excluir praça ou restrição em uso por um vendedor (tarefa 1.3) e categoria ou fornecedor em uso por um produto (tarefa 1.5) ainda não é bloqueado, porque esses vínculos não existem.
- Sem telas (tarefa 1.9).

**Decisões tomadas**
- Fornecedores, praças e restrições são **só do servidor**: a §10 diz que o vendedor não sincroniza fornecedores, e praça e restrição são dados do cadastro do vendedor que ele não precisa. Categorias, semanas e clientes são sincronizáveis (o produto leva a categoria; a venda leva a semana; os clientes são do vendedor). Se a Fase 5 precisar de outra tabela no pull, uma migração nova converte.
- **CPF de cliente não é único.** O vendedor cadastra offline e a §10 manda o CPF repetido para revisão da dona, não para rejeição. O CNPJ de fornecedor é único.
- O `id` de categoria, semana e cliente pode vir do aplicativo (UUID v4 ou v7); sem `id`, o servidor gera um v7. Um `id` que já existe, mesmo excluído, é recusado (409).
- Editar exige a `version` que a tela leu. Se outra pessoa gravou antes, 409 `CADASTRO_ALTERADO`, em vez de apagar a alteração dela.
- Endereço é opcional e dividido em logradouro, número, complemento, bairro, cidade, UF, CEP; a UF é texto livre (a loja atende na fronteira).
- A busca ignora maiúsculas (não ignora acentos) e, em CPF e CNPJ, aceita o número com ou sem pontuação a partir de 3 caracteres.
- Telefone e WhatsApp são guardados como a dona digitou (sem máscara obrigatória), por causa dos números do Paraguai.

**Dúvidas**
- Confirmar a divisão "só do servidor" x "sincronizável" acima, em especial **semanas** (o vendedor precisa saber a semana?) e **praças/restrições**.
- A semana pode ser editada (datas e situação) mesmo depois de FECHADA? Hoje pode; a regra de "semana fechada não aceita alterações" é das vendas e do acerto, e entra nas tarefas daquelas fases.
