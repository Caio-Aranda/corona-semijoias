package br.com.coronasemijoias.api.fotos;

import br.com.coronasemijoias.api.arquivos.TipoDeArquivo;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.http.HttpStatus;

/**
 * Transforma o arquivo que a dona enviou nos três tamanhos da §20 (miniatura 150, média 600, grande
 * 1600 pontos no lado maior), sempre em JPEG recodificado. Por isso:
 * <ul>
 *   <li>o tipo vale pelo <strong>conteúdo</strong> (JPEG, PNG ou WebP), nunca pelo nome ou pelo tipo informado;</li>
 *   <li>o tamanho em pontos é lido do cabeçalho e conferido <strong>antes</strong> de abrir a imagem
 *       (proteção contra imagem-bomba: poucos bytes que viram gigabytes de memória);</li>
 *   <li>tudo que não é imagem (EXIF, GPS, comentários, perfis) é descartado, porque a saída é montada
 *       só com os pontos da imagem; a rotação do EXIF é aplicada antes de descartá-lo;</li>
 *   <li>fundo transparente vira branco; nunca se aumenta uma foto pequena.</li>
 * </ul>
 */
public class ProcessadorDeImagem {

    static final int LADO_DA_MINIATURA = 150;
    static final int LADO_DO_MEDIO = 600;
    static final int LADO_DO_GRANDE = 1600;
    /** Nenhuma foto tem um lado maior que isto, nem que tenha poucos pontos no total (faixa de 70 000 x 10). */
    private static final int LADO_MAXIMO_DECLARADO = 20_000;

    /** Os três arquivos prontos. {@code largura} e {@code altura} são as do tamanho grande. */
    public record Processada(String hash, byte[] miniatura, byte[] media, byte[] grande, int largura, int altura) {
    }

    private final long maximoDePontos;

    public ProcessadorDeImagem(long maximoDePontos) {
        this.maximoDePontos = maximoDePontos;
    }

