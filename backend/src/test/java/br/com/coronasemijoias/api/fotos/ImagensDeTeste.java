package br.com.coronasemijoias.api.fotos;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Imagens montadas na hora para os testes de foto (nada de arquivo no repositório). */
public final class ImagensDeTeste {

    /** WebP de 1x1 ponto (sem perda), o mesmo usado para detectar suporte a WebP nos navegadores. */
    public static final byte[] WEBP_1X1 = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

    public static final String SEGREDO_DO_EXIF = "GPS-LATITUDE-SECRETA";

    private ImagensDeTeste() {
    }

    /** Imagem com a metade de cima vermelha e a de baixo azul (dá para ver se girou). */
    public static BufferedImage imagem(int largura, int altura) {
        BufferedImage img = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, largura, altura / 2);
        g.setColor(Color.BLUE);
        g.fillRect(0, altura / 2, largura, altura - altura / 2);
        g.dispose();
        return img;
    }

    /** Foto diferente a cada valor de {@code semente} (hash diferente). */
    public static BufferedImage imagem(int largura, int altura, int semente) {
        BufferedImage img = imagem(largura, altura);
        g(img, new Color((semente * 53) % 256, (semente * 97) % 256, (semente * 29) % 256), 0, 0);
        g(img, new Color((semente * 71) % 256, (semente * 13) % 256, 40), 1, 0);
        return img;
    }

    private static void g(BufferedImage img, Color cor, int x, int y) {
        Graphics2D g = img.createGraphics();
        g.setColor(cor);
        g.fillRect(x * 16, y * 16, 16, 16);
        g.dispose();
    }

    public static byte[] jpeg(BufferedImage img) {
        return escrever(img, "jpg");
    }

    public static byte[] png(BufferedImage img) {
        return escrever(img, "png");
    }

    public static byte[] jpeg(int largura, int altura, int semente) {
        return jpeg(imagem(largura, altura, semente));
    }

    private static byte[] escrever(BufferedImage img, String formato) {
        try {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            ImageIO.write(img, formato, saida);
            return saida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** O JPEG com um bloco EXIF dizendo a orientação (1 a 8), com um texto "secreto" para provar que é descartado. */
    public static byte[] jpegComOrientacao(byte[] jpeg, int orientacao) {
        byte[] tiff = ByteBuffer.allocate(8 + 2 + 12 + 4)
                .put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8)
                .putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientacao).putShort((short) 0)
                .putInt(0)
                .array();
        byte[] exif = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] segredo = SEGREDO_DO_EXIF.getBytes(StandardCharsets.ISO_8859_1);
        int tamanho = 2 + exif.length + tiff.length + segredo.length;
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        saida.write(jpeg[0]);
        saida.write(jpeg[1]);
        saida.write(0xFF);
        saida.write(0xE1);
        saida.write(tamanho >> 8);
        saida.write(tamanho & 0xFF);
        saida.writeBytes(exif);
        saida.writeBytes(tiff);
        saida.writeBytes(segredo);
        saida.write(jpeg, 2, jpeg.length - 2);
        return saida.toByteArray();
    }

    /** PNG que só declara um tamanho enorme (sem os pontos): prova que a foto é recusada antes de abrir. */
    public static byte[] pngQueDeclaraTamanhoEnorme(int largura, int altura) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        saida.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = ByteBuffer.allocate(13).putInt(largura).putInt(altura).put((byte) 8).put((byte) 0)
                .put((byte) 0).put((byte) 0).put((byte) 0).array();
        bloco(saida, "IHDR", ihdr);
        bloco(saida, "IEND", new byte[0]);
        return saida.toByteArray();
    }

    private static void bloco(ByteArrayOutputStream saida, String tipo, byte[] dados) {
        byte[] t = tipo.getBytes(StandardCharsets.US_ASCII);
        saida.writeBytes(ByteBuffer.allocate(4).putInt(dados.length).array());
        saida.writeBytes(t);
        saida.writeBytes(dados);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(dados);
        saida.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
