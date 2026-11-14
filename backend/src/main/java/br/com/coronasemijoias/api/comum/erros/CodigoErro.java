package br.com.coronasemijoias.api.comum.erros;

/**
 * Códigos de erro para o programa (campo {@code codigo} da resposta) e os textos em português para
 * a pessoa. Os textos vêm de docs/glossario-interface.md (parte 6 e "Erros gerais da API"): para mudar um
 * texto, mude o glossário primeiro.
 */
public enum CodigoErro {

    ERRO_INTERNO(
            "Algo deu errado agora.",
            "Algo deu errado agora. Tente de novo. Se continuar, fale com o suporte do sistema."),

    ROTA_NAO_ENCONTRADA(
            "Não encontramos o que você pediu.",
            "Não encontramos o que você pediu. Atualize o aplicativo e tente de novo. "
                    + "Se continuar, fale com o suporte do sistema."),

    DADOS_INVALIDOS(
            "Alguns dados não estão corretos.",
            "Alguns dados não estão corretos. Confira o que você preencheu e tente de novo."),

    PEDIDO_INVALIDO(
            "Não foi possível entender o pedido.",
            "Não foi possível entender o pedido. Atualize o aplicativo e tente de novo. "
                    + "Se continuar, fale com o suporte do sistema."),

    /** Perfil sem acesso à rota (HTTP 403). */
    SEM_PERMISSAO(
            "Você não tem acesso a esta parte do sistema.",
            "Você não tem acesso a esta parte do sistema. Atualize o aplicativo e tente de novo. "
                    + "Se continuar, fale com o suporte do sistema."),

    // --- Mensagens padrão da Fase 0 (glossário, parte 6) ---

    /**
     * Login inexistente, senha errada ou usuário inativo com senha errada (HTTP 401). A última frase
     * do glossário depende do aplicativo: cada um a acrescenta.
     */
    LOGIN_INVALIDO(
            "Não foi possível entrar.",
            "Não foi possível entrar. Usuário ou senha incorretos. Confira o que você digitou e tente de novo."),

    /** Usuário desativado: no login com a senha certa (403) ou no uso de um token ainda válido (401). */
    USUARIO_DESATIVADO(
            "Seu acesso foi desativado.",
            "Seu acesso foi desativado. Fale com a dona da loja para voltar a usar o sistema."),

    /** Sem token, token vencido ou inválido (HTTP 401). */
    SESSAO_TERMINADA(
            "Sua sessão terminou.",
            "Sua sessão terminou. Entre de novo com seu usuário e sua senha para continuar."),

    /**
     * Bloqueio por tentativas (HTTP 429). {@code {minutos}} é o que falta, arredondado para cima e no
     * mínimo 1; com 1, o texto fica no singular (glossário, parte 6). Montado por
     * {@link ErroDaApi#acessoBloqueado(java.time.Duration)}.
     */
    ACESSO_BLOQUEADO(
            "Acesso bloqueado por alguns minutos.",
            "Acesso bloqueado por alguns minutos. Você errou a senha várias vezes. Por segurança, "
                    + "espere {minutos} minutos e tente de novo."),

    // --- Senha temporária e cadastro de usuário (glossário, parte 6; tarefa 10) ---

    /**
     * Senha temporária certa, mas depois do prazo (HTTP 401). Como no {@link #LOGIN_INVALIDO}, a última
     * frase depende do aplicativo e a tarefa 12 a acrescenta (glossário, parte 6).
     */
    SENHA_TEMPORARIA_VENCIDA(
            "Sua senha temporária venceu.",
            "Sua senha temporária venceu. A senha que você recebeu só vale por alguns dias, e esse prazo acabou."),

    /** Quem entrou com a senha temporária só pode criar a senha nova (HTTP 403). */
    TROCA_DE_SENHA_OBRIGATORIA(
            "Crie sua senha nova.",
            "Crie sua senha nova. A senha que você recebeu é temporária. "
                    + "Para continuar, crie uma senha nova que só você saiba."),

    /** Senha nova com mais de 72 bytes, o limite do BCrypt (HTTP 400). */
    SENHA_LONGA_DEMAIS(
            "Esta senha é longa demais.",
            "Esta senha é longa demais. Escolha uma senha mais curta, com no máximo 36 caracteres."),

    /** Senha nova com menos de 8 caracteres, ou sem letras, ou sem números (HTTP 400). */
    SENHA_FRACA(
            "A senha nova ainda não está boa.",
            "A senha nova ainda não está boa. Ela precisa ter pelo menos 8 caracteres, com letras e números."),

    /** Senha nova igual à temporária (HTTP 400). */
    SENHA_IGUAL_A_TEMPORARIA(
            "A senha nova precisa ser diferente da temporária.",
            "A senha nova precisa ser diferente da temporária. Escolha outra."),

    LOGIN_JA_USADO(
            "Este usuário já existe.",
            "Este usuário já existe. Escolha outro, por exemplo juntando o sobrenome."),

    LOGIN_FORA_DO_FORMATO(
            "Este usuário não pode ser usado.",
            "Este usuário não pode ser usado. Use de 3 a 40 letras sem acento, números ou ponto, "
                    + "sem espaços. Por exemplo: maria.silva."),

    USUARIO_NAO_ENCONTRADO(
            "Não encontramos esta pessoa no cadastro.",
            "Não encontramos esta pessoa no cadastro. Atualize a tela e tente de novo."),

    // --- Tipo e versão do aplicativo (glossário, parte 6; tarefa 12) ---

    /** Cabeçalho X-App-Tipo ausente ou desconhecido (HTTP 400): erro do programa, não da pessoa. */
    APP_TIPO_INVALIDO(
            "Não foi possível entender o pedido.",
            "Não foi possível entender o pedido. Atualize o aplicativo e tente de novo. "
                    + "Se continuar, fale com o suporte do sistema."),

    /** Versão do aplicativo abaixo do mínimo, ausente ou ilegível (HTTP 426). */
    APP_DESATUALIZADO(
            "Seu aplicativo está desatualizado.",
            "Seu aplicativo está desatualizado. Atualize para continuar usando."),

    /**
     * Perfil que não entra neste aplicativo (HTTP 403, só com a senha certa). O texto depende do app:
     * {@link ErroDaApi#appIncompativel(br.com.coronasemijoias.api.aplicativo.TipoDeApp)}.
     */
    APP_INCOMPATIVEL(
            "Este não é o aplicativo do seu acesso.",
            "Este não é o aplicativo do seu acesso. Abra o outro aplicativo da Corona Semijoias."),

    NAO_REDEFINIR_A_PROPRIA_SENHA(
            "Você não pode redefinir a sua própria senha por aqui.",
            "Você não pode redefinir a sua própria senha por aqui. "
                    + "Peça a outra pessoa administradora ou fale com o suporte do sistema."),

