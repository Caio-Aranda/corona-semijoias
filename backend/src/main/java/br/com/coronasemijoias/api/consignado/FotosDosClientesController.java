package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.consignado.FotosDosClientes.Foto;
import br.com.coronasemijoias.api.consignado.FotosDosClientes.TipoDeFoto;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas das fotos do cliente do consignado (ADR-0016, item 9). O representante envia e lê só as dos clientes dele; a dona lê
 * de qualquer cliente. O {@code tipo} é {@code cliente}, {@code documento-frente} ou {@code documento-verso}.
 */
@RestController
@RequestMapping("/api")
public class FotosDosClientesController {

    private final FotosDosClientes fotos;

    FotosDosClientesController(FotosDosClientes fotos) {
        this.fotos = fotos;
    }

    @Operation(operationId = "enviarFotoDoMeuCliente", summary = "Enviar a foto de um cliente",
            description = "O corpo é a imagem (JPG, PNG ou WebP). O servidor confere o SHA-256 contra o hash do cadastro; repetir é "
                    + "inofensivo (200). Cliente que ainda não chegou ao servidor: 409 CLIENTE_AINDA_NAO_CHEGOU (tente de novo). "
                    + "Cliente de outro representante: 404.")
    @PutMapping(path = "/vendedor/clientes/{id}/fotos/{tipo}")
    @ExigePerfil("VENDEDOR")
    public ResponseEntity<Void> enviar(@PathVariable UUID id, @PathVariable String tipo, @RequestBody byte[] conteudo,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        boolean ja = fotos.guardar(quem.vendedorId(), id, TipoDeFoto.daRota(tipo), conteudo);
        return ResponseEntity.status(ja ? HttpStatus.OK : HttpStatus.CREATED).build();
    }

    @Operation(operationId = "baixarFotoDoMeuCliente", summary = "Baixar a foto de um cliente do representante")
    @GetMapping("/vendedor/clientes/{id}/fotos/{tipo}")
    @ExigePerfil("VENDEDOR")
    public ResponseEntity<byte[]> baixar(@PathVariable UUID id, @PathVariable String tipo, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return resposta(fotos.ler(quem.vendedorId(), id, TipoDeFoto.daRota(tipo)));
    }

    @Operation(operationId = "baixarFotoDoCliente", summary = "Baixar a foto de um cliente (dona)")
    @GetMapping("/clientes/{id}/fotos/{tipo}")
    @ExigePerfil("ADMIN")
    public ResponseEntity<byte[]> baixarDaDona(@PathVariable UUID id, @PathVariable String tipo) {
        return resposta(fotos.ler(null, id, TipoDeFoto.daRota(tipo)));
    }

    private static ResponseEntity<byte[]> resposta(Foto f) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, f.tipoDeConteudo()).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ETAG, "\"" + f.hash() + "\"").body(f.conteudo());
    }
}
