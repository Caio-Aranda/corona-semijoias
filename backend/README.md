# Backend (API Corona Semijoias)

API Spring Boot 4.1.2 (Java 21, Maven wrapper 3.3.4 com Maven 3.9.16, Flyway, PostgreSQL).
Como rodar e testar: seção "API" do [README da raiz](../README.md).

## Organização

Pacote base `br.com.coronasemijoias.api`, organizado **por funcionalidade**, não por camada:

- `comum/erros`: tratador global de erros (respostas em português com campo `codigo`);
- `comum/json`: configuração do JSON (UTC; dinheiro como texto decimal);
- `comum/configuracao`: mensagem em português quando falta o `.env`;
- `comum/dinheiro`: `Dinheiro`, o único utilitário de dinheiro (HALF_UP, escala 2; ADR-0002);
- `comum/identificadores`: `UuidV7` (gera UUID v7) e `Identificadores` (aceita só v4 e v7);
- `comum/persistencia`: superclasses JPA (`EntidadeSincronizavel`, `EntidadeDoServidor`),
  `OrigemDoDispositivo` e o dialeto do PostgreSQL;
- `sync`: `RegistroDeTabelas` (lista única das tabelas) e o serviço de pull com limite seguro
  (`ServicoDePull`, sem rota; a rota `/sync/pull` é da Fase 5);
- `documentacao`: grupos `dona` e `vendedor` do OpenAPI, com o esquema `bearer`;
- `seguranca`: token de acesso (JWT HS256 com `kid`, duas chaves; `EmissorDeToken`, conferência em
  `ConfiguracaoDeSeguranca`), usuário da requisição conferido no banco a cada chamada
  (`ConversorDoToken`, `UsuarioAutenticado`), `@ExigePerfil`, lista pública fechada (`RotasPublicas`) e
  as respostas 401/403;
- `usuarios`: `Usuario`, conferência de senha com BCrypt (`ConferenciaDeSenha`), regras da senha
  (`RegrasDaSenha`), senha temporária (`GeradorDeSenhaTemporaria`), criação de usuários e redefinição
  (`ServicoDeUsuarios`, `UsuariosDaDonaController`), o primeiro administrador criado ao subir
  (`PrimeiroAdministrador`) e o comando do servidor `redefinir-senha-admin`
  (`ComandoRedefinirSenhaDeAdministrador`, passo a passo em `docs/redefinir-senha-da-dona.md`);
- `autenticacao`: `POST /api/auth/login`, `POST /api/auth/trocar-senha`, `POST /api/auth/renovar`,
  `POST /api/auth/sair` e `GET /api/auth/eu`;
- `sessoes`: a sessão longa (ADR-0004, item 2): famílias (`sessoes`), tokens de renovação com rotação e
  janela de tolerância (`ServicoDeSessoes`, `SegredoDeRenovacao`). Não depende de `usuarios` nem de
  `seguranca`;
- `acessos`: tentativas de login e bloqueio (`ControleDeTentativas`), a vez de cada IP (`TravaPorIp`), o
  IP real atrás do proxy (`IpDoCliente`) e o alerta de muitos erros num login (`AlertaDeErrosPorLogin`);
- `configuracoes`: leitura da tabela `configuracoes` (§9) por chave (`Configuracoes`, `ChaveDeConfiguracao`).

Cada funcionalidade nova ganha o próprio pacote (por exemplo `produtos`) e, dentro dele, os
subpacotes `dona` e `vendedor` para controladores e DTOs. Regra do ADR-0003: o subpacote
`vendedor` nunca depende do `dona` (nem o contrário); o que for comum fica no pacote da funcionalidade.

Migrações Flyway ficam em `src/main/resources/db/migration` e, depois de publicadas, nunca são editadas.

## Senha temporária e token restrito

Quem tem a marca `troca_de_senha_obrigatoria` (usuário novo, senha redefinida, primeiro administrador)
só recebe do login o **token restrito** (`escopo=TROCA_SENHA`). O `FiltroDoTokenRestrito` nega com 403
`TROCA_DE_SENHA_OBRIGATORIA` toda requisição que não esteja em `ROTAS_PERMITIDAS` (a troca de senha
e a saída): uma rota nova nasce barrada, sem ninguém ter de lembrar. Para o token restrito abrir mais
uma rota, acrescente-a a essa lista.

## Sessões e revogação

Todo token de acesso leva o `sid` da família da sessão (tabela `sessoes`), inclusive o restrito, que
tem uma família própria (`TROCA_SENHA`, sem token de renovação). A cada requisição o `ConversorDoToken`
lê a família pela chave primária: revogada, de outro usuário, de outro aparelho ou do tipo errado dá 401
`SESSAO_TERMINADA`. A saída revoga a família; redefinir ou trocar a senha revoga todas as do usuário, e
por isso nenhum token anterior vale depois, nem o do mesmo segundo. A comparação do `iat` com
`usuarios.credencial_alterada_em` continua como segunda barreira.

Nos testes: `RelogioDeTeste` avança ou para o tempo (`avancar`, `fixar`; reinicie-o no `@AfterEach`);
cada pedido do MockMvc sai de um IP aleatório (`IpsDeTeste`), e quem testa o limite de tentativas fixa o
IP com `IpsDeTeste.de(ip)`; o login de um vendedor de teste manda o aparelho
`UsuariosDeTeste.APARELHO` (`usuarios.corpoDoLogin(login, senha)`).

