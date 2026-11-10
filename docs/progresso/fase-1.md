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

### Tarefa 1.3: vendedor completo (backend)

**O que foi feito**
- Migração `V011__vendedores.sql`: `vendedores` (código sequencial, nome, datas, sexo, estrangeiro, RG/IE, RG UF, CPF/CNPJ, naturalidade, escolaridade, telefones, WhatsApp, e-mail, redes, `limite_consignacao` NUMERIC(14,2) ≥ 0, `comissao_percentual` NUMERIC(5,2) de 0 a 100, restrição, praça, ativo, cadastrado por) e as filhas: endereço, contato (filiação), trabalho, cônjuge, MEI, observações (uma linha por vendedor), referências e documentos (várias, com exclusão lógica). Todas só do servidor, registradas no `RegistroDeTabelas`. Também põe a FK `clientes.vendedor_responsavel_id → vendedores`.
- Migração `V012__usuario_vendedor_com_cadastro.sql`: usuário VENDEDOR sem cadastro ganha um (nome do usuário, limite 0, comissão 0); `vendedor_id` órfão vira cadastro com o mesmo id; depois a FK `usuarios.vendedor_id` e o CHECK "VENDEDOR tem vendedor_id". Testada sobre dados antigos (`MigracaoDosVendedoresTest`).
- Rotas `/api/dona/vendedores` (só ADMIN, vendedor recebe 403): listar (busca por nome, código, CPF/CNPJ e telefones; filtro `situacao`), criar, editar com `version`, inativar/reativar, excluir (lógico); abas `GET/PUT /{id}/{endereco|contato|trabalho|conjuge|mei|observacoes}` (cada uma com a própria `version`; aba nunca gravada vem vazia com versão 0); referências (`/referencias`); documentos (`/documentos`: enviar, listar, baixar, excluir lógico).
- Pacote `arquivos`: `ArmazenamentoDeArquivos` (interface) e `ArmazenamentoEmDisco` (pasta `ARQUIVOS_PASTA`, chave gerada pelo servidor, gravação atômica, chave que sai da pasta é recusada), `TipoDeArquivo` (PDF, JPG, PNG, WebP reconhecidos pelo conteúdo) e limite de 10 MB. Pronto para a tarefa 1.6 (fotos). O banco guarda só nome, tipo, tamanho, hash e chave.
- Criar usuário VENDEDOR exige `vendedorId` de um cadastro que existe. Cliente só aceita vendedor responsável que existe. Excluir praça ou restrição em uso por vendedor dá 409 `CADASTRO_EM_USO`. O vendedor de demonstração (dev) agora cria o cadastro e liga.
- Erros novos no glossário (parte 6) e em `CodigoErro`: `ARQUIVO_TIPO_NAO_ACEITO` (415), `ARQUIVO_GRANDE_DEMAIS` (413), `CADASTRO_EM_USO` (409).

**O que ficou pendente**
- Inativar ou excluir um vendedor **não** desativa o usuário dele: ainda não há rota para desativar usuário (listar, editar e desativar usuários ficam para depois). Hoje um vendedor inativo ainda entra no Corona Vendas.
- Auditoria de mudança de limite, comissão e restrição (§11) não existe ainda no backend.
- Arquivos excluídos (logicamente) continuam na pasta; o backup diário precisa incluir `ARQUIVOS_PASTA`. Não há limpeza de arquivo órfão se a gravação do banco falhar depois do arquivo.
- Sem telas (tarefa 1.9).

**Decisões tomadas**
- Vendedores e filhas são **só do servidor** (§10: "nada de dados de outros vendedores"). Se a Fase 5 precisar do próprio vendedor no app, uma migração nova cria a tabela sincronizável.
- CPF/CNPJ do vendedor é opcional (estrangeiro) e **único** entre os não excluídos (diferente do cliente, onde é revisão).
- Código do vendedor: sequência do banco; pode haver buracos.
- Cada aba tem a própria versão (conflito 409 só na aba, não no cadastro todo).
- O tipo do arquivo vale pelo conteúdo, não pelo nome ou pelo tipo informado; download sempre como anexo, sem cache.
- A migração V011 zera `clientes.vendedor_responsavel_id` (nenhum vendedor existia; era um id sem cadastro).
- Praça/restrição inexistente ou excluída é recusada (400) com o campo.

