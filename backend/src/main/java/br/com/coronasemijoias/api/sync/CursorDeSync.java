package br.com.coronasemijoias.api.sync;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cursor do pull (ADR-0002, item 5), no texto opaco {@code época:xid:seq}. O app só guarda e
 * devolve o texto, sem interpretar. A posição é o par {@code (xid, seq)} da última linha
 * entregue; o {@code xid} é um {@code xid8} (inteiro de 64 bits sem sinal).
 */
public record CursorDeSync(long epoca, long xid, long seq) {

    private static final Pattern FORMATO = Pattern.compile("^(\\d{1,19}):(\\d{1,20}):(\\d{1,19})$");

    public CursorDeSync {
        if (epoca < 1 || seq < 0) {
            throw new CursorInvalidoException("época ou sequência fora da faixa");
        }
    }

    /** Começo da carga completa na época atual. */
    public static CursorDeSync inicio(long epoca) {
        return new CursorDeSync(epoca, 0, 0);
    }

    public static CursorDeSync ler(String texto) {
        Matcher partes = FORMATO.matcher(texto == null ? "" : texto);
        if (!partes.matches()) {
            throw new CursorInvalidoException("formato diferente de época:xid:seq");
        }
        try {
            return new CursorDeSync(
                    Long.parseLong(partes.group(1)),
                    Long.parseUnsignedLong(partes.group(2)),
                    Long.parseLong(partes.group(3)));
        } catch (NumberFormatException e) {
            throw new CursorInvalidoException("número grande demais");
        }
    }

    public String texto() {
        return epoca + ":" + Long.toUnsignedString(xid) + ":" + seq;
    }

    /** Compara só a posição {@code (xid, seq)}, com o xid sem sinal. */
    public static int compararPosicao(long xidA, long seqA, long xidB, long seqB) {
        int porXid = Long.compareUnsigned(xidA, xidB);
        return porXid != 0 ? porXid : Long.compare(seqA, seqB);
    }

    @Override
    public String toString() {
        return texto();
    }
}
