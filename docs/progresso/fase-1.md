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

**Mudança de 11/11/2026 (decisão do responsável): uma foto só por peça.** Enviar uma foto nova substitui a anterior (exclusão lógica da antiga, e o arquivo sai do disco por limpeza diária depois de 7 dias); a importação em massa também substitui. Acabam o limite de 5 fotos (`PRODUTO_COM_FOTOS_DEMAIS` deixou de existir), a foto principal e a ordem (rotas `principal` e `ordem` removidas; as colunas ficam, sem uso). Migração `V029` (nunca se edita a V015): índice único parcial de uma foto ativa por produto. O que o vendedor e a Fase 5 leem (`foto_principal_hash` e `produtos_fotos.arquivo_hash`) continua igual. ESPECIFICACAO §20 e glossário atualizados.

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


### Tarefa 1.7: rota da cotação do dia e dos avisos (backend)

**O que foi feito**
- `GET /api/dona/cotacoes/hoje`: a cotação em vigor hoje (data de Brasília, `CotacaoVigente`): data, Dólar e Guarani (taxa de venda, reais por unidade, texto de 8 casas), fonte, `deDiaAnterior` (fim de semana, feriado ou busca que falhou) e a `linha` pronta ("Cotação do Banco Central de 06/11/2026"). Sem nenhuma cotação: **200** com `disponivel = false` e a mensagem em português; nunca erro.
- `POST /api/dona/cotacoes/converter`: `{ "valorEmReais": "100.00", "data": "2026-11-06" }` (data opcional, hoje se vazia). Responde Dólar com 2 casas e Guarani sem casas, como texto, pela `ConversaoDeMoeda` (nenhum arredondamento novo). Aceita negativo (estorno) e o máximo de `NUMERIC(14,2)`; número JSON, vírgula ou mais de 2 casas são recusados com 400 (mesmo desserializador do dinheiro). Sem cotação para a data: 200 com `disponivel = false`.
- `GET /api/dona/avisos`: lista de "O que precisa da sua atenção", `{ avisos: [{ tipo, titulo, texto, dias }] }`. Extensível: cada tipo novo é um `@Component` que implementa `FonteDeAvisos` (pacote `avisos`). O primeiro é o `COTACAO_NAO_CHEGOU`, montado a partir do `AvisoDeCotacao` com os textos do glossário (hoje / um dia / vários dias / sem nenhuma cotação). Calculado na hora, nunca gravado: some quando a busca recupera o dia.
- Só ADMIN (vendedor 403, sem token 401). Sem migração. Clientes regerados (`api-client-dona`; o do vendedor não mudou).
- Testes (`CotacoesDaDonaTest`, 17): permissões, indisponível, dia de Brasília, sábado/segunda, conversões com os exemplos do ADR-0006, negativo, valor máximo, valores inválidos, e cada forma do aviso (hoje, ontem, vários dias, fim de semana, nenhum, some ao gravar). Relógio parado; banco desfeito no fim de cada teste.

**O que ficou pendente**
- A tela (1.8) e a conversão em TypeScript com `BigInt` na tela. Nenhuma rota do vendedor (Fase 4).

**Decisões tomadas**
- A fonte aparece como "Banco Central do Brasil"; o endereço guardado em `cotacoes.fonte` é técnico e não sai da API.
- A conversão pela data usa a regra de lançamento (última cotação até aquela data); sem `data`, a de hoje. A mais recente "para o que não é lançamento" fica para quando houver telas que a peçam.
- O aviso reúne todos os dias sem cotação num só item (um aviso, não um por dia).
- O Guarani sai em reais por 1 Guarani (0.00089500); mostrar "por mil" é da tela (glossário, parte 8).
- Corrigidas no PROGRESSO as linhas 1.5 e 1.6 ("Em revisão" para "Concluída", PR 31 e 32).

**Dúvidas**
- Nenhuma.


### Tarefa 1.8: base do Corona Gestão, usuários, cotação e sessão sem internet (owner-app)