    NAO_DESATIVAR_A_SI_MESMA(
            "Você não pode desativar o seu próprio acesso.",
            "Você não pode desativar o seu próprio acesso. Peça a outra pessoa administradora para fazer isso."),

    NAO_MUDAR_O_PROPRIO_ACESSO(
            "Você não pode mudar o seu próprio tipo de acesso.",
            "Você não pode mudar o seu próprio tipo de acesso. Peça a outra pessoa administradora para fazer isso."),

    ULTIMA_ADMINISTRADORA(
            "Precisa continuar existindo pelo menos uma pessoa administradora com acesso.",
            "Precisa continuar existindo pelo menos uma pessoa administradora com acesso. "
                    + "Dê o acesso de administradora a outra pessoa antes de fazer esta mudança."),

    // --- Cadastros da dona (glossário, parte 6; tarefa 1.2) ---

    CADASTRO_NAO_ENCONTRADO(
            "Não encontramos este cadastro.",
            "Não encontramos este cadastro. Atualize a tela e tente de novo."),

    CADASTRO_JA_EXISTE(
            "Este cadastro já existe.",
            "Este cadastro já existe. Confira a lista ou use outro nome ou código."),

    CADASTRO_ALTERADO(
            "Este cadastro foi alterado por outra pessoa.",
            "Este cadastro foi alterado por outra pessoa. Atualize a tela e faça a alteração de novo."),

    SEMANA_SOBREPOSTA(
            "Estas datas já fazem parte de outra semana.",
            "Estas datas já fazem parte de outra semana. Escolha dias que não estejam em outra semana."),

    // --- Cadastro do vendedor e arquivos (glossário, parte 6; tarefa 1.3) ---

    CADASTRO_EM_USO(
            "Este cadastro está em uso.",
            "Este cadastro está em uso. Troque a praça ou a restrição dos vendedores que a usam e tente de novo."),

    ARQUIVO_TIPO_NAO_ACEITO(
            "Este tipo de arquivo não pode ser enviado.",
            "Este tipo de arquivo não pode ser enviado. Envie uma foto (JPG, PNG ou WebP) ou um arquivo PDF."),

    // --- Produtos e códigos (glossário, parte 6; tarefa 1.5) ---

    CODIGO_JA_USADO(
            "Este código já está em uso.",
            "Este código já está em uso por outro produto. Use outro código ou abra o produto que já o usa."),

    CADASTRO_EM_USO_POR_PRODUTOS(
            "Este cadastro está em uso por produtos.",
            "Este cadastro está em uso por produtos. Troque a categoria ou o fornecedor desses produtos e tente de novo."),

    ARQUIVO_GRANDE_DEMAIS(
            "Este arquivo é grande demais.",
            "Este arquivo é grande demais. Envie um arquivo de até 10 MB. Se for uma foto, "
                    + "tire-a com menos qualidade ou reduza o tamanho antes de enviar."),

    FOTO_TIPO_NAO_ACEITO(
            "Esta não é uma foto que o sistema aceite.",
            "Esta não é uma foto que o sistema aceite. Envie uma foto nos formatos JPG, PNG ou WebP."),

    FOTO_NAO_LIDA(
            "Não foi possível abrir esta foto.",
            "Não foi possível abrir esta foto. O arquivo pode estar danificado. Tire ou salve a foto de novo e envie outra vez."),

    FOTO_GRANDE_DEMAIS_EM_PONTOS(
            "Esta foto é grande demais em tamanho de imagem.",
            "Esta foto é grande demais em tamanho de imagem. Reduza a foto (até 25 milhões de pontos, "
                    + "por exemplo 5000 por 5000) e envie de novo."),

    // --- Estoque (glossário, parte 12; tarefa 2.2) ---

    /** Saída, perda, ajuste ou "desfazer" que deixaria o estoque abaixo de zero (HTTP 409). O servidor monta o texto com o que há. */
    ESTOQUE_INSUFICIENTE(
            "Não há peças suficientes no estoque.",
            "Não há peças suficientes no estoque. Confira a quantidade ou receba a compra antes."),

    LANCAMENTO_NAO_ENCONTRADO(
            "Não encontramos este lançamento.",
            "Não encontramos este lançamento. Atualize a tela e tente de novo."),

    LANCAMENTO_JA_DESFEITO(
            "Este lançamento já foi desfeito.",
            "Este lançamento já foi desfeito."),

    /** Pedido de desfazer um lançamento que já é um "desfazer" (HTTP 409). */
    LANCAMENTO_NAO_PODE_SER_DESFEITO(
            "Este lançamento não pode ser desfeito.",
            "Este lançamento já desfez outro e não pode ser desfeito. Se precisar, faça um novo lançamento."),

    /** O mesmo identificador voltou com outros dados (HTTP 409). */
    /** Operação gravada por uma versão antiga do Corona Vendas, num formato que o servidor já não aceita (tarefa 5.13). */
    FORMATO_NAO_ACEITO(
            "Este envio foi feito numa versão antiga do aplicativo.",
            "Este envio foi feito numa versão antiga do aplicativo e não pôde ser aceito. A dona já foi avisada."),

    LANCAMENTO_JA_REGISTRADO_DIFERENTE(
            "Este lançamento já foi registrado com outros dados.",
            "Este lançamento já foi registrado com outros dados. Atualize a tela, confira o histórico e lance de novo se precisar."),

    // --- Compras (glossário, parte 12; tarefa 2.3) ---

    COMPRA_NAO_ENCONTRADA(
            "Não encontramos esta compra.",
            "Não encontramos esta compra. Atualize a tela e tente de novo."),

    /** O mesmo identificador voltou com outros dados (HTTP 409). */
    COMPRA_JA_REGISTRADA_DIFERENTE(
            "Esta compra já foi registrada com outros dados.",
            "Esta compra já foi registrada com outros dados. Confira a lista de compras antes de lançar de novo."),

    COMPRA_JA_DESFEITA(
            "Esta compra já foi desfeita.",
            "Esta compra já foi desfeita."),

    /** Já saíram peças da compra e o estoque não cobre o que seria desfeito (HTTP 409). O servidor monta o texto. */
    COMPRA_NAO_PODE_SER_DESFEITA(
            "Não é possível desfazer esta compra.",
            "Não é possível desfazer esta compra porque já saíram peças dela."),

    // --- Maleta e remessa (ADR-0010; tarefa 3.2) ---

    REMESSA_NAO_ENCONTRADA(
            "Não encontramos esta maleta.",
            "Não encontramos esta maleta. Atualize a tela e tente de novo."),

    /** O mesmo identificador voltou com outros dados (HTTP 409). */
    REMESSA_JA_REGISTRADA_DIFERENTE(
            "Esta maleta já foi registrada com outros dados.",
            "Esta maleta já foi registrada com outros dados. Confira a lista de maletas antes de montar de novo."),

