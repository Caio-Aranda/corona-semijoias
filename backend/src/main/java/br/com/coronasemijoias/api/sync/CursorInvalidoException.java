package br.com.coronasemijoias.api.sync;

/**
 * O cursor do pull não vale mais (época antiga, depois de uma restauração de backup) ou não é um
 * cursor. O app precisa refazer a carga completa do que pertence ao vendedor (ADR-0002, item 5);
 * o que ele guardou para enviar não é afetado. A rota da Fase 5 transforma isto na resposta
 * "cursor inválido".
 */
public class CursorInvalidoException extends RuntimeException {

    public CursorInvalidoException(String motivo) {
        super("Cursor inválido (" + motivo + "): refaça a carga completa.");
    }
}