**O que foi feito**
- **Base de navegação:** menu lateral (Início · Cadastros · Pessoas), rota pelo endereço da janela (`#/pessoas/usuarios`, voltar e avançar funcionam) e cabeçalho com Sair; itens ainda não feitos mostram "Em breve". O glossário ganhou a seção "Corona Gestão: menu, listas e usuários" antes do código.
- **Componentes reutilizáveis** (as telas 1.9 a 1.11 usam): em `packages/ui`, `MenuLateral`, `Paginacao`, `EstadoDaLista` (carregando e erro) e `JanelaDeConfirmacao` (foco em Voltar, Esc cancela, botão principal com o nome da ação); no app, `CampoDeDinheiro` (digita em reais com vírgula e mostra Real, Dólar e Guarani), `AvisoDeErro` (texto do servidor; "alterado por outra pessoa" com **Atualizar**; sem internet com **Tentar de novo**), `useBusca` (busca, descarta resposta velha e tenta de novo sozinho se a internet cair) e `chamar`/`traduzirErro`.
- **Ponte:** `chamarApi` agora leva dados (`parametros`). Cada operação é de uma lista fixa e os dados são conferidos de novo no processo principal (`validacao-parametros.ts`: identificadores, perfis, limites, valor em reais como texto). A tela continua sem token. O erro devolve `status`, `codigo` e `campos` com o texto do servidor.
- **Usuários (Pessoas → Usuários):** lista (busca, tipo de acesso, situação, páginas), criar, editar (versão lida; usuário não muda), desativar e reativar (com confirmação que explica "sai do sistema agora"), nova senha temporária. A senha temporária aparece uma única vez, com **Copiar senha**, só em estado da tela: some ao fechar e ao sair da tela, não vai para `localStorage`, `sessionStorage` nem registro (testado). Regras do servidor (não desativar a si mesma, última administradora, usuário repetido) aparecem com o texto dele; usuário repetido ou fora do formato aparece no campo.
- **Cotação:** `moedas.ts` converte só com `BigInt` (HALF_UP, ADR-0006 itens 6 e 9), testado com os exemplos do ADR (inclusive o valor máximo de `NUMERIC(14,2)` e negativos). A cotação do dia vem de `/api/dona/cotacoes/hoje` uma vez, vale para todas as telas, e a linha "Cotação do Banco Central de dd/mm/aaaa." fica no rodapé (com "a última publicada" quando é de dia anterior; "Ainda não há cotação..." quando não há).
- **Tela inicial:** o bloco "O que precisa da sua atenção" lê `/api/dona/avisos`; lista vazia mostra "Tudo em dia.".
- **Sessão sem internet:** a guarda só apaga a sessão por recusa do servidor (400/401/403/404 na renovação). Falha de rede ou servidor fora (5xx, 429) mantém a sessão; ela não gasta uma das duas apresentações do token (`apresentacoes` volta a no máximo 1), então várias quedas seguidas não derrubam mais. A tela de espera mostra "Sem conexão com a internet. Tente de novo." e tenta sozinha a cada 15 segundos; dentro das telas, a lista mostra o mesmo texto e repete sozinha. Continua uma renovação por vez (fila única) e o limite de apresentações.

**O que ficou pendente**
- As telas 1.9 a 1.11 (itens do menu em "Em breve"). Rota `/api/dona/cotacoes/converter` já está na ponte (`cotacao.converter`), mas nenhuma tela a usa ainda: a conversão exibida é local com `BigInt`.
- Texto de "Em breve" ainda é por item; sai quando cada tela existir.
- Rodar o aplicativo Electron de ponta a ponta (login real, lista de usuários contra a API) não foi feito aqui; a cobertura é de testes de componente e de lógica.

**Decisões tomadas**
- Sem biblioteca de rotas: um `useRota` de 30 linhas sobre o endereço da janela. Troca de item reinicia a tela (a chave é a rota), o que também apaga qualquer senha temporária.
- Conversão local em `BigInt` (e não a rota `converter`): é instantânea no campo de dinheiro e funciona com a cotação já carregada. A rota segue disponível para valores de datas passadas.
- Falha de rede na renovação não conta como apresentação do token. Risco assumido: se a primeira tentativa chegou ao servidor e a resposta se perdeu, e a segunda também, uma terceira seria a "terceira apresentação" e o servidor revogaria a sessão; aí a sessão acaba por recusa do servidor (caso seguro) e a pessoa entra de novo.
- A tela não conhece o nome dos erros do servidor: decide só por `status`, `codigo` conhecido (`CADASTRO_ALTERADO`, `LOGIN_JA_USADO`, `LOGIN_FORA_DO_FORMATO`) e `campos`; o texto sempre é o do servidor.
- O vendedor ligado ao usuário é escolhido numa lista de vendedores ativos (até 100); um vendedor já ligado mas inativo aparece como "Vendedor já ligado a este acesso".
- Tipo de acesso na tela: "Dona" (perfil ADMIN) e "Vendedor".

**Dúvidas**
- O tipo de acesso ADMIN deve aparecer como "Dona" ou "Administradora"? O servidor usa "pessoa administradora" nas mensagens; a tela usa "Dona" (glossário, parte 1).
- Mais de 100 vendedores ativos: precisa de busca na escolha? Hoje não cabe mais que 100.

### Tarefa 1.9: cadastros básicos (owner-app)

**O que foi feito**
- Seis telas no Corona Gestão, saindo de "Em breve": Cadastros → Fornecedores, Categorias, Praças, Semanas, Restrições e Pessoas → Clientes. Uma só tela genérica (`telas/cadastros/TelaDeCadastro.tsx` + `FormularioDeCadastro.tsx`) guiada por uma definição por cadastro (`definicoes.ts`: colunas, campos, textos). Reaproveita a base da 1.8 (`Tabela`, `Paginacao`, `EstadoDaLista`, `JanelaDeConfirmacao`, `AvisoDeErro`, `useBusca`, `chamar`).
- Lista com busca (espera a pessoa parar de digitar), páginas de 20, estados de carregando, vazio, busca sem resultado e erro. Criar e editar: cursor no primeiro campo, Enter salva, campo obrigatório vazio avisado no campo, erro de campo do servidor no campo (inclusive `endereco.cep`), mensagens que o app não mostra num campo vão no topo. 409 de versão mostra o texto do servidor com **Atualizar**.
- Excluir com confirmação (foco em **Voltar**); exclusão lógica pela API. Se o servidor recusa (praça ou restrição em uso etc.), mostra o texto dele e a linha continua.
- Clientes: CPF, telefone e WhatsApp como a pessoa digitou (o servidor confere o CPF e devolve o texto); endereço em grupo; vendedor responsável escolhido numa lista (`vendedores.escolha`). Fornecedores: CNPJ e endereço. Semanas: número, primeiro e último dia (datas dd/mm/aaaa na lista) e situação Aberta/Fechada. Restrições: código, descrição e "Impede montar maleta" (o campo `bloqueiaRemessa` da API; "remessa" não aparece na tela).
- Ponte: 24 operações novas (`<cadastro>.listar|criar|editar|excluir`) em `shared/cadastros.ts`, conferidas de novo no processo principal (`validacao-parametros.ts`): só campos conhecidos, tipo e tamanho certos, endereço só onde existe, `id` do app só em categorias, semanas e clientes, `version` obrigatória ao editar. `main/api.ts` liga cada operação à rota do cliente gerado; nenhuma rota nova foi preciso gerar.
- Testes: 11 de tela (lista, busca, criar com Enter, campo obrigatório, erro no campo do CPF, escolha de vendedor, semana, 409, excluir, excluir recusado) e 6 de conferência da ponte.