    /** A remessa já foi enviada (ou cancelada) e não pode mais ser mudada (HTTP 409). */
    REMESSA_NAO_EDITAVEL(
            "Esta maleta não pode mais ser mudada.",
            "Esta maleta já foi enviada e não pode mais ser mudada. Para corrigir, faça uma nova maleta."),

    /** Vendedor inativo ou com restrição que impede montar maleta (HTTP 409). O servidor monta o texto. */
    VENDEDOR_NAO_RECEBE_MALETA(
            "Este vendedor não pode receber maleta.",
            "Este vendedor não pode receber maleta agora."),

    /** O vendedor não tem limite de consignação definido nem "Sem limite" marcado (HTTP 409; V026). */
    LIMITE_DE_CONSIGNACAO_NAO_DEFINIDO(
            "O limite de consignação do vendedor não está definido.",
            "Defina o limite deste vendedor antes de montar a maleta."),

    /** O valor da maleta passa o limite de consignação do vendedor (HTTP 409). O servidor monta o texto. */
    LIMITE_DE_CONSIGNACAO_EXCEDIDO(
            "O valor da maleta passa o limite do vendedor.",
            "O valor da maleta passa o limite de consignação do vendedor."),

    /** Peça desativada ou excluída não vai para a maleta (HTTP 409). O servidor monta o texto. */
    PECA_FORA_DA_MALETA(
            "Esta peça não pode ir para a maleta.",
            "Esta peça está desativada ou foi excluída e não pode ir para a maleta."),

    // --- Devolução e cancelamento da maleta (ADR-0010, itens 4 e 5; tarefa 3.3) ---

    /** Devolução de mais peças do que ainda há na maleta (HTTP 409). O servidor monta o texto com o que há. */
    DEVOLUCAO_MAIOR_QUE_O_SALDO(
            "Há menos peças na maleta do que o informado.",
            "Há menos peças na maleta do que o informado. Confira a quantidade."),

    /** Cancelar uma maleta enviada que já teve devolução, venda ou conferência (HTTP 409). */
    MALETA_JA_MOVIMENTADA(
            "Esta maleta já teve peças devolvidas ou vendidas.",
            "Esta maleta já teve peças devolvidas ou vendidas e não pode ser cancelada. Desfaça as devoluções antes, se foi engano."),

    /** Excluir uma peça que ainda está com um vendedor numa maleta enviada (HTTP 409). */
    PECA_ESTA_EM_MALETA(
            "Esta peça está numa maleta.",
            "Esta peça ainda está com um vendedor numa maleta e não pode ser excluída. Receba a devolução das peças antes."),

    /** Desfazer a devolução faria faltar peça no estoque da loja (HTTP 409). O servidor monta o texto. */
    DEVOLUCAO_NAO_PODE_SER_DESFEITA(
            "Não é possível desfazer esta devolução.",
            "Não é possível desfazer esta devolução porque as peças já saíram do estoque da loja."),

    /** Pediram a nota de uma maleta que ainda é rascunho (HTTP 409): ela só sai depois do envio (ADR-0010, item 13). */
    NOTA_SO_DEPOIS_DO_ENVIO(
            "A nota só fica pronta depois que a maleta for enviada.",
            "A nota só fica pronta depois que a maleta for enviada. Envie a maleta e tente de novo."),

    // --- Contagem do estoque (glossário, parte 12; tarefa 2.4) ---

    CONTAGEM_NAO_ENCONTRADA(
            "Não encontramos esta contagem.",
            "Não encontramos esta contagem do estoque. Atualize a tela e tente de novo."),

    /** O mesmo identificador voltou com outros dados (HTTP 409). */
    CONTAGEM_JA_REGISTRADA_DIFERENTE(
            "Esta contagem já foi registrada com outros dados.",
            "Esta contagem já foi iniciada com outros dados. Confira a lista de contagens antes de começar de novo."),

    /** Já existe contagem em andamento com peças da mesma seleção (HTTP 409). */
    CONTAGEM_JA_EM_ANDAMENTO(
            "Já existe uma contagem em andamento.",
            "Já existe uma contagem em andamento com peças desta seleção. Feche ou cancele essa contagem antes de começar outra."),

    /** Contagem fechada ou cancelada não aceita mais mudança (HTTP 409). */
    CONTAGEM_ENCERRADA(
            "Esta contagem já foi encerrada.",
            "Esta contagem já foi encerrada e não pode mais mudar. Comece uma nova contagem se precisar."),

    /** A leitura voltou com outros dados (HTTP 409). */
    LEITURA_JA_REGISTRADA_DIFERENTE(
            "Esta leitura já foi registrada com outros dados.",
            "Esta leitura já foi registrada com outros dados. Atualize a tela e confira a quantidade contada."),

    /** Fechar deixaria alguma peça com menos de zero (HTTP 409). O servidor monta o texto com a peça. */
    CONTAGEM_NAO_PODE_FECHAR(
            "Não é possível fechar esta contagem.",
            "Não é possível fechar esta contagem porque o estoque de uma peça ficaria menor que zero."),

    // --- Venda do vendedor (Fase 4, ADR-0011) ---

    VENDA_NAO_ENCONTRADA(
            "Não encontramos esta venda.",
            "Não encontramos esta venda. Atualize a tela e tente de novo."),

    /** O cadastro do vendedor está inativo: não vende, não confere maleta (HTTP 409; ADR-0011, linha de 11/11/2026). */
    VENDEDOR_DESATIVADO(
            "Este vendedor está desativado.",
            "Este vendedor está desativado. Fale com a dona."),

    /** O mesmo identificador voltou com outros dados (HTTP 409). */
    VENDA_JA_REGISTRADA_DIFERENTE(
            "Esta venda já foi registrada com outros dados.",
            "Esta venda já foi registrada com outros dados. Confira suas vendas antes de lançar de novo."),

    /** A peça pedida é maior que o saldo da maleta (HTTP 409). O servidor monta o texto com a peça. */
    VENDA_SEM_SALDO(
            "Não há peças suficientes na sua maleta.",
            "Não há peças suficientes na sua maleta para esta venda."),

    /** Desconto acima do limite da dona (HTTP 409). O servidor monta o texto com o limite. */
    DESCONTO_ACIMA_DO_LIMITE(
            "O desconto passa do limite que a dona definiu.",
            "O desconto passa do limite que a dona definiu. Diminua o desconto."),

    VENDA_JA_ESTORNADA(
            "Esta venda já foi estornada.",
            "Esta venda já foi estornada e não pode mudar."),

    /** O vendedor só estorna no dia da venda (HTTP 409). */
    ESTORNO_SO_DA_DONA(
            "Esta venda só pode ser estornada pela dona.",
            "Só dá para estornar a venda no mesmo dia. Peça à dona para estornar."),

