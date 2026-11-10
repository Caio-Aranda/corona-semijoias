package br.com.coronasemijoias.api.fotos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Processamento da foto (§20): recodifica, gira, tira o EXIF, três tamanhos e proteção contra imagem-bomba. */
class ProcessadorDeImagemTest {

    private final ProcessadorDeImagem processador = new ProcessadorDeImagem(25_000_000L);

    private static BufferedImage ler(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static int maiorLado(BufferedImage i) {
        return Math.max(i.getWidth(), i.getHeight());
    }

    private static String codigoDe(Throwable t) {
        return String.valueOf(((ErroDaApi) t).getBody().getProperties().get("codigo"));
    }

    @Test
    void geraOsTresTamanhosSemAumentarFotoPequena() throws Exception {
        var p = processador.processar(ImagensDeTeste.jpeg(ImagensDeTeste.imagem(3000, 2000)));
        assertThat(maiorLado(ler(p.miniatura()))).isEqualTo(150);
        assertThat(maiorLado(ler(p.media()))).isEqualTo(600);
        assertThat(maiorLado(ler(p.grande()))).isEqualTo(1600);
        assertThat(ler(p.grande()).getHeight()).isEqualTo(1067); // mantém a proporção 3:2
        assertThat(p.largura()).isEqualTo(1600);
        assertThat(p.altura()).isEqualTo(1067);

        var pequena = processador.processar(ImagensDeTeste.jpeg(ImagensDeTeste.imagem(300, 200)));
        assertThat(ler(pequena.grande()).getWidth()).isEqualTo(300); // nunca aumenta
        assertThat(ler(pequena.media()).getWidth()).isEqualTo(300);
        assertThat(maiorLado(ler(pequena.miniatura()))).isEqualTo(150);
    }

    @Test
    void hashEPeloConteudoEIgualParaAMesmaFoto() {
        byte[] a = ImagensDeTeste.jpeg(800, 600, 1);
        assertThat(processador.processar(a).hash()).isEqualTo(processador.processar(a.clone()).hash()).hasSize(64);
        assertThat(processador.processar(ImagensDeTeste.jpeg(800, 600, 2)).hash())
                .isNotEqualTo(processador.processar(a).hash());
    }

    @Test
    void aceitaJpegPngEWebp() throws Exception {
        assertThat(processador.processar(ImagensDeTeste.jpeg(ImagensDeTeste.imagem(400, 300))).grande()).isNotEmpty();
        assertThat(processador.processar(ImagensDeTeste.png(ImagensDeTeste.imagem(400, 300))).grande()).isNotEmpty();
        var webp = processador.processar(ImagensDeTeste.WEBP_1X1);
        assertThat(ler(webp.grande()).getWidth()).isEqualTo(1);
    }

    @Test
    void saidaSempreEJpegEFundoTransparenteViraBranco() throws Exception {
        BufferedImage transparente = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB); // todo transparente
        var p = processador.processar(ImagensDeTeste.png(transparente));
        byte[] grande = p.grande();
        assertThat(grande[0] & 0xFF).isEqualTo(0xFF);
        assertThat(grande[1] & 0xFF).isEqualTo(0xD8);
        Color c = new Color(ler(grande).getRGB(5, 5));
        assertThat(c.getRed()).isGreaterThan(240);
        assertThat(c.getBlue()).isGreaterThan(240);
    }

    @Test
    void corrigeARotacaoEDescartaOExif() throws Exception {
        byte[] jpeg = ImagensDeTeste.jpeg(ImagensDeTeste.imagem(200, 100)); // em cima vermelho, embaixo azul
        byte[] comExif = ImagensDeTeste.jpegComOrientacao(jpeg, 6); // precisa girar 90 graus para a direita
        assertThat(new String(comExif, StandardCharsets.ISO_8859_1)).contains(ImagensDeTeste.SEGREDO_DO_EXIF);

        var p = processador.processar(comExif);
        BufferedImage grande = ler(p.grande());
        assertThat(grande.getWidth()).isEqualTo(100);
        assertThat(grande.getHeight()).isEqualTo(200);
        assertThat(p.largura()).isEqualTo(100);
        assertThat(p.altura()).isEqualTo(200);
        // Girando para a direita, o vermelho (que estava em cima) vai para a direita.
        assertThat(new Color(grande.getRGB(90, 100)).getRed()).isGreaterThan(150);
        assertThat(new Color(grande.getRGB(10, 100)).getBlue()).isGreaterThan(150);

        for (byte[] saida : new byte[][] {p.miniatura(), p.media(), p.grande()}) {
            String texto = new String(saida, StandardCharsets.ISO_8859_1);
            assertThat(texto).doesNotContain(ImagensDeTeste.SEGREDO_DO_EXIF).doesNotContain("Exif");
        }
    }

    @Test
    void valeOConteudoNaoONomeNemOTipoInformado() {
        assertThatThrownBy(() -> processador.processar("%PDF-1.4 isto e um pdf".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(ErroDaApi.class).satisfies(e -> assertThat(codigoDe(e)).isEqualTo("FOTO_TIPO_NAO_ACEITO"));
        assertThatThrownBy(() -> processador.processar("MZ programa.exe".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(ErroDaApi.class);
        assertThatThrownBy(() -> processador.processar(new byte[0])).isInstanceOf(ErroDaApi.class);
    }

    @Test
    void arquivoDanificadoDaMensagemSimples() {
        byte[] lixo = new byte[2000];
        lixo[0] = (byte) 0xFF;
        lixo[1] = (byte) 0xD8;
        lixo[2] = (byte) 0xFF;
        for (int i = 3; i < lixo.length; i++) {
            lixo[i] = (byte) (i * 31);
        }
        assertThatThrownBy(() -> processador.processar(lixo)).isInstanceOf(ErroDaApi.class)
                .satisfies(e -> assertThat(((ErroDaApi) e).getBody().getDetail()).isEqualTo(CodigoErro.FOTO_NAO_LIDA.mensagem()));
        byte[] cortado = Arrays.copyOf(ImagensDeTeste.jpeg(ImagensDeTeste.imagem(800, 600)), 120);
        assertThatThrownBy(() -> processador.processar(cortado)).isInstanceOf(ErroDaApi.class);
    }

    @Test
    void imagemBombaERecusadaAntesDeAbrir() {
        // Declara 100 000 x 100 000 pontos (10 bilhões) em poucos bytes: nunca pode ser decodificada.
        byte[] bomba = ImagensDeTeste.pngQueDeclaraTamanhoEnorme(100_000, 100_000);
        assertThatThrownBy(() -> processador.processar(bomba)).isInstanceOf(ErroDaApi.class)
                .satisfies(e -> assertThat(codigoDe(e)).isEqualTo("FOTO_GRANDE_DEMAIS_EM_PONTOS"));
        // Lado enorme mesmo com poucos pontos no total.
        assertThatThrownBy(() -> processador.processar(ImagensDeTeste.pngQueDeclaraTamanhoEnorme(70_000, 10)))
                .isInstanceOf(ErroDaApi.class);
        // Limite configurável: 100 x 100 passa com 10 000 pontos e não passa com 9 999.
        byte[] cem = ImagensDeTeste.jpeg(ImagensDeTeste.imagem(100, 100));
        assertThat(new ProcessadorDeImagem(10_000).processar(cem)).isNotNull();
        assertThatThrownBy(() -> new ProcessadorDeImagem(9_999).processar(cem)).isInstanceOf(ErroDaApi.class);
    }
}