**O que ficou pendente**
- Reativar: a API só tem excluir (exclusão lógica) para estes seis cadastros; não há rota de reativar nem de listar excluídos. A tela oferece **Excluir** (não "Desativar/Reativar") e o texto diz que não dá para desfazer por aqui.
- Rodar o Electron de ponta a ponta contra a API real não foi feito; a cobertura é de testes de componente e de lógica.
- Sem máscara de CPF, CNPJ, telefone e CEP (a §19 pede máscaras); fica para uma melhoria depois de confirmar o formato do Paraguai.

**Decisões tomadas**
- Uma tela genérica por definição, em vez de seis telas: menos código e o mesmo comportamento em todas.
- `id` (UUID) é gerado no app ao criar categoria, semana e cliente (a API aceita); assim repetir o envio não duplica (409 em vez de segundo cadastro).
- O nome mostrado nas mensagens: razão social, nome, "Semana N" e a descrição da restrição.

**Dúvidas**
- Falta de "reativar": a dona precisa recuperar um cadastro excluído? Se sim, a API precisa de uma rota (tarefa de backend).
- Semana FECHADA ainda pode ser editada na tela (regra da 1.2 mantida).


### Tarefa 1.10: vendedores (owner-app)

**O que foi feito**
- Pessoas → Vendedores saiu de "Em breve": lista (busca por nome, CPF, CNPJ, telefone ou código; filtros de praça e situação; páginas de 20) e ficha com nove abas: Cadastro, Endereço, Filiação e contato, Trabalho, Cônjuge, MEI, Referências, Observações e Documentos. Código, limite de consignação (com Real, Dólar e Guarani logo abaixo, pelo `CampoDeDinheiro`), restrição, comissão (%) e praça ficam na aba Cadastro; desativar e reativar pedem confirmação (exclusão lógica; não há botão de excluir).
- Cada aba se grava sozinha com a própria `version` (aba nunca gravada vem vazia, versão 0). Erros de campo aparecem no campo, 409 mostra o texto do servidor com **Atualizar**. Vendedor novo só libera as outras abas depois de salvar o Cadastro. As abas visitadas continuam montadas, então trocar de aba não perde o que foi digitado; a aba com alteração não salva leva um "•".
- Aviso de alterações não salvas ao voltar para a lista, ao escolher outro item do menu e ao fechar a janela (`nucleo/guarda-de-saida.ts`).
- Referências: lista, nova, editar e excluir (com confirmação). Documentos: enviar (foto ou PDF de até 10 MB), baixar e excluir (com confirmação). O arquivo vai e volta pela ponte como bytes (`Uint8Array`) e o processo principal o manda ao servidor em formulário (multipart); nunca como texto codificado. Tipo (JPG, PNG, WebP, PDF), tamanho e arquivo vazio são conferidos na tela e na ponte; o servidor confere o tipo pelo conteúdo.
- Ligação com usuário: a ficha mostra o usuário que tem este `vendedor_id` (ou diz onde criar o acesso). Só informação.
- Ponte: 16 operações novas `vendedores.*` (`shared/vendedores.ts`), conferidas no processo principal: só campos conhecidos, dinheiro como texto decimal não negativo, comissão de 0 a 100, sexo da lista, `version` obrigatória ao editar, arquivo com nome, tipo e tamanho válidos. Campo desconhecido (inclusive `ativo`) recusa tudo. `main/api.ts` liga cada operação às rotas do cliente gerado (nenhuma rota nova no backend).
- Testes: 24 de tela, 9 de conferência da ponte e 5 do adaptador da API (baixar, enviar em multipart, editar com versão, rota das abas). O teste antigo do menu passou a usar Produtos como exemplo de "Em breve".

**O que ficou pendente**
- O filtro de praça da lista é feito na tela (a API não filtra por praça): com praça escolhida a tela busca até 100 vendedores e separa. Se a loja passar de 100 vendedores, precisa de um filtro na API.
- Baixar documento usa o download do navegador embutido (link de `Blob`); a CSP da tela não foi mexida. Não rodei o Electron de ponta a ponta para ver o Windows perguntando onde guardar.
- Inativar o vendedor não desativa o usuário dele (pendência da 1.3).
- Sem máscara de CPF, CNPJ, telefone e CEP (igual à 1.9). Sem botão de excluir vendedor (só desativar).

**Decisões tomadas**
- Uma ficha com um formulário genérico por lista de campos (`FormularioDeCampos`) para o Cadastro e as seis abas de dados, em vez de nove formulários.
- Arquivo como bytes pela ponte, em vez de caminho do disco: a tela nunca passa caminho ao processo principal.
- A aba "Cadastro" não tem caixa "Ativo": a situação muda pelo botão Desativar/Reativar, com confirmação, porque a API tem rotas próprias para isso.