    /** A maleta não está com o vendedor para vender (HTTP 409): rascunho, cancelada ou já acertada. */
    MALETA_NAO_ESTA_COM_VOCE(
            "Esta maleta não está com você.",
            "Esta maleta não está disponível para vender. Atualize a tela."),

    /** A maleta está ligada a outro aparelho do mesmo vendedor (HTTP 409). */
    MALETA_EM_OUTRO_APARELHO(
            "Esta maleta já está em outro aparelho.",
            "Esta maleta já está em outro aparelho. Peça à dona para liberar este aparelho."),

    /** O código lido não é de peça da maleta do vendedor (HTTP 404). */
    PECA_NAO_ENCONTRADA(
            "Não encontramos esta peça.",
            "Esta peça não consta na sua maleta."),

    /** A maleta já tem conferência (HTTP 409): a conferência é uma só por maleta. */
    MALETA_JA_CONFERIDA(
            "Esta maleta já foi conferida.",
            "Esta maleta já foi conferida. Atualize a tela para ver o resultado."),

    /**
     * Já existe cliente (não excluído) com este CPF (HTTP 409; 11/11/2026: clientes são da loja e o CPF é único). O corpo
     * traz o cliente existente (id, nome, telefone e cidade) para a pessoa escolher o que já está cadastrado.
     */
    CLIENTE_CPF_JA_CADASTRADO(
            "Este cliente já está cadastrado.",
            "Este cliente já está cadastrado. Escolha o cliente que já existe, em vez de cadastrar de novo."),

    /** O mesmo identificador de cliente voltou com outros dados (HTTP 409). */
    CLIENTE_JA_REGISTRADO_DIFERENTE(
            "Este cliente já foi cadastrado com outros dados.",
            "Este cliente já foi cadastrado com outros dados. Confira a lista de clientes antes de cadastrar de novo."),

    /** A maleta ainda não foi conferida: vender só depois de conferir (HTTP 409; decisão de 11/11/2026). */
    MALETA_NAO_CONFERIDA(
            "Confira a maleta antes de vender.",
            "Esta maleta ainda não foi conferida. Confira todas as peças da maleta antes de vender."),

    // --- Devolução do cliente (Fase 6, tarefa 6.3; ADR-0014; glossário, parte 6) ---

    DEVOLUCAO_NAO_ENCONTRADA(
            "Não encontramos esta devolução.",
            "Não encontramos esta devolução. Atualize a tela e tente de novo."),

    /** O mesmo identificador voltou com outros dados (HTTP 409). */
    DEVOLUCAO_JA_REGISTRADA_DIFERENTE(
            "Esta devolução já foi registrada com outros dados.",
            "Esta devolução já foi registrada com outros dados. Confira as devoluções antes de lançar de novo."),

    /** Mais peças do que ainda podem voltar daquela linha da venda (HTTP 409). O servidor monta o texto com a peça. */
    DEVOLUCAO_ACIMA_DO_VENDIDO(
            "Esta quantidade passa do que o cliente comprou.",
            "Esta quantidade passa do que o cliente comprou nesta venda. Confira a quantidade."),

    /** Fora do prazo da garantia, na rota online (HTTP 409; ADR-0014, itens 4 e 6). */
    GARANTIA_VENCIDA(
            "Passou do prazo da garantia.",
            "Passou do prazo da garantia. Peça autorização à dona."),

    /** Não é mais devolvido desde a tarefa 6.6 (a devolução abate as parcelas); fica só porque o Corona Vendas antigo conhece o código. HTTP 409. */
    VENDA_A_PRAZO_SEM_DEVOLUCAO(
            "Esta venda tem parcelas.",
            "Esta venda tem parcelas; a devolução dela chega em breve."),

    /** Recebimento acima do que falta pagar na venda (HTTP 409; tarefa 7.3). O servidor monta o texto com o valor. */
    RECEBIMENTO_ACIMA_DO_EM_ABERTO(
            "O valor passa do que falta pagar.",
            "O valor passa do que falta pagar nesta venda. Confira o valor."),

    /** Recebimento de venda que não tem nada a receber (HTTP 409; tarefa 7.3). */
    VENDA_SEM_VALOR_EM_ABERTO(
            "Esta venda não tem nada a receber.",
            "Esta venda não tem nada a receber."),

    /** O mesmo id de recebimento com outros dados, ou já usado em outro pagamento (HTTP 409; tarefa 7.3). */
    RECEBIMENTO_JA_REGISTRADO_DIFERENTE(
            "Este recebimento já foi registrado com outros dados.",
            "Este recebimento já foi registrado com outros dados. Confira os recebimentos antes de lançar de novo."),

    /** Recebimento inexistente ou de outro vendedor (HTTP 404; tarefa 7.3). */
    RECEBIMENTO_NAO_ENCONTRADO(
            "Não encontramos este recebimento.",
            "Não encontramos este recebimento. Atualize a tela e tente de novo."),

    /** Recebimento que já foi estornado (HTTP 409; tarefa 7.3). */
    RECEBIMENTO_JA_ESTORNADO(
            "Este recebimento já foi estornado.",
            "Este recebimento já foi estornado e não pode mudar."),

    /** Conta a receber do vendedor inexistente ou de outro vendedor (HTTP 404; tarefa 7.6). */
    CONTA_DO_VENDEDOR_NAO_ENCONTRADA(
            "Não encontramos esta conta a receber.",
            "Não encontramos esta conta a receber. Atualize a tela e tente de novo."),

    /** O vendedor não tem conta em aberto para receber (HTTP 409; tarefa 7.6). */
    VENDEDOR_SEM_CONTA_EM_ABERTO(
            "Este vendedor não tem conta em aberto.",
            "Este vendedor não tem conta em aberto. Não há o que receber."),

    /** Recebimento do vendedor acima do que ele deve em aberto (HTTP 409; tarefa 7.6). O servidor monta o texto com o valor. */
    RECEBIMENTO_DO_VENDEDOR_ACIMA_DO_EM_ABERTO(
            "O valor passa do que o vendedor deve.",
            "O valor passa do que o vendedor deve. Confira o valor."),

    /** Boleto pago que não é da conta do boleto ou não é do valor inteiro dela (HTTP 409; tarefa 7.6). */
    BOLETO_PAGO_PRECISA_SER_INTEIRO(
            "O boleto pago precisa ser do valor inteiro da conta.",
            "O boleto pago precisa ser do valor inteiro da conta do boleto. Escolha a conta do boleto e o valor todo."),

    /** O pagamento do acerto passa do que o vendedor deve nele (HTTP 409; tarefa 7.6). */
    PAGAMENTO_DO_ACERTO_PASSA_DO_DEVIDO(
            "O valor recebido passa do que o vendedor deve neste acerto.",
            "O valor recebido passa do que o vendedor deve neste acerto. Confira os valores."),