**Dúvidas**
- Inativar o vendedor deve bloquear o acesso dele ao Corona Vendas? Hoje não bloqueia (ver pendências).
- Um vendedor pode ter mais de um usuário? Hoje o banco permite.
- A lista de opções de sexo (feminino, masculino, outro), escolaridade e residência (hoje texto livre) está certa para a dona?

### Tarefa 1.4: usuários da dona, auditoria e teste do glossário (backend)

**O que foi feito**
- Rotas novas em `/api/dona/usuarios` (só ADMIN; vendedor 403, sem token 401): `GET` (busca por nome ou usuário, filtros `perfil` e `ativo`, `pagina` a partir de 0 e `tamanho` até 100), `GET /{id}`, `PUT /{id}` (nome, perfil e vendedor, com a `version` lida), `POST /{id}/desativar` e `POST /{id}/reativar`. Criar e redefinir a senha já existiam (tarefa 10) e ganharam auditoria. A resposta nunca leva hash nem senha; a senha temporária continua saindo uma única vez, só em criar e redefinir.
- Desativar encerra, na mesma transação, todas as famílias de sessão do usuário (motivo novo `USUARIO_DESATIVADO`). O token de acesso para de valer na hora, o de renovação antigo falha e o login é recusado. Reativar não ressuscita as sessões antigas: a pessoa entra de novo com a senha dela.
- Regras da dona: não desativa a si mesma (`NAO_DESATIVAR_A_SI_MESMA`), não muda o próprio perfil (`NAO_MUDAR_O_PROPRIO_ACESSO`) e o sistema nunca fica sem administradora ativa (`ULTIMA_ADMINISTRADORA`). As mudanças que contam administradoras pegam uma trava do PostgreSQL (`pg_advisory_xact_lock`), então duas donas que se desativam ao mesmo tempo não deixam o sistema sem ninguém (teste com duas chamadas em paralelo, 6 rodadas).
- Editar: perfil `ADMIN` não leva vendedor; `VENDEDOR` exige cadastro que existe. Mudar o perfil ou o vendedor também encerra as sessões (`ACESSO_ALTERADO`), porque o token antigo leva esses dados. Mudar só o nome não encerra.
- `V013__auditoria.sql`: tabela `auditoria` (só do servidor, só inserção: ator, origem `API` ou `COMANDO`, ação, tabela, registro, antes/depois em `jsonb`, hora) e os dois motivos novos no CHECK de `sessoes` (a V007 não foi editada). `ServicoDeAuditoria` grava na mesma transação da mudança. Ações: `USUARIO_CRIADO`, `USUARIO_EDITADO`, `USUARIO_DESATIVADO`, `USUARIO_REATIVADO`, `SENHA_REDEFINIDA`. Antes/depois só levam nome, usuário, perfil, vendedor e se está ativo.
- O comando `redefinir-senha-admin` (`npm run api:redefinir-senha`) grava `SENHA_REDEFINIDA` com origem `COMANDO` e ator vazio ("pelo servidor"). Comando recusado não grava nada.
- `GlossarioDasMensagensTest`: lê os blocos `PROIBIDAS-A` e `PROIBIDAS-B` do glossário e confere todos os textos de `CodigoErro`, as frases montadas por `ErroDaApi`, as constantes de `Mensagens` e os textos escritos no código (`message = "..."`, `recusar(...)`, `campoInvalido(...)`). Falha listando a palavra, a mensagem e a origem. Um segundo teste reprova qualquer anotação de conferência de campo sem `message` (o validador devolveria o texto padrão dele, que pode sair em inglês); quatro campos de login, senha, troca de senha e renovação estavam assim e ganharam mensagem.
- Glossário (parte 6): os três erros novos, escritos antes do código. Clientes da API regerados.