**Dúvidas**
- Desativar o vendedor deve também desativar o acesso dele? (repete a dúvida da 1.3)
- O limite de consignação e a comissão devem pedir confirmação extra ao mudar (a §11 pede auditoria no servidor, que ainda não existe para estes campos)?

### Tarefa 1.11: produtos, códigos, custo, fotos, importação em massa e leitor (owner-app)

**O que foi feito**
- Cadastros → Produtos saiu de "Em breve", com quatro abas: **Lista**, **Importar planilha**, **Importar fotos** e **Consultar produto**.
- **Lista:** foto em miniatura em toda linha (a foto vem da API pela ponte e fica guardada por hash); sem foto, foto que não carrega ou internet fora mostram o marcador "Sem foto" e nunca travam nada. Busca por nome, código (qualquer um) ou referência; filtros de categoria, fornecedor e situação; páginas de 20.
- **Ficha/cadastro:** cursor já em Código; o leitor age como teclado (Enter confere o código e não envia o formulário). A **velocidade das teclas** (`DetectorDeLeitor`) separa leitor de digitação: lido pelo leitor mostra "Código lido pelo leitor." e sugere o tipo "Código de barras" se o EAN-13 de 13 números confere. Duplicidade conferida na hora ("Já existe: {produto}", com botão para abrir esse produto; para código do fornecedor repetido, o texto diz que a dona decide). Código sempre como texto (zeros à esquerda). Tipos Código da loja / do fornecedor / de barras, vários códigos e um principal; dígito verificador só para EAN-13 de 13 números; EAN-13 com poucos números avisa "Leia o código de novo". **Gerar código da loja** usa a rota do servidor (a numeração é só dele). Preço de venda e **custo** (campo de dinheiro com Real, Dólar e Guarani; o custo só aparece aqui), estoque mínimo, categoria, fornecedor, referência. Salvar manda os dados (com a versão lida) e, se os códigos mudaram, a lista de códigos com a versão nova. 409 de versão mostra o texto do servidor com **Atualizar**. Desativar e reativar com confirmação (foco em Voltar). Aviso de alterações não salvas (mesma guarda da 1.10).
- **Fotos na ficha:** escolher arquivo, **colar (Ctrl+V)** e **arrastar**; tipo (JPG, PNG, WebP), tamanho (10 MB) e arquivo vazio conferidos na tela e na ponte antes de enviar; foto colada sem extensão ganha a do tipo. Miniatura/grande vêm da API; tornar principal, ver maior, remover com confirmação; com 5 fotos o envio fica bloqueado com o texto do glossário. Depois de mexer nas fotos a ficha lê o produto de novo só para pegar a versão nova (não apaga o que foi digitado).
- **Importação por planilha (.xlsx ou CSV):** colunas código, nome, categoria, custo e preço (em qualquer ordem, com ou sem acento; valores como "1.234,56", "12,5" ou "12.5"). Escolher → **prévia linha a linha sem gravar nada** (cadastrar, atualizar ou não importar, com o motivo) → confirmar → **relatório** (cadastrados, atualizados e não importados, com o motivo de cada recusa, inclusive a do servidor). Código que já existe atualiza (mantém referência, fornecedor e estoque mínimo); categoria precisa existir. Até 5000 linhas e 20 MB.
- **Importação de fotos em massa:** escolher fotos ou uma pasta → prévia só com os nomes (nenhuma foto sai do computador) → confirmar → envio em lotes de 10 → relatório por arquivo (gravada, já estava, sem produto, nome repetido, recusada, com a mensagem). Nomes repetidos na prévia **não são enviados** (entre lotes o servidor não veria a repetição).
- **Consulta de produto:** campo de leitura que mostra foto, preço em três moedas, código principal e **saldo** (a rota já devolve `estoqueAtual`, que fica 0 até as movimentações da Fase 2). O foco volta ao campo depois de cada leitura; código inexistente tem texto próprio.
- **Ponte:** 16 operações novas `produtos.*` (`shared/produtos.ts`, `shared/validacao-produtos.ts`), conferidas de novo no processo principal: só campos conhecidos (campo desconhecido, inclusive `estoqueAtual`, recusa tudo), dinheiro como texto decimal, `version` obrigatória ao editar, tipo de código da lista, foto só JPG/PNG/WebP até 10 MB, prévia só com nomes, no máximo 10 fotos por lote, hash de foto de 64 letras/números. A foto volta como bytes. A tela continua sem token. Nenhuma rota nova no backend e nenhuma regeneração do cliente.
- A CSP da tela ganhou `blob:` em `img-src` (as fotos viram imagens locais; o resto continua fechado).
- Testes: 29 de tela, 9 da conferência da ponte, 2 do adaptador da API, 11 das regras de código e leitor, 22 da planilha (CSV, .xlsx com zip real, prévia) e 3 das fotos. O teste do menu deixou de usar Produtos como exemplo de "Em breve".