    /** O que ficou devendo não tem data de pagamento escolhida (HTTP 409; tarefa 7.6). */
    DATA_DO_RESTANTE_OBRIGATORIA(
            "Escolha a data de pagamento do que ficou devendo.",
            "Escolha a data de pagamento do que ficou devendo e em quantas parcelas."),

    /** O acerto a favor do vendedor só se paga na hora pelo valor inteiro, ou fica de crédito (HTTP 409; tarefa 7.6). */
    PAGAMENTO_AO_VENDEDOR_INVALIDO(
            "O pagamento ao vendedor não confere com o acerto.",
            "O pagamento ao vendedor não confere com o acerto. Pague o valor inteiro ou deixe de crédito para o próximo acerto."),

    /** O vendedor só estorna o recebimento no dia dele (HTTP 409; tarefa 7.3). */
    ESTORNO_DE_RECEBIMENTO_SO_DA_DONA(
            "Este recebimento só pode ser estornado pela dona.",
            "Só dá para estornar o recebimento no mesmo dia. Peça à dona para estornar."),

    /** O pagamento feito na hora da venda se corrige estornando a venda (HTTP 409; tarefa 7.3). */
    PAGAMENTO_NA_VENDA_SO_PELO_ESTORNO_DA_VENDA(
            "Este pagamento foi feito na hora da venda.",
            "Este pagamento foi feito na hora da venda. Para corrigir, estorne a venda."),

    /** Estorno de venda com devolução não cancelada (HTTP 409; ADR-0014, item 1; dúvida R6). */
    VENDA_COM_DEVOLUCAO(
            "Esta venda já tem devolução.",
            "Esta venda já tem devolução. Cancele a devolução antes de estornar."),

    /**
     * A maleta está em acerto (HTTP 409; ADR-0014, item 12; ADR-0012, item 4). Quem chama pode trocar o texto por
     * {@code comDetalhe} (venda, estorno, devolução, troca).
     */
    MALETA_EM_ACERTO(
            "Esta maleta está em acerto.",
            "Esta maleta está em acerto. Espere a dona terminar o acerto."),

    /** Acerto que não existe ou é de outro vendedor (HTTP 404; ADR-0012, item 16). */
    ACERTO_NAO_ENCONTRADO(
            "Não encontramos este acerto.",
            "Não encontramos este acerto. Atualize a lista e tente de novo."),

    /** O mesmo identificador de acerto voltou com outros dados (HTTP 409; ADR-0012, item 14). */
    ACERTO_JA_REGISTRADO_DIFERENTE(
            "Este acerto já foi registrado com outros dados.",
            "Este acerto já foi registrado com outros dados. Confira a lista de acertos."),

    /** O vendedor já tem um acerto em andamento (HTTP 409; ADR-0012, item 2). O corpo traz {@code acertoId}. */
    ACERTO_EM_ANDAMENTO(
            "Este vendedor já tem um acerto em andamento.",
            "Este vendedor já tem um acerto em andamento. Termine esse acerto antes de começar outro."),

    /** O acerto não está mais em andamento (terminou ou foi desistido; HTTP 409). */
    ACERTO_NAO_ESTA_EM_ANDAMENTO(
            "Este acerto não está em andamento.",
            "Este acerto já terminou ou foi desfeito. Abra a lista de acertos para ver como ficou."),

    /** Já existe um acerto desse vendedor nessa semana (HTTP 409; ADR-0012, item 2). */
    ACERTO_DA_SEMANA_JA_EXISTE(
            "Já existe um acerto desta semana.",
            "Este vendedor já tem acerto nesta semana. Escolha outra semana."),

    /** A semana do acerto já está fechada (HTTP 409). */
    SEMANA_FECHADA(
            "Esta semana já está fechada.",
            "Esta semana já está fechada. Escolha uma semana aberta."),

    /** Não achamos a semana do acerto (HTTP 409). */
    SEMANA_NAO_ENCONTRADA(
            "Não encontramos a semana do acerto.",
            "Cadastre a semana antes de iniciar o acerto."),

    /** O vendedor não tem maleta com ele para acertar (HTTP 409). */
    SEM_MALETA_PARA_ACERTO(
            "Você não tem maleta para acertar.",
            "Você não tem maleta para acertar agora. Fale com a dona."),

    /** O computador ainda tem operações para enviar (HTTP 409; ADR-0012, item 3). */
    ENVIOS_AGUARDANDO(
            "Ainda há vendas para enviar.",
            "Ainda há vendas neste computador esperando para ser enviadas. Conecte à internet, espere o envio terminar e tente de novo."),

    /** Maleta nova não é enviada enquanto o vendedor está em acerto (HTTP 409; ADR-0012, item 4). */
    VENDEDOR_EM_ACERTO(
            "Este vendedor está em acerto.",
            "Este vendedor está em acerto. Termine o acerto antes de enviar uma maleta."),

    /** Desfazer mais do que foi contado (HTTP 409; ADR-0012, item 15). */
    LEITURA_DEIXA_CONTAGEM_NEGATIVA(
            "Não dá para tirar mais do que foi contado.",
            "Não dá para tirar mais peças do que foram contadas. Confira a contagem."),

    /** O mesmo identificador de desistência voltou com outros dados, ou o acerto já foi desfeito de outro jeito (HTTP 409). */
    DESISTENCIA_JA_REGISTRADA_DIFERENTE(
            "Este acerto já foi desfeito de outro jeito.",
            "Este acerto já foi desfeito por outro pedido. Atualize a lista de acertos."),

    /** Sobrou peça na contagem do acerto (HTTP 409; ADR-0012, item 3.6). O corpo traz a lista em {@code sobras}. */
    SOBRA_NO_ACERTO(
            "Sobrou peça na contagem.",
            "Contaram mais peças do que o esperado. Confira as vendas e a contagem antes de fechar o acerto."),

    /** O mesmo fechamento voltou com outras decisões (HTTP 409). */
    FECHAMENTO_JA_REGISTRADO_DIFERENTE(
            "Este acerto já foi fechado com outras decisões.",
            "Este acerto já foi fechado com outras decisões. Abra a lista de acertos para ver como ficou."),

    /** O acerto já foi fechado por outro pedido (HTTP 409). */
    ACERTO_JA_FECHADO(
            "Este acerto já foi fechado.",
            "Este acerto já foi fechado. Abra a lista de acertos para ver como ficou."),

    /** Devolução ou crédito esperando a decisão da dona (HTTP 409). */
    REVISAO_PENDENTE_NO_ACERTO(
            "Há devolução esperando a sua decisão.",
            "Há devolução esperando a sua decisão. Mantenha ou não aceite antes de fechar o acerto."),

    /** As decisões ou o líquido não batem com o cálculo de agora (HTTP 409). O corpo traz a prévia nova. */
    FECHAMENTO_DESATUALIZADO(
            "O acerto mudou.",
            "Chegou coisa nova neste acerto. Confira de novo antes de fechar."),

