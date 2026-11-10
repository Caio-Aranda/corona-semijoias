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

    PRODUTO_COM_FOTOS_DEMAIS(
            "Esta peça já tem 5 fotos.",
            "Esta peça já tem 5 fotos. Exclua uma das fotos para poder enviar outra."),

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
    LANCAMENTO_JA_REGISTRADO_DIFERENTE(
            "Este lançamento já foi registrado com outros dados.",
            "Este lançamento já foi registrado com outros dados. Atualize a tela, confira o histórico e lance de novo se precisar.");

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