**O que ficou pendente**
- Telas de usuários (tarefa 1.8). Tela para ler a auditoria: não existe ainda, só a tabela.
- Auditoria de limite, comissão e restrição do vendedor e das demais ações da §11 (preço, estoque, desconto...) entram com as funcionalidades que as criam; o mecanismo (`ServicoDeAuditoria`) já está pronto para elas.
- Inativar ou excluir vendedor ainda não desativa o usuário dele (pendência da 1.3; ver dúvidas).

**Decisões tomadas**
- Desativar ou reativar quem já está nessa situação responde 200 sem mudar nada e sem registrar na auditoria.
- O login do usuário não muda pela edição (é a identidade nos registros de acesso).
- Redefinir a senha de usuário desativado continua permitido (tarefa 10).
- A auditoria guarda o ator por chave estrangeira para `usuarios`; origem `COMANDO` exige ator vazio e `API` exige ator (CHECK).

**Dúvidas**
- Inativar o vendedor (cadastro) deveria desativar o usuário dele? Hoje não, e a dona desativa os dois separadamente.
- Quem lê a auditoria e por quanto tempo ela fica guardada (retenção)?

### Tarefa 1.5: produtos, custos e códigos (backend)

**O que foi feito**
- Migração `V014__produtos.sql`: `produtos` (sincronizável, **sem custo**: nome, referência, categoria, fornecedor, `preco_venda` NUMERIC(14,2) ≥ 0, `estoque_minimo` ≥ 0, `estoque_atual`, ativo, `foto_principal_hash` reservado para a 1.6), `produtos_custos` (só do servidor, 1 para 1, `preco_custo` NUMERIC(14,2), nunca sincronizada) e `produto_codigos` (sincronizável: código texto, tipo `LOJA`/`FORNECEDOR`/`EAN13`, fornecedor opcional, principal, ativo), mais a sequência `codigo_loja_seq`. Registradas no `RegistroDeTabelas` (o teste de convenções passa).
- Código (ADR-0007): índice único parcial em `lower(codigo)` entre os não excluídos, CHECK de que não tem espaço nas pontas e índice parcial de **um principal por produto**. Dígito verificador só para `EAN13` com 13 dígitos (`Ean13`); zeros à esquerda ficam. Código repetido → 409 `CODIGO_JA_USADO` dizendo o nome do produto que já o usa; se dois pedidos chegam juntos, o banco deixa passar só um (teste com 6 pedidos em paralelo).
- Rotas `/api/dona/produtos` (só ADMIN; vendedor 403, sem token 401): `GET` (busca por nome, referência ou parte de qualquer código; filtros `categoriaId`, `fornecedorId`, `ativo`; página), `GET /{id}`, `GET /por-codigo?codigo=` (código exato, sem espaços nas pontas, sem diferenciar maiúsculas; 404 se não houver), `POST` (produto + custo + códigos na mesma transação), `PUT /{id}` (com `version`), `PUT /{id}/codigos` (a lista enviada vira a lista do produto; a `version` do produto sobe e a linha fica travada), `POST /{id}/inativar` e `/reativar`, `DELETE /{id}` (lógico: os códigos ficam livres) e `POST /codigos/proximo` (próximo número da loja pela sequência do banco, pulando números já usados; teste com 24 chamadas em paralelo sem repetição).
- O `estoque_atual` nunca é aceito de entrada (a coluna é só leitura na entidade); fica 0 até as movimentações da Fase 2.
- Preço: recusa negativo e mais de duas casas (nada é arredondado em silêncio).
- Auditoria (`ServicoDeAuditoria`, mesma transação): `PRODUTO_CRIADO`, `PRECO_VENDA_ALTERADO` e `PRECO_CUSTO_ALTERADO` com valores antes e depois. O custo só é lido pelas rotas da dona.
- Categoria ou fornecedor em uso por um produto não pode ser excluído: 409 `CADASTRO_EM_USO_POR_PRODUTOS` (pendência da 1.2).
- Testes: `ProdutosDaDonaTest` (permissão, custo fora do contrato do vendedor e fora das tabelas sincronizadas, criar/editar/auditoria, busca, códigos, concorrência) e `Ean13Test`.