    /** Não há cotação nenhuma para o acerto (HTTP 409). */
    SEM_COTACAO_PARA_O_ACERTO(
            "Falta a cotação do dia.",
            "Busque a cotação do dia antes de fechar o acerto."),

    /** Pendência que não existe (HTTP 404). */
    PENDENCIA_NAO_ENCONTRADA(
            "Não encontramos esta pendência.",
            "Não encontramos esta pendência. Atualize a lista e tente de novo."),

    /** A pendência já foi decidida (HTTP 409). */
    PENDENCIA_JA_RESOLVIDA(
            "Esta pendência já foi resolvida.",
            "Esta pendência já foi resolvida. Atualize a lista."),

    /** O mesmo comando de resolver voltou com outros dados (HTTP 409). */
    PENDENCIA_RESOLUCAO_DIFERENTE(
            "Esta pendência já foi resolvida de outro jeito.",
            "Esta pendência já foi resolvida de outro jeito. Atualize a lista."),

    /** O vendedor só cancela a devolução no dia dela (HTTP 409; ADR-0014, item 11). */
    CANCELAR_DEVOLUCAO_SO_DA_DONA(
            "Esta devolução só pode ser cancelada pela dona.",
            "Só dá para cancelar a devolução no mesmo dia. Peça à dona para cancelar."),

    DEVOLUCAO_JA_CANCELADA(
            "Esta devolução já foi cancelada.",
            "Esta devolução já foi cancelada e não pode mudar."),

    /** A dona já decidiu (mantida ou não aceita), ou a maleta da peça já não está com o vendedor (HTTP 409). */
    DEVOLUCAO_NAO_PODE_SER_CANCELADA(
            "Esta devolução não pode ser cancelada.",
            "A dona já decidiu sobre esta devolução e ela não pode mais ser cancelada."),

    /** A peça que voltou à maleta já foi vendida de novo: cancelar deixaria o saldo negativo (HTTP 409). */
    PECA_DA_DEVOLUCAO_JA_VENDIDA(
            "A peça desta devolução já foi vendida de novo.",
            "A peça desta devolução já foi vendida de novo e a devolução não pode ser cancelada. Fale com a dona."),

    // --- Autorização, revisão e antifraude (Fase 6, tarefa 6.4; ADR-0014, itens 5, 6, 10 e 12; glossário, parte 6) ---

    /** Pedido inexistente ou de outro vendedor (HTTP 404, sem dizer que existe). */
    AUTORIZACAO_NAO_ENCONTRADA(
            "Não encontramos este pedido de autorização.",
            "Não encontramos este pedido de autorização. Atualize a tela e tente de novo."),

    /** O mesmo identificador do pedido voltou com outros dados (HTTP 409). */
    AUTORIZACAO_JA_REGISTRADA_DIFERENTE(
            "Este pedido de autorização já foi feito com outros dados.",
            "Este pedido de autorização já foi feito com outros dados. Confira os pedidos antes de pedir de novo."),

    /** No prazo e sem passar do valor que a dona aprova antes: registre a devolução direto (HTTP 409). */
    AUTORIZACAO_DESNECESSARIA(
            "Esta devolução não precisa de autorização.",
            "Esta devolução está no prazo. Registre a devolução direto."),

    /** Já há um pedido esperando a dona ou aprovado e ainda válido para a venda (HTTP 409; dúvida P10). */
    AUTORIZACAO_JA_PEDIDA(
            "Já há um pedido de autorização para esta venda.",
            "Já há um pedido de autorização para esta venda. Espere a resposta da dona ou use a autorização aprovada."),

    /** A dona já decidiu o pedido de outro jeito (HTTP 409). */
    AUTORIZACAO_JA_DECIDIDA(
            "A dona já decidiu este pedido.",
            "A dona já decidiu este pedido de autorização e a decisão não muda."),

    /** A devolução veio com um pedido esperando a dona ou negado (HTTP 409). */
    AUTORIZACAO_NAO_APROVADA(
            "A dona ainda não aprovou esta devolução.",
            "Este pedido de autorização ainda não foi aprovado ou foi negado. Espere a resposta da dona."),

    /** A autorização já serviu para uma devolução (HTTP 409; dúvida P11). */
    AUTORIZACAO_JA_USADA(
            "Esta autorização já foi usada.",
            "Esta autorização já foi usada numa devolução. Peça outra autorização à dona."),

    /** Passou o último dia (Brasília) da autorização (HTTP 409; dúvida P7). */
    AUTORIZACAO_VENCIDA(
            "Esta autorização venceu.",
            "A autorização vale 7 dias e já venceu. Peça outra autorização à dona."),

    /** Outra venda, outro motivo ou peças além das autorizadas (HTTP 409; dúvida P6). */
    AUTORIZACAO_NAO_CONFERE(
            "Esta devolução não é a que a dona autorizou.",
            "Use a mesma venda, o mesmo motivo e no máximo as peças que a dona autorizou."),

    /** Reembolso acima do valor que a dona aprova antes, sem autorização, na rota online (HTTP 409). */
    DEVOLUCAO_PRECISA_DE_AUTORIZACAO(
            "Esta devolução precisa da autorização da dona.",
            "Esta devolução passa do valor que a dona aprova antes. Peça autorização à dona."),

    /** Manter ou Não aceitar uma devolução que não está para revisão (HTTP 409). */
    DEVOLUCAO_NAO_ESTA_EM_REVISAO(
            "Esta devolução não está para revisão.",
            "Só a devolução para revisão da dona pode ser mantida ou não aceita."),

    /** A dona já revisou a devolução de outro jeito (HTTP 409). */
    DEVOLUCAO_JA_REVISADA(
            "Esta devolução já foi revisada.",
            "A dona já decidiu sobre esta devolução e a decisão não muda."),

    // --- Troca e crédito do cliente (Fase 6, tarefa 6.5; ADR-0014, itens 8, 9 e 11; glossário, parte 6) ---

    TROCA_NAO_ENCONTRADA(
            "Não encontramos esta troca.",
            "Não encontramos esta troca. Atualize a tela e tente de novo."),

    /** O mesmo identificador voltou com outros dados, ou um id da troca já é de outro registro (HTTP 409). */
    TROCA_JA_REGISTRADA_DIFERENTE(
            "Esta troca já foi registrada com outros dados.",
            "Esta troca já foi registrada com outros dados. Confira as trocas antes de lançar de novo."),

    /** Os valores gravados não fecharam na conferência do fim da transação (HTTP 409; nunca 500). */
    TROCA_NAO_CONFERE(
            "Os valores desta troca não fecharam.",
            "Os valores desta troca não fecharam e nada foi gravado. Tente de novo; se continuar, fale com a dona."),

