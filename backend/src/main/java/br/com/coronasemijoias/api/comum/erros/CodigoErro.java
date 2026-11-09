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

    NAO_REDEFINIR_A_PROPRIA_SENHA(
            "Você não pode redefinir a sua própria senha por aqui.",
            "Você não pode redefinir a sua própria senha por aqui. "
                    + "Peça a outra pessoa administradora ou fale com o suporte do sistema.");

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