## Como proteger uma rota

Toda rota declara quem pode usá-la com `@ExigePerfil` (ADR-0004, item 9), no controlador ou no método.
**Rota sem a anotação é negada** (403), e o `TodasAsRotasTest` reprova o build. Só a lista fechada de
`RotasPublicas` responde sem token (login, renovação, saúde e, no perfil `dev`, a documentação).

```java
@ExigePerfil("ADMIN")
@RestController
@RequestMapping("/api/dona/produtos")
class ProdutosDaDonaController {

    @GetMapping
    List<ProdutoDaDona> listar(@AuthenticationPrincipal UsuarioAutenticado usuario) { ... }
}
```

- O grupo do prefixo também vale: `/api/dona/**` só aceita os perfis de `corona.seguranca.grupos.dona`
  e `/api/vendedor/**` os de `corona.seguranca.grupos.vendedor` (`application.properties`). A anotação
  do método vale sobre a do controlador e só pode restringir.
- O perfil é conferido antes de ler o corpo: o perfil errado recebe 403, nunca um 400.
- Filtro "só do vendedor": sempre pelo `usuario.vendedorId()` do token, nunca por parâmetro (ADR-0003).
- Perfil novo (por exemplo, um gerente): uma linha numa migração nova em `perfis`, a anotação nas rotas
  que ele usa e, se ele entrar num grupo, o valor em `corona.seguranca.grupos.*`. Nenhuma classe muda.

## Como criar uma tabela

As convenções são do ADR-0002. Uma tabela nova precisa de três coisas, na mesma tarefa:

**1. A migração**, com `id uuid PRIMARY KEY` e **sem** as colunas de controle (a função cria todas):

```sql
CREATE TABLE corona.clientes (
    id uuid PRIMARY KEY,
    nome text NOT NULL,
    limite numeric(14,2) NOT NULL DEFAULT 0
);
SELECT corona.aplicar_convencoes_sincronizavel('corona.clientes');
```

- **Sincronizável** (nasce no Corona Vendas ou chega a ele): `corona.aplicar_convencoes_sincronizavel(tabela)`,
  ou `(tabela, imutavel => true)` para vendas, itens, pagamentos e movimentos. Cria `created_at`,
  `updated_at`, `version`, `deleted_at`, `origem_dispositivo`, `sync_seq`, `sync_xid`, o `CHECK` de
  UUID v4 ou v7, o gatilho de controle, as recusas de DELETE e TRUNCATE (e de UPDATE, se imutável) e o
  índice `(sync_xid, sync_seq)` do pull. A tabela precisa estar vazia.
- **Só do servidor**: `corona.aplicar_convencoes_do_servidor(tabela, editavel => true)` (cria `created_at`,
  `updated_at`, `version` e o gatilho de controle) ou `editavel => false` para histórico (só inserção,
  recusa UPDATE). `deleted_at`, quando a exclusão fizer sentido, é declarada na tabela.
- Dinheiro em `numeric(14,2)`, porcentagem em `numeric(5,2)` com `CHECK` de 0 a 100, datas em
  `timestamptz`. Nada de `real`, `double precision`, `money` nem `timestamp` sem fuso.
- Os gatilhos são os únicos que escrevem `version`, `updated_at`, `sync_seq` e `sync_xid`; o que vier
  do cliente nesses campos é ignorado. `origem_dispositivo` vem da sessão
  (`OrigemDoDispositivo.definirNaTransacaoAtual`), nunca do corpo.

**2. O registro** em `sync/RegistroDeTabelas.daAplicacao()`: `TabelaSincronizavel` com a lista
explícita das colunas que o pull entrega (nunca `SELECT *`; custo nunca), ou `TabelaDoServidor`.

**3. A entidade**, estendendo `EntidadeSincronizavel` ou `EntidadeDoServidor`. Os campos do gatilho são
só leitura. A edição otimista usa o `version` lido: gravar com versão velha dá `OptimisticLockException`.

O `ConvencoesDoSchemaTest` reprova o build se faltar qualquer peça (tipo proibido, tabela fora do
registro, gatilho, índice, privilégio de DELETE). Valores em dinheiro são calculados só com
`Dinheiro` (o único lugar com arredondamento).

Tabelas que só existem nos testes ficam em `src/test/resources/db/migration-teste`, com versão 9000 ou
mais, e nunca entram no jar.

## Testes do sync

Os testes de pull rodam no PostgreSQL de verdade: `PullConcorrenteTest` (duas conexões controladas
passo a passo), `RestauracaoDoSyncTest` (`pg_dump` restaurado num segundo PostgreSQL; roteiro em
`docs/restauracao-de-backup.md`) e `EstresseDoPullTest` (8 escritores e um leitor, 20 s em todo
`./mvnw verify`). A versão longa (10 minutos) roda toda segunda pelo workflow "Estresse do sync" e
pode ser disparada à mão na aba Actions. Para rodar no seu computador:

```bash
./mvnw test -Dtest=EstresseDoPullTest -Dcorona.estresse.duracao=PT10M
```

A semente aparece no log (`Estresse do pull: semente=...`); para repetir as escolhas de uma execução,
passe `-Dcorona.estresse.semente=<a semente>`.