    TROCA_JA_CANCELADA(
            "Esta troca já foi cancelada.",
            "Esta troca já foi cancelada e não pode mudar."),

    /** A dona já decidiu a devolução da troca, uma maleta não está mais com o vendedor ou a peça já saiu (HTTP 409). */
    TROCA_NAO_PODE_SER_CANCELADA(
            "Esta troca não pode ser cancelada.",
            "A dona já decidiu sobre esta troca e ela não pode mais ser cancelada."),

    /** O vendedor só cancela a troca no dia dela (HTTP 409; ADR-0014, item 11). */
    CANCELAR_TROCA_SO_DA_DONA(
            "Esta troca só pode ser cancelada pela dona.",
            "Só dá para cancelar a troca no mesmo dia. Peça à dona para cancelar."),

    /** A sobra do crédito da troca já foi usada em outra venda (HTTP 409; ADR-0014, item 11). */
    CREDITO_DA_TROCA_JA_USADO(
            "O crédito desta troca já foi usado.",
            "O crédito que sobrou desta troca já foi usado em outra compra e a troca não pode ser cancelada. Fale com a dona."),

    /** Estorno da venda nova de uma troca: só pelo "Cancelar troca" (HTTP 409; dúvida T8). */
    VENDA_DE_TROCA(
            "Esta venda é de uma troca.",
            "Esta venda faz parte de uma troca. Use Cancelar troca."),

    /** Cancelar a devolução de uma troca: só pelo "Cancelar troca" (HTTP 409; dúvida T8). */
    DEVOLUCAO_DE_TROCA(
            "Esta devolução é de uma troca.",
            "Esta devolução faz parte de uma troca. Use Cancelar troca."),

    /** Crédito inexistente ou de outro vendedor (HTTP 404, sem dizer que existe). */
    CREDITO_NAO_ENCONTRADO(
            "Não encontramos este crédito.",
            "Não encontramos este crédito do cliente. Atualize a tela e tente de novo."),

    CREDITO_DE_OUTRO_CLIENTE(
            "Este crédito é de outro cliente.",
            "Este crédito é de outro cliente. Use só o crédito do cliente desta venda."),

    /** Crédito já todo usado (ou transferido) ou cancelado (HTTP 409). */
    CREDITO_SEM_SALDO(
            "Este crédito não tem mais saldo.",
            "Este crédito já foi todo usado ou foi cancelado. Escolha outra forma de pagamento."),

    /** O valor pago em crédito passa do saldo ("acima" é estrito: no valor exato passa; HTTP 409). */
    CREDITO_ACIMA_DO_SALDO(
            "O valor passa do saldo do crédito.",
            "O valor passa do que sobra neste crédito. Diminua o valor e pague o resto de outra forma."),

    /** Cancelar a devolução cujo crédito já foi usado (HTTP 409). */
    CREDITO_JA_USADO(
            "O crédito desta devolução já foi usado.",
            "O crédito desta devolução já foi usado em outra compra e a devolução não pode ser cancelada. Fale com a dona."),

    /** O mesmo identificador de transferência voltou com outros dados (HTTP 409). */
    CREDITO_JA_TRANSFERIDO_DIFERENTE(
            "Esta transferência já foi feita com outros dados.",
            "Esta transferência de crédito já foi feita com outros dados. Confira os créditos antes de transferir de novo."),

    // --- Crédito sem saldo pelo envio (tarefa 6.7; ADR-0014, item 9, linha de 13/11/2026) ---

    /** A venda com crédito para revisar não existe (HTTP 404). */
    CREDITO_SEM_SALDO_NAO_ENCONTRADO(
            "Não encontramos esta venda com crédito para revisar.",
            "Não encontramos esta venda com crédito para revisar. Atualize a tela e tente de novo."),

    /** A dona já decidiu a venda com crédito sem saldo (HTTP 409). */
    CREDITO_SEM_SALDO_NAO_ESTA_PARA_REVISAO(
            "Esta venda com crédito não está para revisão.",
            "A dona já decidiu sobre esta venda com crédito e a decisão não muda."),

    // --- Computadores do vendedor (Fase 5, tarefa 5.3; ADR-0013, itens 13 e 14) ---

    /** Segundo computador do vendedor, ainda sem a liberação da dona (HTTP 403). */
    APARELHO_NAO_LIBERADO(
            "Este computador ainda não foi liberado pela dona.",
            "Este computador ainda não foi liberado pela dona. Avise a dona para liberar este computador."),

    /** A dona trocou este computador por outro: ele só envia o que guardou (HTTP 403). */
    APARELHO_SUBSTITUIDO(
            "Este computador foi trocado por outro.",
            "A dona trocou este computador por outro. Use o computador novo para vender."),

    /** A dona bloqueou o computador (perdido ou roubado). Vai com a ordem de apagar os dados (HTTP 403). */
    APARELHO_BLOQUEADO(
            "Este computador foi desligado da loja.",
            "Este computador foi desligado da loja pela dona. Fale com a dona."),

    /** O identificador do computador já é de outra pessoa (HTTP 403). */
    APARELHO_DE_OUTRA_PESSOA(
            "Este computador já é usado por outro vendedor.",
            "Este computador já é usado por outro vendedor. Cada vendedor usa o próprio computador. Fale com a dona."),

    /** A dona tentou liberar um computador bloqueado ou já trocado (HTTP 409). */
    APARELHO_NAO_PODE_SER_LIBERADO(
            "Este computador não pode ser liberado.",
            "Este computador foi bloqueado ou trocado e não pode voltar a vender. Peça ao vendedor para entrar no "
                    + "computador que ele vai usar e libere esse."),

    APARELHO_NAO_ENCONTRADO(
            "Não encontramos este computador.",
            "Não encontramos este computador. Atualize a tela e tente de novo."),

    // --- Contas a pagar, caixa manual e fechar semana (Fase 7, tarefa 7.7; ADR-0012, itens 11, 12 e 17) ---

    /** As contas desta compra já foram geradas (HTTP 409): gerar de novo só desfazendo a compra. */
    CONTAS_A_PAGAR_JA_GERADAS(
            "As contas desta compra já foram geradas.",
            "As contas desta compra já foram geradas. Para refazer as parcelas, desfaça a compra e lance de novo."),

    /** A compra não tem valor a pagar (HTTP 409). */
    COMPRA_SEM_VALOR_A_PAGAR(
            "Esta compra não tem valor a pagar.",
            "Esta compra não tem valor a pagar, então não há contas para gerar."),

    /** Conta a pagar inexistente (HTTP 404). */
    CONTA_A_PAGAR_NAO_ENCONTRADA(
            "Não encontramos esta conta a pagar.",
            "Não encontramos esta conta a pagar. Atualize a tela e tente de novo."),

    /** A conta foi cancelada porque a compra foi desfeita (HTTP 409). */
    CONTA_A_PAGAR_CANCELADA(
            "Esta conta foi cancelada.",
            "Esta conta foi cancelada porque a compra foi desfeita. Não há o que pagar."),