    public Processada processar(byte[] original) {
        TipoDeArquivo tipo = TipoDeArquivo.identificar(original).filter(t -> t != TipoDeArquivo.PDF)
                .orElseThrow(() -> new ErroDaApi(HttpStatus.UNSUPPORTED_MEDIA_TYPE, CodigoErro.FOTO_TIPO_NAO_ACEITO));
        try {
            int[] declarado = tamanhoDeclarado(tipo, original);
            if (declarado != null) {
                conferirTamanho(declarado[0], declarado[1]);
            }
            BufferedImage lida = abrir(original, tipo);
            conferirTamanho(lida.getWidth(), lida.getHeight());
            BufferedImage reta = girar(lida, tipo == TipoDeArquivo.JPEG ? orientacaoDoExif(original) : 1);
            lida.flush();

            BufferedImage grande = reduzir(reta, LADO_DO_GRANDE);
            BufferedImage media = reduzir(grande, LADO_DO_MEDIO);
            BufferedImage miniatura = reduzir(media, LADO_DA_MINIATURA);
            byte[] bytesGrande = jpeg(grande, 0.88f);
            return new Processada(sha256(bytesGrande), jpeg(miniatura, 0.80f), jpeg(media, 0.85f), bytesGrande,
                    grande.getWidth(), grande.getHeight());
        } catch (ErroDaApi e) {
            throw e;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.FOTO_NAO_LIDA);
        }
    }

    // --- tamanho ---

    private void conferirTamanho(int largura, int altura) {
        if (largura <= 0 || altura <= 0) {
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.FOTO_NAO_LIDA);
        }
        if (largura > LADO_MAXIMO_DECLARADO || altura > LADO_MAXIMO_DECLARADO
                || (long) largura * altura > maximoDePontos) {
            throw new ErroDaApi(HttpStatus.PAYLOAD_TOO_LARGE, CodigoErro.FOTO_GRANDE_DEMAIS_EM_PONTOS);
        }
    }

    /** Largura e altura que o próprio cabeçalho do arquivo declara (PNG e JPEG); vazio para os outros. */
    private static int[] tamanhoDeclarado(TipoDeArquivo tipo, byte[] c) {
        if (tipo == TipoDeArquivo.PNG && c.length >= 24) {
            return new int[] {inteiro(c, 16), inteiro(c, 20)};
        }
        if (tipo == TipoDeArquivo.JPEG) {
            int i = 2;
            while (i + 9 < c.length) {
                if ((c[i] & 0xFF) != 0xFF) {
                    return null;
                }
                int marcador = c[i + 1] & 0xFF;
                if (marcador == 0xFF) {
                    i++;
                    continue;
                }
                int tamanho = ((c[i + 2] & 0xFF) << 8) | (c[i + 3] & 0xFF);
                boolean quadro = marcador >= 0xC0 && marcador <= 0xCF && marcador != 0xC4 && marcador != 0xC8
                        && marcador != 0xCC;
                if (quadro) {
                    int altura = ((c[i + 5] & 0xFF) << 8) | (c[i + 6] & 0xFF);
                    int largura = ((c[i + 7] & 0xFF) << 8) | (c[i + 8] & 0xFF);
                    return new int[] {largura, altura};
                }
                if (marcador == 0xDA || marcador == 0xD9) {
                    return null;
                }
                i += 2 + tamanho;
            }
        }
        return null;
    }

    private static int inteiro(byte[] c, int posicao) {
        return ((c[posicao] & 0xFF) << 24) | ((c[posicao + 1] & 0xFF) << 16) | ((c[posicao + 2] & 0xFF) << 8)
                | (c[posicao + 3] & 0xFF);
    }

    // --- leitura ---

    private BufferedImage abrir(byte[] original, TipoDeArquivo tipo) throws IOException {
        try (ImageInputStream entrada = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> leitores = ImageIO.getImageReaders(entrada);
            if (!leitores.hasNext()) {
                throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.FOTO_NAO_LIDA);
            }
            ImageReader leitor = leitores.next();
            try {
                leitor.setInput(entrada, true, true);
                // Para WebP é o leitor que diz o tamanho (o cabeçalho dele é lido sem abrir a imagem).
                conferirTamanho(leitor.getWidth(0), leitor.getHeight(0));
                BufferedImage imagem = leitor.read(0);
                if (imagem == null) {
                    throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.FOTO_NAO_LIDA);
                }
                return imagem;
            } finally {
                leitor.dispose();
            }
        }
    }

    // --- rotação (EXIF) ---

    /** Orientação 1 a 8 do bloco EXIF do JPEG; 1 (sem girar) quando não há ou não dá para ler. */
    static int orientacaoDoExif(byte[] c) {
        int i = 2;
        while (i + 4 < c.length && (c[i] & 0xFF) == 0xFF) {
            int marcador = c[i + 1] & 0xFF;
            if (marcador == 0xDA || marcador == 0xD9) {
                return 1;
            }
            int tamanho = ((c[i + 2] & 0xFF) << 8) | (c[i + 3] & 0xFF);
            if (marcador == 0xE1 && tamanho >= 16 && i + 4 + 6 <= c.length && c[i + 4] == 'E' && c[i + 5] == 'x'
                    && c[i + 6] == 'i' && c[i + 7] == 'f' && c[i + 8] == 0 && c[i + 9] == 0) {
                int tiff = i + 10;
                int fim = Math.min(c.length, i + 2 + tamanho);
                return orientacaoDoTiff(c, tiff, fim);
            }
            i += 2 + tamanho;
        }
        return 1;
    }

    private static int orientacaoDoTiff(byte[] c, int tiff, int fim) {
        if (tiff + 8 > fim) {
            return 1;
        }
        boolean grande = c[tiff] == 'M' && c[tiff + 1] == 'M';
        boolean pequeno = c[tiff] == 'I' && c[tiff + 1] == 'I';
        if (!grande && !pequeno) {
            return 1;
        }
        long inicio = numero(c, tiff + 4, 4, grande);
        long pos = tiff + inicio;
        if (pos < tiff || pos + 2 > fim) {
            return 1;
        }
        int entradas = (int) numero(c, (int) pos, 2, grande);
        for (int e = 0; e < entradas; e++) {
            long base = pos + 2 + 12L * e;
            if (base + 12 > fim) {
                return 1;
            }
            if (numero(c, (int) base, 2, grande) == 0x0112) {
                int valor = (int) numero(c, (int) base + 8, 2, grande);
                return valor >= 1 && valor <= 8 ? valor : 1;
            }
        }
        return 1;
    }

    private static long numero(byte[] c, int posicao, int bytes, boolean grande) {
        long valor = 0;
        for (int k = 0; k < bytes; k++) {
            int b = c[posicao + (grande ? k : bytes - 1 - k)] & 0xFF;
            valor = (valor << 8) | b;
        }
        return valor;
    }

    /** Põe a imagem em pé segundo a orientação do EXIF e a deixa em RGB sobre fundo branco. */
    private static BufferedImage girar(BufferedImage origem, int orientacao) {
        int w = origem.getWidth();
        int h = origem.getHeight();
        boolean troca = orientacao >= 5;
        AffineTransform t = switch (orientacao) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
        BufferedImage destino = new BufferedImage(troca ? h : w, troca ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, destino.getWidth(), destino.getHeight());
            g.drawImage(origem, t, null);
        } finally {
            g.dispose();
        }
        return destino;
    }

    // --- tamanhos e gravação ---

    /** A imagem com o lado maior em {@code lado} pontos; se já for menor, devolve a mesma (nunca aumenta). */
    private static BufferedImage reduzir(BufferedImage origem, int lado) {
        int w = origem.getWidth();
        int h = origem.getHeight();
        int maior = Math.max(w, h);
        if (maior <= lado) {
            return origem;
        }
        int novaW = Math.max(1, Math.round((float) w * lado / maior));
        int novaH = Math.max(1, Math.round((float) h * lado / maior));
        BufferedImage atual = origem;
        // Metade por vez enquanto sobrar o dobro: evita o serrilhado de uma redução grande de uma vez só.
        while (atual.getWidth() / 2 >= novaW && atual.getHeight() / 2 >= novaH) {
            atual = redimensionar(atual, atual.getWidth() / 2, atual.getHeight() / 2,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        return redimensionar(atual, novaW, novaH, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage redimensionar(BufferedImage origem, int w, int h, Object interpolacao) {
        BufferedImage destino = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolacao);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(origem, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return destino;
    }

    /** JPEG só com os pontos da imagem (sem EXIF, miniatura embutida nem comentário). */
    private static byte[] jpeg(BufferedImage imagem, float qualidade) throws IOException {
        ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam parametros = escritor.getDefaultWriteParam();
            parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parametros.setCompressionQuality(qualidade);
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream fluxo = new MemoryCacheImageOutputStream(saida)) {
                escritor.setOutput(fluxo);
                escritor.write(null, new IIOImage(imagem, null, null), parametros);
            }
            return saida.toByteArray();
        } finally {
            escritor.dispose();
        }
    }

    static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
