package br.com.coronasemijoias.api.sessoes;

/** Por que uma família de sessão foi revogada (coluna {@code sessoes.motivo_da_revogacao}; ADR-0004, item 2). */
public enum MotivoDaRevogacao {

    /** A pessoa saiu ({@code POST /api/auth/sair}). */
    SAIDA,

    /** Token anterior reapresentado depois da janela de tolerância. */
    REUSO_FORA_DA_JANELA,

    /** Token anterior reapresentado pela segunda vez, mesmo dentro da janela. */
    SEGUNDA_REAPRESENTACAO,

    /** Token anterior reapresentado depois de o sucessor já ter sido usado. */
    ANTERIOR_DEPOIS_DO_SUCESSOR,

    /** Token usado com outro identificador de aparelho. */
    OUTRO_APARELHO,

    /** A dona gerou uma senha temporária nova pela tela. */
    SENHA_REDEFINIDA,

    /** O comando do servidor {@code redefinir-senha-admin} gerou uma senha temporária nova. */
    SENHA_REDEFINIDA_PELO_SERVIDOR,

    /** A pessoa criou a senha nova (troca obrigatória). */
    SENHA_TROCADA,

    /** A dona desativou o usuário. */
    USUARIO_DESATIVADO,

    /** A dona mudou o tipo de acesso ou o vendedor do usuário: o token antigo leva dados que não valem mais. */
    ACESSO_ALTERADO,

    /** A dona bloqueou o computador (perdido ou roubado): a próxima chamada recebe a ordem de apagar os dados (tarefa 5.3). */
    APARELHO_BLOQUEADO;

    /** Revogações que mandam o Corona Vendas apagar os dados do computador (ADR-0013, item 14), e não só entrar de novo. */
    public boolean apagaOsDados() {
        return this == APARELHO_BLOQUEADO || this == USUARIO_DESATIVADO;
    }
}