    /** Pagamento a fornecedor inexistente (HTTP 404). */
    PAGAMENTO_A_FORNECEDOR_NAO_ENCONTRADO(
            "Não encontramos este pagamento.",
            "Não encontramos este pagamento. Atualize a tela e tente de novo."),

    /** O mesmo id de pagamento (ou de estorno) com outros dados (HTTP 409). */
    PAGAMENTO_A_FORNECEDOR_JA_REGISTRADO_DIFERENTE(
            "Este pagamento já foi registrado com outros dados.",
            "Este pagamento já foi registrado com outros dados. Confira a conta antes de lançar de novo."),

    /** O pagamento já foi estornado (HTTP 409). */
    PAGAMENTO_A_FORNECEDOR_JA_ESTORNADO(
            "Este pagamento já foi estornado.",
            "Este pagamento já foi estornado e não pode mudar."),

    /** O valor passa do que falta pagar (HTTP 422; o texto traz o saldo). */
    PAGAMENTO_ACIMA_DO_SALDO(
            "O valor passa do que falta pagar.",
            "O valor passa do que falta pagar desta conta."),

    /** A data cai numa semana já fechada (HTTP 409). */
    DATA_EM_SEMANA_FECHADA(
            "Essa semana já foi fechada.",
            "Essa semana já foi fechada. Lance com a data de hoje."),

    /** Lançamento do caixa inexistente (HTTP 404). */
    LANCAMENTO_DO_CAIXA_NAO_ENCONTRADO(
            "Não encontramos este lançamento.",
            "Não encontramos este lançamento. Atualize a tela e tente de novo."),

    /** O mesmo id de lançamento (ou de estorno) com outros dados (HTTP 409). */
    LANCAMENTO_DO_CAIXA_JA_REGISTRADO_DIFERENTE(
            "Este lançamento já foi registrado com outros dados.",
            "Este lançamento já foi registrado com outros dados. Confira o caixa antes de lançar de novo."),

    /** O lançamento já foi estornado (HTTP 409). */
    LANCAMENTO_JA_ESTORNADO(
            "Este lançamento já foi estornado.",
            "Este lançamento já foi estornado e não pode mudar."),

    /** Lançamento que veio de um pagamento ou recebimento não se estorna pelo caixa manual (HTTP 409). */
    LANCAMENTO_AUTOMATICO(
            "Este lançamento veio de um pagamento.",
            "Este lançamento veio de um pagamento. Desfaça o pagamento para ele sair do caixa."),

    /** Categoria do caixa inexistente (HTTP 404). */
    CATEGORIA_DO_CAIXA_NAO_ENCONTRADA(
            "Não encontramos esta categoria.",
            "Não encontramos esta categoria. Atualize a tela e tente de novo."),

    /** O mesmo id de categoria com outros dados, ou nome que já existe (HTTP 409). */
    CATEGORIA_DO_CAIXA_JA_EXISTE(
            "Já existe uma categoria com este nome.",
            "Já existe uma categoria com este nome. Use outro nome."),

    /** Categoria do sistema não muda (HTTP 409). */
    CATEGORIA_DO_CAIXA_DO_SISTEMA(
            "Esta categoria é do sistema.",
            "Esta categoria é do sistema e não pode ser alterada."),

    /** Semana fechada não muda nem se exclui (HTTP 409). */
    SEMANA_FECHADA_NAO_MUDA(
            "Esta semana já foi fechada.",
            "Esta semana já foi fechada e não muda mais."),

    /** A semana ainda não terminou (HTTP 409). */
    SEMANA_NAO_TERMINOU(
            "Esta semana ainda não terminou.",
            "Esta semana ainda não terminou. Feche a semana depois do último dia dela."),

    /** Há acerto em andamento na semana (HTTP 409). */
    SEMANA_COM_ACERTO_EM_ANDAMENTO(
            "Há acerto em andamento nesta semana.",
            "Há acerto em andamento nesta semana. Termine ou desista do acerto antes de fechar a semana."),

    // --- Receber os dados (Fase 5, tarefa 5.4; ADR-0013, item 3) ---

    /** O ponto de onde o computador recebe os dados não vale mais (backup restaurado): ele baixa tudo de novo (HTTP 409). */
    CURSOR_INVALIDO(
            "Os dados deste computador vão ser baixados de novo.",
            "Os dados deste computador vão ser baixados de novo. Suas vendas guardadas continuam salvas."),

    // --- Enviar as vendas guardadas (Fase 5, tarefa 5.5; ADR-0013, item 2) ---

    /** Outro envio deste computador ainda está em andamento (HTTP 429, com Retry-After). O app tenta de novo sozinho. */
    ENVIO_EM_ANDAMENTO(
            "Suas vendas já estão sendo enviadas.",
            "Suas vendas já estão sendo enviadas. Aguarde um instante."),

    /** O lote passou de 50 operações ou de 512 KB (HTTP 413). O app manda a metade. */
    LOTE_GRANDE_DEMAIS(
            "Muitas vendas de uma vez.",
            "Muitas vendas de uma vez. O Corona Vendas vai enviar em partes menores."),

    /** A operação usa um registro cujo envio foi recusado (o cliente da venda, a venda do estorno). */
    DEPENDE_DE_ENVIO_RECUSADO(
            "Esta venda depende de outro registro que não foi aceito.",
            "Esta venda depende de outro registro que não foi aceito. A dona já foi avisada."),

    /** O vendedor alterou um cliente que não atende (HTTP 409). */
    CLIENTE_DE_OUTRO_VENDEDOR(
            "Este cliente é atendido por outro vendedor.",
            "Este cliente é atendido por outro vendedor. Só quem atende o cliente muda o cadastro dele. Fale com a dona."),

    ENVIO_NAO_ENCONTRADO(
            "Não encontramos este envio.",
            "Não encontramos este envio. Atualize a tela e tente de novo."),

    /** A dona já tentou de novo ou descartou este envio (HTTP 409). */
    ENVIO_JA_RESOLVIDO(
            "Este envio já foi resolvido.",
            "Este envio já foi resolvido. Atualize a tela para ver como ficou."),

    /** Envio de mais de 90 dias: os dados completos já saíram do servidor e ele não pode ser repetido (HTTP 409). */
    ENVIO_SEM_DADOS(
            "Este envio é antigo demais para ser repetido.",
            "Este envio é antigo demais para ser repetido. Os detalhes completos ficam guardados por 90 dias. Descarte-o.");

    private final String titulo;
    private final String mensagem;

    CodigoErro(String titulo, String mensagem) {
        this.titulo = titulo;
        this.mensagem = mensagem;
    }

    public String titulo() {
        return titulo;
    }

    public String mensagem() {
        return mensagem;
    }
}