**O que ficou pendente**
- **Webcam:** não feita (precisa de permissão de câmera no Electron e de uma tela de captura; não era simples). Fica como pendência; escolher, colar e arrastar cobrem o uso.
- **Histórico de custos:** não existe rota para listar o histórico (a API só guarda o custo atual e a auditoria `PRECO_CUSTO_ALTERADO`). A ficha mostra o custo atual e avisa que o histórico ainda não aparece. Precisa de rota de leitura da auditoria/histórico no backend.
- **Limite de partes do Tomcat (pendência da 1.6):** conferido só pela configuração (`server.tomcat.max-part-count=20`, pedido de até 110 MB): a tela manda no máximo **10 partes por pedido**, abaixo do limite. Não rodei uma importação real contra a API em execução.
- A importação por planilha grava linha a linha pelas rotas normais de produto (não há rota de importação em massa de produtos); 5000 linhas fazem 5000 pedidos. Se ficar lento, falta uma rota de lote no backend.
- Planilha com várias abas: só a primeira é lida. Preço sugerido (custo x 7) continua sem cálculo (ADR-0007).
- Rodar o Electron de ponta a ponta contra a API real, o leitor físico e o Windows abrindo o seletor de arquivos/pasta não foram testados aqui (cobertura de testes de componente e lógica).

**Decisões tomadas**
- Produtos é uma tela só com abas (lista, planilha, fotos, consulta) em vez de itens novos no menu.
- O .xlsx é lido sem biblioteca nova: um leitor de zip/XML de ~100 linhas (a biblioteca comum, SheetJS, tem vulnerabilidades conhecidas no npm). Só planilhas simples (texto e números); fórmulas aparecem como o último valor calculado.
- Importação por planilha atualiza o produto que já tem o código (como a especificação pede "atualizados"); o servidor continua dono das regras (código repetido, categoria que sumiu), e a recusa dele vira o motivo da linha.
- Código lido que confere como EAN-13 troca o tipo para "Código de barras" sozinho; digitado à mão, o tipo escolhido fica.
- Quem aparece como "Já existe" é sempre outro produto; o próprio produto não conta.

**Dúvidas**
- A planilha atual da loja já tem essas cinco colunas e esses títulos? Se vier com outros nomes (por exemplo "COD", "COD Fábrica"), é só acrescentar os apelidos.
- Importar planilha deve poder trocar a categoria de produto que já existe? Hoje troca (a categoria da planilha vale).
- Webcam é desejada no balcão? Se sim, vira tarefa própria.

### Tarefa 1.12: relatórios de cadastro (backend + tela) — fecha a Fase 1

**O que foi feito**
- **Backend** (`api/relatorios`, rotas `GET /api/dona/relatorios/cadastros/...`, só ADMIN, sem migração): `vendedores`, `clientes`, `fornecedores`, `produtos` (com custo), `produtos-sem-codigo-de-barras`, `codigos-em-conflito`, `aniversariantes` e `alteracoes-de-produtos`. O de "produtos sem foto" é a rota que já existia (`GET /api/dona/produtos/sem-foto`, tarefa 1.6), agora com tela.
- **Uma só fonte de cálculo** (`MotorDeRelatorio`): cada relatório descreve o FROM, as condições e o rodapé; as linhas, a contagem, cada total e o detalhamento de cada total saem das mesmas condições. Resposta igual em todos: `itens`, `total`, `pagina`, `tamanho`, `totais` (rodapé: chave, rótulo, quantidade e, quando há valor, `valorEmReais` em NUMERIC(14,2), texto com duas casas) e `recorte`. `recorte=<chave do total>` devolve só as linhas que formam aquele total ("ver os registros"); o rodapé é sempre do filtro inteiro.
- Filtros: busca, categoria, fornecedor, praça, vendedor, restrição, situação (`ATIVOS` padrão, `INATIVOS`, `TODOS`), período `de`/`ate` sobre a data do cadastro (UTC, inclusive nas duas pontas) e `mes`. Excluídos (`deleted_at`) nunca aparecem; inativos só quando se pede. Página de 20 (até 100).
- **Códigos em conflito** (calculado em memória com a regra `Ean13` do servidor): códigos quase iguais (só mudam maiúscula, espaço, traço ou pontuação), o mesmo caso entre fornecedores diferentes, e código de barras sem 13 números ou com dígito verificador errado. O código realmente repetido o servidor já recusa (índice único), por isso não aparece.
- **Aniversariantes:** vendedores do mês escolhido (sem `mes`, o mês de hoje em Brasília); a data é `date` de calendário, sem fuso, então 31/03 continua 31/03.
- **Mudanças nos produtos:** produtos cadastrados e preços de venda/custo alterados, lidos da auditoria (`PRODUTO_CRIADO`, `PRECO_VENDA_ALTERADO`, `PRECO_CUSTO_ALTERADO`), com quem fez e quando, por período. O valor do custo não aparece.
- **Tela** (Corona Gestão, grupo de menu "Relatórios de cadastros", um item por relatório): filtros, tabela, rodapé com **Ver os registros** em cada total (e **Ver o relatório inteiro** para voltar), valores em Real, Dólar e Guarani pela **cotação de hoje** e exportação em **PDF, Excel (.xlsx) e CSV** com cabeçalho (loja, filtros usados, data e hora de Brasília, quem gerou, linha da cotação), as três moedas, a data da cotação em cada linha e os totais no fim. O arquivo traz o relatório inteiro (todas as páginas, até 10.000 linhas), não só o que está na tela. Os totais em Dólar e em Guarani são a **soma das linhas já convertidas** (na tela e nos arquivos), então o detalhamento fecha.
- Exportação sem biblioteca nova: CSV (UTF-8 com marca, `;`, vírgula decimal; texto que começa com `=`, `+`, `-` ou `@` ganha apóstrofo para não virar fórmula), .xlsx (zip sem compressão com XML; texto como texto, números como números) e PDF (Helvetica em Windows-1252, A4 deitada, títulos repetidos em cada página, "Página x de y"). Nada novo no `package.json`.
- **Ponte:** uma operação nova, `relatorios.cadastros` (`shared/relatorios.ts`, `shared/validacao-relatorios.ts`), conferida de novo no processo principal: nome do relatório da lista fixa, filtros conhecidos, UUIDs, datas AAAA-MM-DD, situação da lista, mês de 1 a 12, recorte só com letras minúsculas, números e hífen, página e tamanho. Campo desconhecido recusa tudo. O custo só existe no app da dona (a rota é `/api/dona/...` e o app do vendedor não tem esse código).
- Clientes da API regerados (`npm run api:clientes`); só o da dona mudou.
- **Testes:** backend (`RelatoriosDeCadastroTest`, 11 testes com massa conhecida e resultado calculado à mão): permissão (vendedor recebe 403 em todos), limite somado, restrição, situação, praça, período, exclusão lógica, custo, somas, EAN-13, conflitos, aniversariantes sem deslocar o dia e auditoria. **Conciliação** em cada relatório: percorre todas as páginas e confere que a contagem das linhas = total = total "todos" do rodapé, que cada total do rodapé = contagem do seu detalhamento e que o valor do rodapé = soma das linhas. Front: 13 testes das regras de exportação (três moedas, soma das linhas convertidas, CSV, .xlsx lido de volta pelo leitor da 1.11, PDF com várias páginas, fórmulas neutralizadas), 14 da tela (menu, cotação, filtros, detalhamento, total em Dólar = soma das linhas, exportação, palavras proibidas) e 12 da conferência da ponte.