**O que ficou pendente**
- Fotos (tarefa 1.6): só existe o campo reservado `foto_principal_hash`. Importação em massa e telas (1.11).
- Preço sugerido (custo x 7) **não** é calculado (pendente com a dona, ADR-0007).
- O filtro "só os produtos da maleta do vendedor" e a rota de leitura do vendedor entram nas Fases 4 e 5.

**Decisões tomadas**
- `fornecedor_id` **não** vai nas colunas sincronizadas de `produtos` nem de `produto_codigos` (o vendedor não recebe nada de fornecedor, §10); a coluna existe no banco. Dá para incluir depois sem risco.
- Produto novo exige **pelo menos um código**; sem nenhum marcado como principal, o primeiro ativo vira principal; dois principais é erro; o principal não pode estar desativado.
- O formato do código da loja é **só o número sequencial** (1, 2, 3...), a confirmar com a dona (ADR-0007, item 5).
- Mudar só o custo não sobe a `version` do produto (o custo fica em tabela à parte, só do servidor).

**Dúvidas**
- O código da loja pode ser só número, ou ela quer prefixo/letras?
- A dona quer reaproveitar um código de produto excluído? Hoje fica livre.

### Tarefa 1.6: fotos de produto (backend)

**O que foi feito**
- Migração `V015__produtos_fotos.sql`: `produtos_fotos` (sincronizável: produto, `arquivo_hash` SHA-256, principal, ordem, largura, altura, tamanho, e as chaves dos três arquivos). Nunca binário no banco (teste confere que não há coluna `bytea`). Índices: uma principal por produto e o mesmo hash só uma vez por produto (entre as não excluídas). No sync entram só `produto_id`, `arquivo_hash`, `principal` e `ordem`; as chaves e as medidas ficam no servidor. `produtos.foto_principal_hash` (reservado na V014) agora é mantido pela API.
- Envio `POST /api/dona/produtos/{id}/fotos` (multipart, campo `arquivo`; só ADMIN): o tipo vale pelo **conteúdo** (JPEG, PNG ou WebP; PDF e programa renomeado são recusados), até 10 MB e **25 milhões de pontos** (`corona.fotos.maximo-de-pontos`). O tamanho em pontos é lido do cabeçalho **antes** de abrir a imagem (imagem-bomba: um PNG de 100 000 x 100 000 em poucos bytes é recusado com 413). A foto é sempre recodificada: gira pelo EXIF, descarta EXIF/GPS/comentários, fundo transparente vira branco, gera miniatura (150), média (600) e grande (1600) em JPEG, sem aumentar foto pequena. O nome do arquivo é a chave gerada pelo servidor; o nome enviado nunca é usado. O hash é o SHA-256 do tamanho grande já recodificado.
- Regras: até 5 fotos por peça (409 `PRODUTO_COM_FOTOS_DEMAIS`), a primeira vira principal, a mesma foto na mesma peça não duplica (200 com a que já existe), em outra peça reaproveita os arquivos. A linha do produto é travada (`FOR UPDATE`) em toda mudança: 8 envios em paralelo terminam com exatamente 5 fotos e 1 principal (teste). Rotas: listar, principal (`POST .../{fotoId}/principal`), ordem (`PUT .../fotos/ordem` com todos os ids), excluir (lógico; se era a principal, a próxima vira principal).
- Download `GET /api/dona/produtos/fotos/{hash}/{miniatura|media|grande}`: só autenticado, `Cache-Control: private, max-age=1 ano, immutable`, `ETag` pelo hash+tamanho (304 em `If-None-Match`), `X-Content-Type-Options: nosniff`, CSP `sandbox`. Foto excluída ou hash desconhecido: 404.
- Importação em massa (`/api/dona/produtos/fotos/importacao`): **prévia** recebe só os nomes (JSON, nada é enviado nem gravado) e devolve associadas, sem peça, nomes ambíguos e quantas peças ficarão sem foto (com as 200 primeiras); **confirmar** recebe até 10 fotos por vez (multipart `arquivos`), grava cada uma sozinha e devolve, por arquivo, `GRAVADA`, `JA_EXISTIA`, `SEM_PRODUTO`, `AMBIGUA` ou `RECUSADA` com a mensagem em português. Uma foto ruim não derruba as outras; repetir o lote não duplica. O nome sem a extensão (.jpg, .jpeg, .png, .webp) é o código: sem pasta, sem espaços nas pontas, sem diferenciar maiúsculas (ADR-0007).
- Relatório `GET /api/dona/produtos/sem-foto` (busca, `categoriaId`, `fornecedorId`, `ativo` — por padrão só as ativas —, página). Tela na 1.12.
- Toda leitura de produto da dona (lista, `/{id}`, `/por-codigo`) devolve `fotoPrincipal` com o hash e o endereço dos três tamanhos, ou `null`. A falta de foto nunca é erro.
- Erros novos no glossário (parte 6) e em `CodigoErro`: `FOTO_TIPO_NAO_ACEITO` (415), `FOTO_NAO_LIDA` (400), `FOTO_GRANDE_DEMAIS_EM_PONTOS` (413), `PRODUTO_COM_FOTOS_DEMAIS` (409). O limite de pedido multipart subiu para 110 MB (a importação manda até 10 fotos de 10 MB). WebP é lido pela biblioteca TwelveMonkeys `imageio-webp` (só leitura; a saída é sempre JPEG). Clientes da API regerados.
- Testes: `ProcessadorDeImagemTest` (tamanhos, EXIF/rotação, WebP, transparência, danificada, imagem-bomba) e `FotosDosProdutosTest` (permissão, envio, conteúdo x nome, arquivos fora do banco, 5 fotos, paralelo, principal/ordem/exclusão, download e cache, relatório, prévia e confirmar).

