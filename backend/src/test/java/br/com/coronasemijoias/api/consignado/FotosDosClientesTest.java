package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** As fotos do cliente do consignado (ADR-0016, item 9): hash conferido, repetir é inofensivo, só o dono lê. Escritos antes do código. */
class FotosDosClientesTest extends ApoioDoConsignado {

    private static byte[] jpeg(String conteudo) {
        byte[] resto = conteudo.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[resto.length + 3];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        System.arraycopy(resto, 0, b, 3, resto.length);
        return b;
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private UUID clienteComFoto(UsuarioDeTeste v, byte[] foto) throws Exception {
        UUID c = cliente(v);
        jdbc.update("UPDATE corona.clientes SET foto_hash = ? WHERE id = ?", sha(foto), c);
        return c;
    }

    private org.springframework.test.web.servlet.ResultActions enviar(RequestPostProcessor quem, UUID cliente, String tipo, byte[] corpo)
            throws Exception {
        return mvc.perform(put("/api/vendedor/clientes/" + cliente + "/fotos/" + tipo).with(quem)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(corpo));
    }

    @Test
    void fotoComOHashDoCadastroEGuardadaERepetirEInofensivo() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        byte[] foto = jpeg("foto da maria");
        UUID cliente = clienteComFoto(v, foto);
        enviar(como(v), cliente, "cliente", foto).andExpect(status().isCreated());
        enviar(como(v), cliente, "cliente", foto).andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM corona.fotos_de_cliente WHERE cliente_id = ?", cliente)).isEqualTo(1);
        byte[] volta = mvc.perform(get("/api/vendedor/clientes/" + cliente + "/fotos/cliente").with(como(v))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(volta).isEqualTo(foto);
        // A dona também lê.
        byte[] daDona = mvc.perform(get("/api/clientes/" + cliente + "/fotos/cliente").with(dona())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(daDona).isEqualTo(foto);
    }

    @Test
    void hashDiferenteDoCadastroERecusado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = clienteComFoto(v, jpeg("a foto certa"));
        enviar(como(v), cliente, "cliente", jpeg("outra foto")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("FOTO_DIFERENTE_DO_CADASTRO"));
        // O documento não tem hash no cadastro: também não confere.
        enviar(como(v), cliente, "documento-frente", jpeg("a foto certa")).andExpect(status().isUnprocessableEntity());
        assertThat(contar("SELECT count(*) FROM corona.fotos_de_cliente WHERE cliente_id = ?", cliente)).isZero();
    }

    @Test
    void foraDeImagemOuTipoDeRotaInvalidoERecusado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        enviar(como(v), cliente, "cliente", "isto não é imagem".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());
        enviar(como(v), cliente, "barba", jpeg("x")).andExpect(status().isNotFound());
    }

    @Test
    void clienteDeOutroRepresentanteDa404EClienteQueNaoChegouDa409() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UsuarioDeTeste outro = usuarios.criarVendedor();
        byte[] foto = jpeg("foto privada");
        UUID cliente = clienteComFoto(v, foto);
        enviar(como(v), cliente, "cliente", foto).andExpect(status().isCreated());
        enviar(como(outro), cliente, "cliente", foto).andExpect(status().isNotFound());
        mvc.perform(get("/api/vendedor/clientes/" + cliente + "/fotos/cliente").with(como(outro))).andExpect(status().isNotFound());
        // O cadastro ainda não chegou: o computador tenta de novo depois.
        enviar(como(v), UUID.randomUUID(), "cliente", foto).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_AINDA_NAO_CHEGOU"));
    }
}