**O que ficou pendente**
- **Resultado das importações:** o servidor não guarda o resultado de uma importação (a planilha e as fotos são importadas produto a produto pelas rotas normais, e o resultado com linhas aceitas e recusadas só existe na tela, na hora). A auditoria só distingue "produto cadastrado" e "preço alterado", sem dizer se veio de uma importação. Não criei persistência para isso. Fica para a **Fase 2**: gravar o lote da importação (quem, quando, linhas aceitas e recusadas com o motivo) e então ligar o relatório. Enquanto isso a tela "Mudanças nos produtos" mostra o que foi cadastrado e alterado, com um aviso dizendo que o resultado da importação ainda não fica guardado.
- **Aniversariantes de clientes:** o cadastro de cliente não tem data de nascimento (nem na tela nem na tabela `clientes`). O relatório é só de vendedores, com aviso na tela. Para incluir clientes: campo novo no cadastro de clientes (migração, sincronizável, tela da 1.9) e depois o relatório.
- As listas dos filtros (categoria, fornecedor, praça, restrição) carregam até 100 itens; acima disso os demais não aparecem no filtro (a busca por texto continua achando).
- A exportação pede o relatório inteiro em páginas de 100 (até 100 páginas); acima de 10.000 linhas pede para usar os filtros. Se ficar lento, falta uma rota de exportação no servidor.
- Telas não conferidas dentro do Electron de verdade (cobertas por testes de componente), e os arquivos PDF e Excel não foram abertos num leitor real (o .xlsx é lido de volta pelo leitor da 1.11; do PDF só a estrutura e o texto são conferidos no teste).

**Decisões tomadas**
- **"Produtos sem código de barras" em vez de "sem código do fornecedor":** o EAN-13 não é obrigatório (ADR-0007), então o relatório é só informativo e mostra, dentro dele, quantos têm código do fornecedor e quantos só o código da loja. "Sem código do fornecedor" não é um erro (a peça pode ser da própria loja), por isso não virou relatório à parte.
- **Cotação dos relatórios de cadastro:** como cadastro não tem "dia do lançamento", os valores em Dólar e Guarani usam a cotação de hoje (a mesma do rodapé da tela), e o cabeçalho dos arquivos diz isso. Quando a cotação de hoje é a de um dia anterior, vale o texto do glossário ("a última publicada").
- **Dinheiro nas respostas em Real**; a conversão é só na tela e nos arquivos, com `BigInt`, igual ao resto do app. Totais convertidos = soma das linhas convertidas (§12).
- **Nomes das telas** (glossário): Vendedores: limite e situação · Clientes cadastrados · Fornecedores cadastrados · Produtos e custos · Produtos sem foto · Produtos sem código de barras · Códigos repetidos ou parecidos · Aniversariantes do mês · Mudanças nos produtos.
- A exportação sempre leva o relatório inteiro com os filtros da tela, mesmo se a pessoa estiver vendo o detalhamento de um total.
- "Mudanças nos produtos" (pela auditoria) no lugar de "Resultado das importações", que não existe no servidor (ver pendência).

**Dúvidas**
- A dona quer a data de nascimento no cadastro de clientes? Se sim, vira uma tarefa pequena (campo, tela e relatório).
- As colunas dos relatórios servem para ela? Deixei de fora CPF e endereço do vendedor de propósito, para o arquivo não circular com dados pessoais.

### Tarefa 1.13: ajustes de fechamento da Fase 1

**O que foi feito**