**O que ficou pendente**
- Telas (1.11 cadastro/importação, 1.12 relatório). Webcam, colar e arrastar são da tela.
- A rota de download das fotos para o **vendedor** e o filtro por maleta entram na Fase 5; hoje só a dona baixa. O sync já leva os metadados (`produtos_fotos`) e `foto_principal_hash`.
- Arquivos de fotos excluídas ficam na pasta (backup diário precisa incluir `ARQUIVOS_PASTA`); não há limpeza de arquivo órfão se o banco falhar depois de gravar o arquivo.
- Limites do Tomcat para muitas partes no mesmo pedido (`server.tomcat.max-part-count=20`) não são exercitados pelo MockMvc; conferir com a importação real na 1.11.
- Foto em WebP animado ou CMYK: lida como uma foto só quando o leitor consegue; senão, "Não foi possível abrir esta foto".

**Decisões tomadas**
- A prévia manda só nomes; erros de conteúdo (arquivo danificado, PDF com nome de foto) aparecem no confirmar, por arquivo.
- Dois arquivos para a mesma peça no mesmo lote (inclusive códigos diferentes da mesma peça) são "ambíguos": nenhum é gravado. Entre lotes diferentes o servidor não vê a repetição e a segunda vira mais uma foto da peça.
- Na importação a foto nova **entra como mais uma foto** da peça (a primeira vira principal); com 5 fotos ela é recusada com a mensagem de limite. Não troca a principal.
- Mexer na foto principal sobe a `version` do produto (precisa, para o sync do vendedor ver o hash novo); uma tela de edição aberta antes recebe o aviso de "alterado por outra pessoa" ao gravar, então a tela deve reler o produto depois de mexer nas fotos.
- O hash é do conteúdo servido; uma atualização do Java que mude a codificação do JPEG pode gerar hash novo para a mesma foto original (a foto entraria como nova, nunca perdida).

**Dúvidas**
- Importando uma foto para uma peça que já tem fotos: adicionar (como está) ou trocar a principal?
- 25 milhões de pontos bastam? Celulares de 48 ou 108 megapixels passam do limite e a pessoa precisa reduzir.
- No Corona Vendas a foto grande será baixada sob demanda: a rota para o vendedor entra com a Fase 5 (escolher entre token no cabeçalho ou URL assinada).

