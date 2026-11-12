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

    // --- Computadores do vendedor (Fase 5, tarefa 5.3; ADR-0013, itens 13 e 14) ---

    /** Segundo computador do vendedor, ainda sem a liberação da dona (HTTP 403). */
    APARELHO_NAO_LIBERADO(
            "Este computador ainda não foi liberado pela dona.",
            "Este computador ainda não foi liberado pela dona. Peça à dona para liberar este computador no Corona Gestão."),

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