*A) Fotos sem demora (owner-app, processo principal e tela)*
- **Cache em disco** (`main/cache-de-fotos.ts`, pasta `fotos/` dentro da pasta de dados do app): um arquivo por `hash.tamanho`. A foto é imutável por hash, então **nunca se pergunta ao servidor se mudou**; trocou a foto, o hash é outro. Só entra o que o servidor entregou a uma sessão autenticada, e a leitura só acontece dentro de uma chamada já autenticada (`produtos.fotos.imagem`). Limite de **200 MB**: passou, o arquivo mais antigo (último uso) é descartado. Hash e tamanho viram nome de arquivo só nos formatos exatos (64 hexadecimais; miniatura, media, grande), então nada de `../`.
- **Ao sair da sessão a pasta é apagada** (sair pelo botão ou sessão perdida): a foto de produto não fica no computador de quem não está logado. A tela também revoga os endereços `blob:`.
- **No máximo 4 fotos sendo buscadas ao mesmo tempo** (`LimiteDeBuscas`): uma lista com 100 miniaturas não trava as outras chamadas.
- **Tela** (`nucleo/imagens.ts`): a mesma foto é pedida uma vez; `URL.revokeObjectURL` quando passa de 300 fotos na memória (só as que ninguém está mostrando); **pré-busca** das miniaturas da página da lista assim que ela chega; na consulta por código e na ficha, a **miniatura aparece na hora** (levemente apagada) enquanto a média ou a grande carrega.
- **Medido** (teste automático com servidor simulado de 150 ms): 1ª exibição ~157 ms; 2ª exibição da mesma foto ~0,2 ms e **nenhuma chamada de rede** (o teste conta as chamadas). Servidor real e disco de verdade não foram medidos aqui: a 1ª depende da rede e do tamanho da foto (a miniatura tem cerca de 150 px).

*B) Filtros dos relatórios de cadastro (§12)*
- API (parâmetros validados, 400 em português simples; o rodapé e o detalhamento continuam saindo das mesmas condições): **vendedores** `limiteDe`/`limiteAte` e `comRestricao` (SIM/NAO); **clientes** `comVendedor`; **fornecedores** `comProdutos`; **produtos** `estoque` (ZERADO, ABAIXO_DO_MINIMO, COM_ESTOQUE), `precoDe`/`precoAte` (preço de venda) e `foto` (COM/SEM). Ponte (`shared/relatorios.ts`, `validacao-relatorios.ts`) e tela (filtros próprios de cada relatório, também nos cabeçalhos dos arquivos).
- **Conferido contra a §12** (período, semana, vendedor, praça, cliente, produto, categoria, situação): ver "O que ficou de fora".

*C) Resultado das importações*
- Migração **V016**: `importacoes` (lote: tipo PLANILHA/FOTOS, arquivo, quem, quando) e `importacoes_linhas` (referência, resultado CRIADO/ATUALIZADO/JA_EXISTIA/RECUSADO, motivo). Só do servidor e só inserção (histórico). O id do lote é UUID do aplicativo: repetir o envio não duplica, e enviar em partes junta ao mesmo lote.
- Rota `POST /api/dona/importacoes/{id}/linhas` (só ADMIN, até 500 linhas por envio) e relatório `GET /api/dona/relatorios/cadastros/resultado-das-importacoes` (busca, tipo, resultado, período `de`/`ate`; rodapé: criados, atualizados, já existiam, recusados).
- **Fotos:** o próprio servidor grava o resultado de cada foto enviada (`confirmar?importacaoId=`), então o resultado de foto gravada não depende do app. As fotos que a tela nem enviou (sem peça, nome repetido) o servidor não viu: o app informa essas, pelo mesmo lote.
- **Planilha:** o produto é criado/atualizado pelas rotas normais, uma a uma; no fim o app informa o resultado de cada linha. Esse registro é **declarado pelo app** (o servidor valida formato, mas não confere se a linha de fato virou produto). Se o registro falhar, a tela avisa e a importação já feita não é desfeita.
- Tela: relatório "Resultado das importações" no menu de relatórios; aviso fixo dizendo o que vale.

**O que ficou de fora e por quê**
- ~~Situação (ativo/inativo) de cliente e de fornecedor~~ e ~~data de nascimento do cliente / aniversariantes de clientes~~: **resolvidos na tarefa 1.14** (decisão do responsável de 10/11/2026).
- **Filtros "semana", "cliente" e "produto" nos relatórios de cadastro:** não se aplicam ao cadastro (semana, cliente e produto como filtro são de vendas, maletas e acerto, nas Fases 3 e seguintes). Vendedor, praça, categoria, fornecedor, período e situação existem onde o dado existe.
- **Relatórios de cadastro por produto:** não filtra por "código" em faixa nem por tipo de código; a busca por texto cobre nome, referência e código.
- Resultado de importação anterior a esta tarefa não existe (nunca foi guardado).
- Telas não conferidas dentro do Electron de verdade (testes de componente) e cache em disco só testado com pasta temporária e servidor simulado.

**Decisões tomadas**
- Cache em disco no processo principal, não no navegador: a pasta é apagada ao sair da sessão e o app do vendedor não é tocado (a barreira de código continua passando).
- Sem revalidar: foto é imutável por hash (o servidor já manda `Cache-Control: immutable`).
- Resultado de planilha declarado pelo app e resultado de foto gravado pelo servidor; a diferença está escrita na nota do relatório.
- Filtros novos entram como `Mais` (um registro à parte) no servidor, para não mexer em todos os relatórios.

**Dúvidas**
- 200 MB é um bom teto para o cache de fotos no computador dela?

### Tarefa 1.14: ativo/inativo em todos os cadastros e data de nascimento do cliente
**Concluída (PR a informar).** Decisões do responsável de 10/11/2026 (ESPECIFICACAO v1.7: §9, §10, §12 e registro de mudanças, escritas antes do código).

**O que foi feito**
- **Migração V017** (nova; nenhuma publicada foi editada): `ativo boolean NOT NULL DEFAULT true` em `fornecedores`, `pracas`, `restricoes`, `categorias` e `clientes`; `clientes.data_nascimento date` (opcional, o banco só recusa antes de 1900; "não futura" é conferido pela API) e índice do mês para os aniversariantes. As linhas que já existiam ficam ativas; o `ADD COLUMN` com valor padrão constante não dispara o gatilho de `sync_seq`.
- **Contrato do sync:** `RegistroDeTabelas` ganha `ativo` em `categorias` e em `clientes`, e `data_nascimento` em `clientes`. O teste de convenções confere as colunas. O cliente da API do vendedor não mudou (as rotas do sync não estão no OpenAPI dele).
- **API da dona** (só ADMIN, sem mudar o que já existia): `POST /{id}/inativar` e `/{id}/reativar` em fornecedores, categorias, praças, restrições e clientes, com `version` opcional no corpo (se vier e estiver velha, 409; sem ela vale a versão de agora), auditoria (`INATIVADO`/`REATIVADO`, na mesma transação) e 404 para o que não existe ou foi excluído. As listas ganham `situacao` (ATIVOS por padrão, INATIVOS, TODOS; outro valor dá 400 "Escolha ATIVOS, INATIVOS ou TODOS."). As respostas trazem `ativo`; o cliente traz `dataNascimento`. `DELETE` continua só lógico. Base nova `ServicoDeCadastroAtivavel` (uma só implementação para os cinco).
- **Relatórios (1.12/1.13):** `situacao` em clientes e fornecedores (ATIVOS por padrão) e coluna Situação; clientes mostram a data de nascimento; aniversariantes passa a unir vendedores e clientes (`quem`: TODOS, VENDEDORES ou CLIENTES; a praça do cliente é a do vendedor responsável), com os totais "Vendedores" e "Clientes" no rodapé conciliados com as linhas e o detalhamento. A nota "Só vendedores..." saiu.
- **owner-app:** em cada tela de cadastro (menos semanas) filtro Situação, coluna Situação (marca "Inativo"), botões Inativar (com confirmação "pode voltar a qualquer hora") e Reativar; campo Data de nascimento no cliente. Ponte com validação em `shared/` (`situacao` na lista, `inativar`/`reativar` com `id` e `version`, `dataNascimento`, `quem` nos relatórios). Listas de escolha (categoria e fornecedor do produto, praça e restrição do vendedor) só com ativos, mostrando o inativo já escolhido com "(inativo/a)"; filtros e importação continuam enxergando todos. Clientes da API regerados; `main/api.ts` ajustado porque dois nomes numerados do cliente gerado mudaram (`inativar1`→`inativar2`, `reativar2`→`reativar3` do produto).
- Glossário: Ativo, Inativo, Inativar, Reativar, Situação, Data de nascimento, textos de confirmação e do relatório de aniversariantes.

**Decisões que tomei**
- **Semanas não ganham `ativo`:** ABERTA/FECHADA já diz se a semana está em uso (documentado na §9). A rota `/inativar` não existe para elas.
- **Inativar é permitido com o cadastro em uso e preserva os vínculos** (sem aviso nem bloqueio, como pedido). Só as listas de ESCOLHA escondem inativos; a ficha mostra o inativo já ligado.
- **Data de nascimento do cliente vai ao app do vendedor** (padrão seguro pedido): é o vendedor quem cadastra o cliente, e o filtro por vendedor da Fase 5 já limita ao que é dele. Decidido e escrito na §10.
- `version` é opcional em inativar/reativar (como nas rotas de vendedores e produtos); a tela sempre manda.
- Reativar não pede confirmação (não tem risco); inativar pede.
- A validação "não escolher inativo" é só da tela. A API aceita salvar um produto ou vendedor ligado a um cadastro inativo, para a ficha antiga continuar salvando sem mudar o vínculo.
- Os relatórios de clientes e fornecedores passam a mostrar só ativos por padrão (como os de vendedores e produtos); como tudo nasce ativo, nada some até alguém inativar.

**O que ficou de fora**
- **Vendedores, produtos e usuários** não foram mexidos: já tinham ativo, mas a lista de vendedores continua com padrão TODOS (a tela manda ATIVOS explicitamente) e as telas ainda dizem "Desativar/Desativados" (usuários) e "Desativados" (produtos); alinhar a "Inativar" é um ajuste de texto à parte.
- Validação no servidor de "não ligar a um cadastro inativo" ao criar um vínculo novo (hoje é só a tela).
- O app do vendedor (Fase 5) ainda não consome `ativo`/`data_nascimento`: o contrato do pull já leva os campos, mas o filtro por vendedor e a tela dele são daquela fase.
- Telas não conferidas dentro do Electron de verdade (testes de componente).

**Dúvidas**
- A dona confirma que a data de nascimento do cliente pode ir ao app do vendedor (só dos clientes dele)?
- Alinhar o texto das telas de usuários e produtos ("Desativar") para "Inativar" também?
